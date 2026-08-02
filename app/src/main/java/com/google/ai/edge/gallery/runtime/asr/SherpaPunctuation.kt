/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.ai.edge.gallery.runtime.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Lazy-loaded sherpa-onnx OfflinePunctuation (ct-transformer zh-en int8).
 *
 * SenseVoice itself does not emit punctuation; this post-processes ASR text.
 * Model is auto-downloaded on first use into app external files (not bundled in APK).
 */
object SherpaPunctuation {
  private const val TAG = "SherpaPunct"
  private const val MODEL_DIR =
    "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8"
  private const val MODEL_FILE = "model.int8.onnx"
  private const val ARCHIVE_NAME =
    "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2"
  private const val ARCHIVE_URL =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/$ARCHIVE_NAME"

  private val lock = Any()
  private val downloadStarted = AtomicBoolean(false)
  private val executor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "sherpa-punct-download").apply { isDaemon = true }
  }

  @Volatile private var punct: OfflinePunctuation? = null
  @Volatile private var ready = false
  @Volatile private var lastError: String? = null

  fun isReady(): Boolean = ready

  fun lastError(): String? = lastError

  /** Kick off download/load if needed; never blocks the caller. */
  fun ensureAsync(context: Context) {
    if (ready && punct != null) return
    val app = context.applicationContext
    if (!downloadStarted.compareAndSet(false, true)) return
    executor.execute {
      try {
        ensureBlocking(app)
      } catch (e: Throwable) {
        lastError = e.message
        Log.e(TAG, "Failed to prepare punctuation model", e)
        downloadStarted.set(false)
      }
    }
  }

  /** Apply punctuation when the model is ready; otherwise return [text] unchanged. */
  fun addPunctuation(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return text
    val engine = punct ?: return text
    return try {
      val out = engine.addPunctuation(trimmed)
      if (out.isBlank()) text else out
    } catch (e: Throwable) {
      Log.w(TAG, "addPunctuation failed, returning raw text", e)
      text
    }
  }

  fun release() {
    synchronized(lock) {
      try {
        punct?.release()
      } catch (_: Throwable) {
      }
      punct = null
      ready = false
    }
  }

  private fun ensureBlocking(context: Context) {
    synchronized(lock) {
      if (punct != null) {
        ready = true
        return
      }
      val modelFile = resolveOrDownloadModel(context)
      Log.i(TAG, "Loading OfflinePunctuation from ${modelFile.absolutePath}")
      val config =
        OfflinePunctuationConfig(
          model =
            OfflinePunctuationModelConfig(
              ctTransformer = modelFile.absolutePath,
              numThreads = 1,
              debug = false,
              provider = "cpu",
            ),
        )
      val engine = OfflinePunctuation(assetManager = null, config = config)
      if (engine.addPunctuation("你好世界").isBlank()) {
        engine.release()
        error("OfflinePunctuation smoke test returned empty text")
      }
      punct = engine
      ready = true
      lastError = null
      Log.i(TAG, "OfflinePunctuation ready")
    }
  }

  private fun resolveOrDownloadModel(context: Context): File {
    findModelFile(context)?.let { return it }

    val root =
      File(context.getExternalFilesDir(null), "sherpa-punct").also { it.mkdirs() }
    val archive = File(root, ARCHIVE_NAME)
    Log.i(TAG, "Downloading punctuation model → ${archive.absolutePath}")
    downloadFile(ARCHIVE_URL, archive)
    extractTarBz2(archive, root)
    archive.delete()

    return findModelFile(context)
      ?: error("Punctuation model not found after extract under ${root.absolutePath}")
  }

  private fun findModelFile(context: Context): File? {
    val roots =
      listOfNotNull(
        context.getExternalFilesDir(null)?.let { File(it, "sherpa-punct") },
        context.getExternalFilesDir(null),
        context.filesDir,
      )
    for (root in roots) {
      val candidates =
        listOf(
          File(root, "$MODEL_DIR/$MODEL_FILE"),
          File(root, MODEL_FILE),
          File(root, "model.onnx"),
        )
      candidates.firstOrNull { it.isFile && it.length() > 1_000_000 }?.let { return it }
      // Walk one level for manually pushed layouts.
      root.listFiles()?.forEach { child ->
        if (!child.isDirectory) return@forEach
        val onnx = File(child, MODEL_FILE)
        if (onnx.isFile && onnx.length() > 1_000_000) return onnx
        val fp = File(child, "model.onnx")
        if (fp.isFile && fp.length() > 1_000_000) return fp
      }
    }
    return null
  }

  private fun downloadFile(url: String, dest: File) {
    dest.parentFile?.mkdirs()
    val tmp = File(dest.absolutePath + ".tmp")
    if (tmp.exists()) tmp.delete()
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 30_000
      readTimeout = 120_000
      instanceFollowRedirects = true
      requestMethod = "GET"
    }
    try {
      val code = conn.responseCode
      if (code !in 200..299) {
        throw IOException("HTTP $code downloading $url")
      }
      conn.inputStream.use { input ->
        FileOutputStream(tmp).use { output ->
          val buf = ByteArray(64 * 1024)
          var n: Int
          var total = 0L
          var lastLog = 0L
          while (input.read(buf).also { n = it } >= 0) {
            output.write(buf, 0, n)
            total += n
            if (total - lastLog >= 8L * 1024 * 1024) {
              Log.i(TAG, "download progress ${total / (1024 * 1024)} MiB")
              lastLog = total
            }
          }
        }
      }
      if (tmp.length() < 1_000_000) {
        tmp.delete()
        throw IOException("Downloaded archive too small: ${tmp.length()} bytes")
      }
      if (dest.exists()) dest.delete()
      if (!tmp.renameTo(dest)) {
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
      }
      Log.i(TAG, "Downloaded ${dest.length()} bytes → ${dest.name}")
    } finally {
      conn.disconnect()
    }
  }

  private fun extractTarBz2(archive: File, destDir: File) {
    destDir.mkdirs()
    val buffer = ByteArray(8192)
    BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bz ->
      TarArchiveInputStream(bz).use { tarIn ->
        var entry: TarArchiveEntry? = tarIn.nextEntry
        while (entry != null) {
          writeArchiveEntry(destDir, entry.name, entry.isDirectory, tarIn, buffer)
          entry = tarIn.nextEntry
        }
      }
    }
  }

  private fun writeArchiveEntry(
    destDir: File,
    entryName: String,
    isDirectory: Boolean,
    input: java.io.InputStream,
    buffer: ByteArray,
  ) {
    val normalized = entryName.replace('\\', '/').trimStart('/')
    if (normalized.isEmpty() || normalized.contains("..")) return
    val outFile = File(destDir, normalized)
    val destCanonical = destDir.canonicalFile
    val outCanonical = outFile.canonicalFile
    if (!outCanonical.path.startsWith(destCanonical.path + File.separator) &&
      outCanonical != destCanonical
    ) {
      throw IOException("Illegal archive path: $entryName")
    }
    if (isDirectory) {
      outFile.mkdirs()
      return
    }
    outFile.parentFile?.mkdirs()
    FileOutputStream(outFile).use { out ->
      var n: Int
      while (input.read(buffer).also { n = it } >= 0) {
        out.write(buffer, 0, n)
      }
    }
  }
}
