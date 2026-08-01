/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.ai.edge.gallery.runtime.asr

internal object SenseVoiceNative {
  init {
    System.loadLibrary("phonellama_asr")
  }

  external fun nativeTranscribe(
    modelPath: String,
    vadPath: String,
    audioPath: String,
    accelerator: String,
  ): String?

  external fun nativeHasGpuBackend(): Boolean
}
