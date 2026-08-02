/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.ai.edge.gallery.data

/**
 * China-friendly Hugging Face download host helpers.
 *
 * Strategy:
 * - Ungated models: download via [MIRROR_HOST] (faster in CN, no OAuth).
 * - Gated models (e.g. Gemma): authenticate against [OFFICIAL_HOST], then download from the
 *   official host with a Bearer token. hf-mirror.com 308-redirects gated Google repos back to
 *   huggingface.co and drops Authorization on cross-host redirects, which surfaces as HTTP 401.
 *
 * OAuth / license pages always stay on the official host.
 */
object HfMirror {
  const val OFFICIAL_HOST = "https://huggingface.co"
  const val MIRROR_HOST = "https://hf-mirror.com"

  fun downloadUrl(modelId: String, commitHash: String, modelFile: String): String =
    toMirrorUrl("$OFFICIAL_HOST/$modelId/resolve/$commitHash/$modelFile?download=true")

  fun isHuggingFaceFamily(url: String): Boolean =
    url.startsWith(OFFICIAL_HOST) ||
      url.startsWith(MIRROR_HOST) ||
      url.startsWith("http://huggingface.co") ||
      url.startsWith("http://hf-mirror.com")

  fun toMirrorUrl(url: String): String {
    if (url.isBlank()) return url
    val official = toOfficialUrl(url)
    return if (official.startsWith(OFFICIAL_HOST)) {
      MIRROR_HOST + official.removePrefix(OFFICIAL_HOST)
    } else {
      url
    }
  }

  fun toOfficialUrl(url: String): String {
    if (url.isBlank()) return url
    return when {
      url.startsWith(OFFICIAL_HOST) -> url
      url.startsWith(MIRROR_HOST) -> OFFICIAL_HOST + url.removePrefix(MIRROR_HOST)
      url.startsWith("http://hf-mirror.com") ->
        OFFICIAL_HOST + url.removePrefix("http://hf-mirror.com")
      url.startsWith("http://huggingface.co") ->
        OFFICIAL_HOST + url.removePrefix("http://huggingface.co")
      else -> url
    }
  }
}
