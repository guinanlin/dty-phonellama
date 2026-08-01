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
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SenseVoice ASR via sherpa-onnx (ONNX Runtime).
 *
 * Uses Silero VAD for segmentation, then Offline SenseVoice for each speech
 * segment. Provider selection prefers QNN on Qualcomm devices when a QNN model
 * pack is present; otherwise falls back to CPU ONNX.
 */
class SenseVoiceSherpaEngine(
  context: Context,
  model: Model,
) : AsrEngine {
  private val appContext = context.applicationContext
  private val lock = Any()
  private val modelDir = File(model.getPath(appContext)).parentFile
    ?: throw IllegalStateException("SenseVoice model directory is missing")

  var activeAccelerator: String
    private set
  private val recognizer: OfflineRecognizer
  private val vad: Vad
  private val vadWindowSize: Int = 512

  init {
    val requested =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "CPU")
    val providerChoice = SherpaSenseVoiceFactory.selectProvider(
      modelDir = modelDir,
      requestedAccelerator = requested,
    )
    activeAccelerator = providerChoice.label

    val vadPath = copyVadAsset(appContext)
    vad =
      Vad(
        config =
          VadModelConfig(
            sileroVadModelConfig =
              SileroVadModelConfig(
                model = vadPath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = vadWindowSize,
                maxSpeechDuration = 30f,
              ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
          ),
      )

    var chosen = providerChoice
    recognizer =
      try {
        SherpaSenseVoiceFactory.createRecognizer(modelDir, chosen)
      } catch (e: Throwable) {
        if (chosen.provider == "qnn") {
          Log.w(TAG, "QNN SenseVoice init failed, falling back to CPU", e)
          chosen = SherpaSenseVoiceFactory.ProviderChoice(provider = "cpu", label = "CPU")
          activeAccelerator = "CPU"
          SherpaSenseVoiceFactory.createRecognizer(modelDir, chosen)
        } else {
          throw e
        }
      }
    Log.i(TAG, "SenseVoice sherpa-onnx ready provider=$activeAccelerator dir=${modelDir.absolutePath}")
  }

  override fun transcribe(wavBytes: ByteArray): AsrResult {
    synchronized(lock) {
      val samples = decodeWavToFloatSamples(wavBytes)
      if (samples.isEmpty()) {
        return AsrResult(text = "")
      }

      val segments = segmentWithVad(samples)
      if (segments.isEmpty()) {
        // Short clips may not trip VAD; fall back to whole-utterance decode.
        return decodeSegment(samples)
      }

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
      try {
        vad.release()
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

  private fun segmentWithVad(samples: FloatArray): List<FloatArray> {
    vad.reset()
    val segments = mutableListOf<FloatArray>()
    var offset = 0
    while (offset + vadWindowSize <= samples.size) {
      val window = samples.copyOfRange(offset, offset + vadWindowSize)
      vad.acceptWaveform(window)
      while (!vad.empty()) {
        segments.add(vad.front().samples)
        vad.pop()
      }
      offset += vadWindowSize
    }
    // Flush trailing speech.
    vad.flush()
    while (!vad.empty()) {
      segments.add(vad.front().samples)
      vad.pop()
    }
    return segments
  }

  private fun copyVadAsset(context: Context): String {
    val vadFile = File(context.filesDir, VAD_FILE_NAME)
    if (!vadFile.exists() || vadFile.length() == 0L) {
      context.assets.open(VAD_FILE_NAME).use { input ->
        vadFile.outputStream().use { output -> input.copyTo(output) }
      }
    }
    return vadFile.absolutePath
  }

  companion object {
    private const val TAG = "SenseVoiceSherpa"
    private const val SAMPLE_RATE = 16000
    private const val VAD_FILE_NAME = "silero_vad.onnx"

    /** Decode 16-bit PCM WAV (or raw PCM16LE) into float samples in [-1, 1]. */
    fun decodeWavToFloatSamples(bytes: ByteArray): FloatArray {
      if (bytes.size < 44) {
        return pcm16ToFloat(bytes)
      }
      val header = String(bytes, 0, 4, Charsets.US_ASCII)
      if (header != "RIFF") {
        return pcm16ToFloat(bytes)
      }
      // Find "data" chunk.
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
      // Fallback: skip classic 44-byte header.
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
  }
}

/** Selects sherpa-onnx provider and builds OfflineRecognizer configs. */
object SherpaSenseVoiceFactory {
  private const val TAG = "SherpaSenseVoiceFactory"
  private const val MODEL_ONNX = "model.int8.onnx"
  private const val TOKENS = "tokens.txt"
  private const val QNN_LIB = "libmodel.so"
  private const val QNN_BIN = "model.bin"

  data class ProviderChoice(val provider: String, val label: String)

  fun selectProvider(
    modelDir: File,
    requestedAccelerator: String,
  ): ProviderChoice {
    val wantsQnn =
      requestedAccelerator.equals("QNN", ignoreCase = true) ||
        requestedAccelerator.equals("NPU", ignoreCase = true)
    val isQualcomm = isQualcommDevice()
    val hasQnnPack =
      File(modelDir, QNN_LIB).exists() &&
        (File(modelDir, QNN_BIN).exists() || File(modelDir, TOKENS).exists())
    return if (wantsQnn && isQualcomm && hasQnnPack) {
      ProviderChoice(provider = "qnn", label = "QNN")
    } else {
      if (wantsQnn) {
        Log.i(
          TAG,
          "QNN not available (qualcomm=$isQualcomm pack=$hasQnnPack); using CPU",
        )
      }
      ProviderChoice(provider = "cpu", label = "CPU")
    }
  }

  fun createRecognizer(modelDir: File, choice: ProviderChoice): OfflineRecognizer {
    val tokens = File(modelDir, TOKENS)
    require(tokens.exists()) { "Missing SenseVoice tokens.txt in ${modelDir.absolutePath}" }

    val senseVoiceModel =
      when (choice.provider) {
        "qnn" -> {
          val lib = File(modelDir, QNN_LIB)
          require(lib.exists()) { "Missing QNN libmodel.so in ${modelDir.absolutePath}" }
          lib.absolutePath
        }
        else -> {
          val onnx = File(modelDir, MODEL_ONNX)
          // Also accept whatever downloadFileName produced as the main model file.
          val fallback =
            modelDir.listFiles()?.firstOrNull {
              it.isFile && it.name.endsWith(".onnx") && !it.name.contains("vad")
            }
          val modelFile = if (onnx.exists()) onnx else fallback
          require(modelFile != null && modelFile.exists()) {
            "Missing SenseVoice ONNX model in ${modelDir.absolutePath}"
          }
          modelFile.absolutePath
        }
      }

    val config =
      OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig =
          OfflineModelConfig(
            senseVoice =
              OfflineSenseVoiceModelConfig(
                model = senseVoiceModel,
                language = "auto",
                useInverseTextNormalization = true,
              ),
            tokens = tokens.absolutePath,
            numThreads = if (choice.provider == "cpu") 2 else 1,
            provider = choice.provider,
            modelType = "sense_voice",
          ),
      )
    return OfflineRecognizer(config = config)
  }

  private fun isQualcommDevice(): Boolean {
    val hardware = listOf(
      Build.HARDWARE,
      Build.BOARD,
      Build.SOC_MODEL.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S } ?: "",
      Build.MANUFACTURER,
    ).joinToString(" ").lowercase()
    return hardware.contains("qcom") ||
      hardware.contains("qualcomm") ||
      hardware.contains("sm8") ||
      hardware.contains("lahaina") ||
      hardware.contains("kona") ||
      hardware.contains("taro")
  }
}
