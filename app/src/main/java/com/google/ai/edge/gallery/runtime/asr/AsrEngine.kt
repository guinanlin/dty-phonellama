/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.ai.edge.gallery.runtime.asr

data class AsrResult(
  val text: String,
  val language: String? = null,
  val emotion: String? = null,
  val events: List<String> = emptyList(),
)

interface AsrEngine {
  fun transcribe(wavBytes: ByteArray): AsrResult

  /** Transcribe raw 16 kHz mono float PCM without WAV header wrapping. */
  fun transcribeSamples(samples: FloatArray): AsrResult = transcribe(wavBytesFromPcm(samples))

  fun close()

  companion object {
    fun wavBytesFromPcm(samples: FloatArray, sampleRate: Int = 16000): ByteArray {
      val dataSize = samples.size * 2
      val buf = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      buf.put("RIFF".toByteArray())
      buf.putInt(36 + dataSize)
      buf.put("WAVE".toByteArray())
      buf.put("fmt ".toByteArray())
      buf.putInt(16)
      buf.putShort(1)
      buf.putShort(1)
      buf.putInt(sampleRate)
      buf.putInt(sampleRate * 2)
      buf.putShort(2)
      buf.putShort(16)
      buf.put("data".toByteArray())
      buf.putInt(dataSize)
      for (s in samples) {
        val clamped = s.coerceIn(-1f, 1f)
        val v = if (clamped < 0) (clamped * 32768).toInt() else (clamped * 32767).toInt()
        buf.putShort(v.toShort())
      }
      return buf.array()
    }
  }
}
