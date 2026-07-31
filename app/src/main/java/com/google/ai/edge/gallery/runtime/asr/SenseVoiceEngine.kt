/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.ai.edge.gallery.runtime.asr

import android.content.Context
import com.google.ai.edge.gallery.data.Model
import java.io.File
import java.util.UUID

/**
 * SenseVoice GGUF engine.
 *
 * The v0.1.9 upstream implementation is a command-line inference program. The Android native
 * library compiles that implementation with its entry point renamed and invokes it through JNI.
 * Calls are serialized because the upstream CLI creates a CPU graph and loads the GGUF model per
 * request.
 */
class SenseVoiceEngine(
  context: Context,
  model: Model,
) : AsrEngine {
  private val appContext = context.applicationContext
  private val modelPath = model.getPath(appContext)
  private val vadPath = copyVadAsset(appContext)
  private val lock = Any()

  override fun transcribe(wavBytes: ByteArray): AsrResult {
    synchronized(lock) {
      val audioFile = File(appContext.cacheDir, "sensevoice-${UUID.randomUUID()}.wav")
      return try {
        audioFile.writeBytes(wavBytes)
        val raw = SenseVoiceNative.nativeTranscribe(
          modelPath = modelPath,
          vadPath = vadPath,
          audioPath = audioFile.absolutePath,
        ) ?: throw IllegalStateException("SenseVoice native inference returned no result")
        parseResult(raw)
      } finally {
        audioFile.delete()
      }
    }
  }

  override fun close() {
    // The upstream CLI loads and frees its native graph inside each invocation.
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

  private fun parseResult(raw: String): AsrResult {
    val tags = Regex("<\\|([^|]+)\\|>").findAll(raw).map { it.groupValues[1] }.toList()
    val language = tags.firstOrNull { it in LANGUAGE_TAGS }
    val emotion = tags.firstOrNull { it in EMOTION_TAGS }
    val events = tags.filter { it in EVENT_TAGS }
    val text = raw
      .replace(Regex("<\\|[^|]+\\|>"), "")
      .replace("\n", " ")
      .trim()
    return AsrResult(text = text, language = language, emotion = emotion, events = events)
  }

  private companion object {
    const val VAD_FILE_NAME = "fsmn-vad.gguf"
    val LANGUAGE_TAGS = setOf("zh", "en", "ja", "ko", "yue", "nospeech")
    val EMOTION_TAGS = setOf("HAPPY", "SAD", "ANGRY", "NEUTRAL")
    val EVENT_TAGS = setOf("BGM", "Speech", "Applause", "Laughter", "Cry", "Cough")
  }
}
