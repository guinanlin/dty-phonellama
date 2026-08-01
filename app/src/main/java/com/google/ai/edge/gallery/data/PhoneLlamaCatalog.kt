/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data

/**
 * PhoneLlama extended model catalog.
 *
 * These models are NOT in the upstream Edge Gallery allowlist but are verified to work with the
 * LiteRT-LM runtime. They are especially selected for tool-calling ability (useful with
 * OpenAI-compatible agent frameworks) and variety of sizes.
 *
 * Each entry follows the same [AllowedModel] schema as the upstream allowlist, so the existing
 * download / management infrastructure handles them transparently.
 */
object PhoneLlamaCatalog {

  /** Names of all models in this catalog, for section labeling in the UI. */
  val modelNames: Set<String> by lazy { EXTENDED_MODELS.map { it.name }.toSet() }

  val EXTENDED_MODELS: List<AllowedModel> = listOf(

    // -----------------------------------------------------------------------
    // SenseVoice (sherpa-onnx ONNX, CPU; QNN when a QNN pack is present)
    // Silero VAD is bundled in APK assets. Delete any old GGUF SenseVoice first.
    // -----------------------------------------------------------------------
    AllowedModel(
      name = "SenseVoice-Small",
      modelId = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09",
      modelFile = "model.int8.onnx",
      commitHash = "main",
      description = "SenseVoice int8 via sherpa-onnx for Chinese, English, Japanese, " +
        "Korean, Cantonese, with language/emotion/event labels (~237 MB ONNX + tokens). " +
        "Runs on CPU; QNN used automatically when a Qualcomm QNN model pack is installed. " +
        "Replaces the previous GGUF/llama.cpp path — re-download required.",
      sizeInBytes = 237_115_547L,
      minDeviceMemoryInGb = 4,
      defaultConfig = DefaultConfig(
        topK = 1,
        topP = 1.0f,
        temperature = 0.0f,
        accelerators = "cpu",
        visionAccelerator = null,
        maxContextLength = null,
        maxTokens = 0,
      ),
      taskTypes = listOf(BuiltInTaskId.LLM_ASK_AUDIO),
      bestForTaskTypes = listOf(BuiltInTaskId.LLM_ASK_AUDIO),
      llmSupportAudio = true,
      runtimeType = RuntimeType.SHERPA_ONNX_ASR,
      url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/" +
        "resolve/main/model.int8.onnx?download=true",
      extraDataFiles =
        listOf(
          ModelDataFile(
            name = "tokens",
            url =
              "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/" +
                "resolve/main/tokens.txt?download=true",
            downloadFileName = "tokens.txt",
            sizeInBytes = 315_894L,
          ),
        ),
    ),

    // -----------------------------------------------------------------------
    // Qwen3-0.6B  (Alibaba Qwen3)
    // Excellent speed for its size; Qwen3 supports both thinking and
    // non-thinking modes and has strong function/tool-calling capability.
    // ~585 MB — fits any modern phone. Also used as the Mobile Actions model.
    // -----------------------------------------------------------------------
    AllowedModel(
      name = "Qwen3-0.6B",
      modelId = "litert-community/Qwen3-0.6B",
      modelFile = "Qwen3-0.6B.litertlm",
      commitHash = "4c0f158768e8ed6b3cebc54e617732e9b1d819ae",
      description = "Qwen3 0.6B instruction-tuned model in LiteRT-LM format. " +
        "Supports thinking and non-thinking modes. " +
        "Strong tool-calling capability for its size (~585 MB).",
      sizeInBytes = 614_236_160L,
      minDeviceMemoryInGb = 6,
      defaultConfig = DefaultConfig(
        topK = 20,
        topP = 0.8f,
        temperature = 0.7f,
        accelerators = "gpu,cpu",
        visionAccelerator = null,
        maxContextLength = 4096,
        // maxTokens capped at 1024 (was 2048) to keep total token budget well within
        // the 4096 KV-cache allocation and prevent SIGSEGV in liblitertlm_jni.so.
        // Math: 5000 prompt chars ≈ 1429 tokens + 1024 output = 2453 << 4096.
        maxTokens = 1024,
      ),
      taskTypes = listOf(BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_PROMPT_LAB, BuiltInTaskId.LLM_MOBILE_ACTIONS),
      bestForTaskTypes = listOf(BuiltInTaskId.LLM_CHAT),
      llmSupportMobileActions = true,
    ),

    // -----------------------------------------------------------------------
    // Phi-4-Mini-Instruct  (Microsoft)
    // Microsoft's best small model — exceptional reasoning and tool-calling.
    // Q8 quantized, ~3.6 GB. Requires 12 GB+ device RAM.
    // -----------------------------------------------------------------------
    AllowedModel(
      name = "Phi-4-Mini-Instruct",
      modelId = "litert-community/Phi-4-mini-instruct",
      modelFile = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
      commitHash = "8cd368be75fdb94d5a6f6f5b40f1ab22a6c2543e",
      description = "Microsoft Phi-4 Mini Instruct in LiteRT-LM format (Q8, 4096 KV cache). " +
        "Best-in-class reasoning and function/tool-calling for a ~3.6 GB model. " +
        "Requires 12 GB+ device RAM.",
      sizeInBytes = 3_910_090_752L,
      minDeviceMemoryInGb = 12,
      defaultConfig = DefaultConfig(
        topK = 50,
        topP = 0.9f,
        temperature = 0.7f,
        accelerators = "gpu,cpu",
        visionAccelerator = null,
        maxContextLength = 4096,
        // maxTokens capped at 1024 (was 2048) — see Qwen3-0.6B entry for reasoning.
        maxTokens = 1024,
      ),
      taskTypes = listOf(BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_PROMPT_LAB),
      bestForTaskTypes = listOf(BuiltInTaskId.LLM_CHAT),
    ),

  )
  // NOTE: Qwen3-8B (7.7 GB, float32 KV cache) is excluded — causes OOM crash on Pixel Fold.
}
