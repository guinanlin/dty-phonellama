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
    // SenseVoice has no built-in punctuation; load sherpa ct-transformer in background.
    SherpaPunctuation.ensureAsync(appContext)
  }

  override fun transcribe(wavBytes: ByteArray): AsrResult {
    synchronized(lock) {
      val samples = decodeWavToFloatSamples(wavBytes)
      return transcribeSamplesInternal(samples)
    }
  }

  override fun transcribeSamples(samples: FloatArray): AsrResult {
    synchronized(lock) {
      return transcribeSamplesInternal(samples)
    }
  }

  private fun transcribeSamplesInternal(samples: FloatArray): AsrResult {
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
    val joined = joinTranscript(texts)
    val punctuated = SherpaPunctuation.addPunctuation(joined)
    if (punctuated != joined) {
      Log.d(TAG, "punctuated '${joined.take(60)}' → '${punctuated.take(60)}'")
    }
    return AsrResult(
      text = punctuated,
      language = language,
      emotion = emotion,
      events = events.toList(),
    )
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
    if (samples.isEmpty()) {
      return AsrResult(text = "")
    }
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
    } catch (e: Throwable) {
      Log.e(TAG, "decodeSegment failed for ${samples.size} samples", e)
      throw e
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

    /** Join ASR pieces without inserting spaces between CJK characters. */
    fun joinTranscript(parts: List<String>): String {
      if (parts.isEmpty()) return ""
      val sb = StringBuilder()
      for (part in parts) {
        val piece = part.trim()
        if (piece.isEmpty()) continue
        if (sb.isEmpty()) {
          sb.append(piece)
          continue
        }
        val prev = sb.last()
        val next = piece.first()
        val needSpace = !isCjk(prev) && !isCjk(next) && !prev.isWhitespace() && !next.isWhitespace()
        if (needSpace) sb.append(' ')
        sb.append(piece)
      }
      return sb.toString().trim()
    }

    private fun isCjk(c: Char): Boolean {
      val block = Character.UnicodeBlock.of(c)
      return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
        block == Character.UnicodeBlock.GENERAL_PUNCTUATION
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
    // If the catalog declares an unzipDir, only use it when it actually contains
    // the required model artifacts. Otherwise fall back to the base dir or any
    // sibling directory that has a model file.
    val candidates =
      buildList {
        if (model.unzipDir.isNotEmpty()) {
          val unzipDir = File(base, model.unzipDir)
          if (unzipDir.isDirectory && unzipDir.listFiles()?.any { it.isFile } == true) {
            add(unzipDir)
          }
        }
        add(base)
        base.listFiles()?.filter { it.isDirectory }?.forEach { add(it) }
      }
        .distinct()
    Log.i(TAG, "resolveModelDir candidates: ${candidates.map { it.absolutePath }}")
    val withTokens = candidates.filter { File(it, TOKENS).exists() }
    withTokens.firstOrNull { File(it, QNN_LIB).exists() }?.let {
      Log.i(TAG, "resolveModelDir chose QNN dir: ${it.absolutePath}")
      return it
    }
    withTokens.firstOrNull()?.let {
      Log.i(TAG, "resolveModelDir chose token dir: ${it.absolutePath}")
      return it
    }
    candidates
      .firstOrNull { File(it, MODEL_ONNX).exists() || File(it, QNN_LIB).exists() }
      ?.let {
        Log.i(TAG, "resolveModelDir chose model dir: ${it.absolutePath}")
        return it
      }
    Log.w(TAG, "resolveModelDir falling back to: ${base.absolutePath}")
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
    val contextBinary = File(modelDir, QNN_BIN)
    val hasContextBinary = contextBinary.isFile && contextBinary.length() > 0
    val useQnn = wantsQnn && isQualcomm && hasQnnPack && hasRuntime && hasContextBinary
    Log.i(
      TAG,
      "Provider check: wantsQnn=$wantsQnn qualcomm=$isQualcomm hasQnnPack=$hasQnnPack " +
        "hasRuntime=$hasRuntime contextBinary=${contextBinary.absolutePath} " +
        "contextBinaryExists=$hasContextBinary -> useQnn=$useQnn",
    )
    return if (useQnn) {
      ProviderChoice(provider = "qnn", label = "QNN")
    } else {
      ProviderChoice(provider = "cpu", label = "CPU")
    }
  }

  fun createRecognizer(
    context: Context,
    modelDir: File,
    choice: ProviderChoice,
  ): OfflineRecognizer {
    val nativeDir = context.applicationInfo.nativeLibraryDir
    val senseVoice =
      when (choice.provider) {
        "qnn" -> {
          // sherpa-onnx 1.12.17's Android binding does not expose QnnConfig.
          // Keep the provider selection logic, but let the caller fall back to CPU.
          error("QNN SenseVoice is unavailable in the bundled sherpa-onnx 1.12.17 binding")
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
          // Prefer zh over auto: auto often mis-tags Mandarin as <|yue|> and hurts accuracy.
          OfflineSenseVoiceModelConfig(
            model = modelFile.absolutePath,
            language = "zh",
            useInverseTextNormalization = true,
          )
        }
      }

    // Tokens must match the exact model file being used. For CPU the ONNX lives in the
    // parent dir, so prefer tokens there. For QNN the lib/tokens live in the unzip dir.
    val modelFile =
      when (choice.provider) {
        "qnn" -> File(modelDir, QNN_LIB)
        else -> File(senseVoice.model)
      }
    val tokens =
      sequenceOf(modelFile.parentFile, modelDir, modelDir.parentFile)
        .filterNotNull()
        .map { File(it, TOKENS) }
        .firstOrNull { it.isFile }
    require(tokens != null) { "Missing SenseVoice tokens.txt near ${modelDir.absolutePath}" }

    Log.i(
      TAG,
      "SenseVoice ${choice.provider} model: ${modelFile.absolutePath}, tokens: ${tokens.absolutePath}",
    )

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
