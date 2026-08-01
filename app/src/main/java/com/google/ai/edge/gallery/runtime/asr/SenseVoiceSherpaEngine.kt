/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.ai.edge.gallery.runtime.asr

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SenseVoice ASR via sherpa-onnx.
 *
 * Prefers Qualcomm QNN when the QNN pack, HTP runtime, and context binary
 * (`model.bin`) are available; otherwise falls back to CPU ONNX.
 *
 * Note: Silero VAD is intentionally not used. Mixed sherpa AAR/native builds
 * caused native SIGSEGV inside `Vad.acceptWaveform` / `segmentWithVad` on
 * device. Long audio is split by fixed duration instead (QNN max 30s).
 */
class SenseVoiceSherpaEngine(
  context: Context,
  model: Model,
) : AsrEngine {
  private val appContext = context.applicationContext
  private val lock = Any()
  private val modelDir = SherpaSenseVoiceFactory.resolveModelDir(appContext, model)

  var activeAccelerator: String
    private set
  private val recognizer: OfflineRecognizer
  private val maxSegmentSamples: Int

  init {
    val requested =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "QNN")
    val providerChoice =
      SherpaSenseVoiceFactory.selectProvider(
        context = appContext,
        modelDir = modelDir,
        requestedAccelerator = requested,
      )
    activeAccelerator = providerChoice.label
    maxSegmentSamples =
      if (providerChoice.provider == "qnn") {
        (SAMPLE_RATE * QNN_MAX_SEGMENT_SECONDS).toInt()
      } else {
        (SAMPLE_RATE * CPU_MAX_SEGMENT_SECONDS).toInt()
      }

    var chosen = providerChoice
    recognizer =
      try {
        SherpaSenseVoiceFactory.createRecognizer(appContext, modelDir, chosen)
      } catch (e: Throwable) {
        if (chosen.provider == "qnn") {
          Log.w(TAG, "QNN SenseVoice init failed, falling back to CPU", e)
          chosen = SherpaSenseVoiceFactory.ProviderChoice(provider = "cpu", label = "CPU")
          activeAccelerator = "CPU"
          SherpaSenseVoiceFactory.createRecognizer(appContext, modelDir, chosen)
        } else {
          throw e
        }
      }
    Log.i(
      TAG,
      "SenseVoice sherpa-onnx ready provider=$activeAccelerator dir=${modelDir.absolutePath}",
    )
  }

  override fun transcribe(wavBytes: ByteArray): AsrResult {
    synchronized(lock) {
      val samples = decodeWavToFloatSamples(wavBytes)
      if (samples.isEmpty()) {
        return AsrResult(text = "")
      }

      val segments = splitByDuration(samples, maxSegmentSamples)
      val texts = mutableListOf<String>()
      var language: String? = null
      var emotion: String? = null
      val events = linkedSetOf<String>()
      for (segment in segments) {
        val result = decodeSegment(segment)
        if (result.text.isNotBlank()) texts.add(result.text)
        if (language == null) language = result.language
        if (emotion == null) emotion = result.emotion
        events.addAll(result.events)
      }
      return AsrResult(
        text = texts.joinToString(" ").trim(),
        language = language,
        emotion = emotion,
        events = events.toList(),
      )
    }
  }

  override fun close() {
    synchronized(lock) {
      try {
        recognizer.release()
      } catch (_: Throwable) {
      }
    }
  }

  private fun decodeSegment(samples: FloatArray): AsrResult {
    val stream = recognizer.createStream()
    return try {
      stream.acceptWaveform(samples, SAMPLE_RATE)
      recognizer.decode(stream)
      val result = recognizer.getResult(stream)
      AsrResult(
        text = result.text.trim(),
        language = result.lang.takeIf { it.isNotBlank() },
        emotion = result.emotion.takeIf { it.isNotBlank() },
        events = result.event.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
      )
    } finally {
      stream.release()
    }
  }

  companion object {
    private const val TAG = "SenseVoiceSherpa"
    private const val SAMPLE_RATE = 16000
    private const val QNN_MAX_SEGMENT_SECONDS = 30
    private const val CPU_MAX_SEGMENT_SECONDS = 60

    fun decodeWavToFloatSamples(bytes: ByteArray): FloatArray {
      if (bytes.size < 44) {
        return pcm16ToFloat(bytes)
      }
      val header = String(bytes, 0, 4, Charsets.US_ASCII)
      if (header != "RIFF") {
        return pcm16ToFloat(bytes)
      }
      var offset = 12
      while (offset + 8 <= bytes.size) {
        val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
        val chunkSize =
          ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        offset += 8
        if (chunkId == "data") {
          val end = (offset + chunkSize).coerceAtMost(bytes.size)
          return pcm16ToFloat(bytes.copyOfRange(offset, end))
        }
        offset += chunkSize
      }
      return pcm16ToFloat(bytes.copyOfRange(44, bytes.size))
    }

    private fun pcm16ToFloat(pcm: ByteArray): FloatArray {
      val sampleCount = pcm.size / 2
      val out = FloatArray(sampleCount)
      val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
      for (i in 0 until sampleCount) {
        out[i] = buf.short / 32768.0f
      }
      return out
    }

    /** Split audio into fixed-length chunks; last chunk may be shorter. */
    fun splitByDuration(samples: FloatArray, maxSamples: Int): List<FloatArray> {
      if (samples.isEmpty()) return emptyList()
      if (maxSamples <= 0 || samples.size <= maxSamples) return listOf(samples)
      val out = ArrayList<FloatArray>((samples.size + maxSamples - 1) / maxSamples)
      var offset = 0
      while (offset < samples.size) {
        val end = (offset + maxSamples).coerceAtMost(samples.size)
        out.add(samples.copyOfRange(offset, end))
        offset = end
      }
      return out
    }
  }
}

/** Selects sherpa-onnx provider and builds OfflineRecognizer configs. */
object SherpaSenseVoiceFactory {
  private const val TAG = "SherpaSenseVoiceFactory"
  private const val MODEL_ONNX = "model.int8.onnx"
  private const val TOKENS = "tokens.txt"
  private const val QNN_LIB = "libmodel.so"
  private const val QNN_BIN = "model.bin"
  private const val QNN_BACKEND = "libQnnHtp.so"
  private const val QNN_SYSTEM = "libQnnSystem.so"

  data class ProviderChoice(val provider: String, val label: String)

  fun resolveModelDir(context: Context, model: Model): File {
    val base =
      File(
        listOf(context.getExternalFilesDir(null)?.absolutePath, model.normalizedName, model.version)
          .joinToString(File.separator),
      )
    val candidates =
      buildList {
        if (model.unzipDir.isNotEmpty()) add(File(base, model.unzipDir))
        add(base)
        base.listFiles()?.filter { it.isDirectory }?.forEach { add(it) }
      }
        .distinct()
    val withTokens = candidates.filter { File(it, TOKENS).exists() }
    withTokens.firstOrNull { File(it, QNN_LIB).exists() }?.let {
      return it
    }
    withTokens.firstOrNull()?.let {
      return it
    }
    candidates
      .firstOrNull { File(it, MODEL_ONNX).exists() || File(it, QNN_LIB).exists() }
      ?.let {
        return it
      }
    return if (model.isZip && model.unzipDir.isNotEmpty()) File(base, model.unzipDir) else base
  }

  fun selectProvider(
    context: Context,
    modelDir: File,
    requestedAccelerator: String,
  ): ProviderChoice {
    val wantsQnn =
      requestedAccelerator.equals("QNN", ignoreCase = true) ||
        requestedAccelerator.equals("NPU", ignoreCase = true)
    val isQualcomm = isQualcommDevice()
    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val hasRuntime =
      File(nativeDir, QNN_BACKEND).exists() && File(nativeDir, QNN_SYSTEM).exists()
    val hasQnnPack = File(modelDir, QNN_LIB).exists() && File(modelDir, TOKENS).exists()
    // First-run generation of model.bin from libmodel.so is known to hard-crash on
    // some devices; only enable QNN when a ready context binary is already present.
    val hasContextBinary = File(modelDir, QNN_BIN).isFile && File(modelDir, QNN_BIN).length() > 0
    return if (wantsQnn && isQualcomm && hasQnnPack && hasRuntime && hasContextBinary) {
      ProviderChoice(provider = "qnn", label = "QNN")
    } else {
      if (wantsQnn) {
        Log.i(
          TAG,
          "QNN not available (qualcomm=$isQualcomm pack=$hasQnnPack runtime=$hasRuntime " +
            "model.bin=$hasContextBinary); using CPU",
        )
      }
      ProviderChoice(provider = "cpu", label = "CPU")
    }
  }

  fun createRecognizer(
    context: Context,
    modelDir: File,
    choice: ProviderChoice,
  ): OfflineRecognizer {
    val tokens = File(modelDir, TOKENS)
    require(tokens.exists()) { "Missing SenseVoice tokens.txt in ${modelDir.absolutePath}" }

    val nativeDir = context.applicationInfo.nativeLibraryDir
    val senseVoice =
      when (choice.provider) {
        "qnn" -> {
          val lib = File(modelDir, QNN_LIB)
          require(lib.exists()) { "Missing QNN libmodel.so in ${modelDir.absolutePath}" }
          val backend = File(nativeDir, QNN_BACKEND)
          val system = File(nativeDir, QNN_SYSTEM)
          require(backend.exists()) { "Missing $QNN_BACKEND in $nativeDir" }
          require(system.exists()) { "Missing $QNN_SYSTEM in $nativeDir" }
          val contextBinary = File(modelDir, QNN_BIN)
          OfflineSenseVoiceModelConfig(
            model = lib.absolutePath,
            language = "auto",
            useInverseTextNormalization = true,
            qnnConfig =
              QnnConfig(
                backendLib = backend.absolutePath,
                contextBinary = contextBinary.absolutePath,
                systemLib = system.absolutePath,
              ),
          )
        }
        else -> {
          val onnx = File(modelDir, MODEL_ONNX)
          val parentOnnx = modelDir.parentFile?.let { File(it, MODEL_ONNX) }
          val fallback =
            sequenceOf(modelDir, modelDir.parentFile)
              .filterNotNull()
              .flatMap { dir -> dir.listFiles()?.asSequence() ?: emptySequence() }
              .firstOrNull {
                it.isFile && it.name.endsWith(".onnx") && !it.name.contains("vad")
              }
          val modelFile =
            when {
              onnx.exists() -> onnx
              parentOnnx != null && parentOnnx.exists() -> parentOnnx
              else -> fallback
            }
          require(modelFile != null && modelFile.exists()) {
            "Missing SenseVoice ONNX model in ${modelDir.absolutePath}"
          }
          OfflineSenseVoiceModelConfig(
            model = modelFile.absolutePath,
            language = "auto",
            useInverseTextNormalization = true,
          )
        }
      }

    val config =
      OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig =
          OfflineModelConfig(
            senseVoice = senseVoice,
            tokens = tokens.absolutePath,
            numThreads = if (choice.provider == "cpu") 2 else 1,
            provider = choice.provider,
            modelType = "sense_voice",
          ),
      )
    return OfflineRecognizer(config = config)
  }

  private fun isQualcommDevice(): Boolean {
    val hardware =
      listOf(
          Build.HARDWARE,
          Build.BOARD,
          Build.SOC_MODEL.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S } ?: "",
          Build.MANUFACTURER,
        )
        .joinToString(" ")
        .lowercase()
    return hardware.contains("qcom") ||
      hardware.contains("qualcomm") ||
      hardware.contains("sm8") ||
      hardware.contains("lahaina") ||
      hardware.contains("kona") ||
      hardware.contains("taro")
  }
}
