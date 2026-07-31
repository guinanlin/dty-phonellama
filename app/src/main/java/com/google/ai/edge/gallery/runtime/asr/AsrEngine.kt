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

  fun close()
}
