/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.data.KEY_MODEL_COMMIT_HASH
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RATE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_URLS
import com.google.ai.edge.gallery.data.KEY_MODEL_IS_ZIP
import com.google.ai.edge.gallery.data.KEY_MODEL_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_START_UNZIPPING
import com.google.ai.edge.gallery.data.KEY_MODEL_TOTAL_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_UNZIPPED_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_URL
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

private const val TAG = "AGDownloadWorker"

data class UrlAndFileName(val url: String, val fileName: String)

private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel_foreground"
private var channelCreated = false

class DownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  private val externalFilesDir = context.getExternalFilesDir(null)

  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  // Unique notification id.
  private val notificationId: Int = params.id.hashCode()

  init {
    if (!channelCreated) {
      // Create a notification channel for showing notifications for model downloading progress.
      val channel =
        NotificationChannel(
            FOREGROUND_NOTIFICATION_CHANNEL_ID,
            "Model Downloading",
            // Make it silent.
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = "Notifications for model downloading" }
      notificationManager.createNotificationChannel(channel)
      channelCreated = true
    }
  }

  override suspend fun doWork(): Result {
    val fileUrl = inputData.getString(KEY_MODEL_URL)
    val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
    val version = inputData.getString(KEY_MODEL_COMMIT_HASH)!!
    val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
    val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR)!!
    val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
    val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
    val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
    val extraDataFileNames =
      inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
    val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)

    return withContext(Dispatchers.IO) {
      if (fileUrl == null || fileName == null) {
        Result.failure()
      } else {
        return@withContext try {
          // Set the worker as a foreground service immediately.
          setForeground(createForegroundInfo(progress = 0, modelName = modelName))

          // Collect data for all files.
          val allFiles: MutableList<UrlAndFileName> = mutableListOf()
          allFiles.add(UrlAndFileName(url = fileUrl, fileName = fileName))
          for (index in extraDataFileUrls.indices) {
            allFiles.add(
              UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index])
            )
          }
          Log.d(TAG, "About to download: $allFiles")

          if (
            isDownloadAlreadyComplete(
              modelDir = modelDir,
              version = version,
              mainFileName = fileName,
              isZip = isZip,
              unzippedDir = unzippedDir,
              extraFileNames = extraDataFileNames,
            )
          ) {
            Log.d(TAG, "All model files already on disk; skipping download")
            return@withContext Result.success()
          }

          // Download them in sequence.
          // TODO: maybe consider downloading them in parallel.
          var downloadedBytes = 0L
          val bytesReadSizeBuffer: MutableList<Long> = mutableListOf()
          val bytesReadLatencyBuffer: MutableList<Long> = mutableListOf()
          for (file in allFiles) {
            val url = URL(file.url)

            val connection = url.openConnection() as HttpURLConnection
            if (accessToken != null) {
              Log.d(TAG, "Using access token: ${accessToken.subSequence(0, 10)}...")
              connection.setRequestProperty("Authorization", "Bearer $accessToken")
            }

            // Prepare output file's dir.
            val outputDir =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version).joinToString(separator = File.separator),
              )
            if (!outputDir.exists()) {
              outputDir.mkdirs()
            }

            // Read the tmp file and see if it is partially downloaded.
            val outputTmpFile =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version, "${file.fileName}.$TMP_FILE_EXT")
                  .joinToString(separator = File.separator),
              )
            val outputFileBytes = outputTmpFile.length()
            if (outputFileBytes > 0) {
              Log.d(
                TAG,
                "File '${outputTmpFile.name}' partial size: ${outputFileBytes}. Trying to resume download",
              )
              connection.setRequestProperty("Range", "bytes=${outputFileBytes}-")
              // Force the server to send non-compressed data to make download resuming work.
              connection.setRequestProperty("Accept-Encoding", "identity")
            }
            connection.connect()
            Log.d(TAG, "response code: ${connection.responseCode}")

            if (
              connection.responseCode == HttpURLConnection.HTTP_OK ||
                connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            ) {
              val contentRange = connection.getHeaderField("Content-Range")

              if (contentRange != null) {
                // Parse the Content-Range header
                val rangeParts = contentRange.substringAfter("bytes ").split("/")
                val byteRange = rangeParts[0].split("-")
                val startByte = byteRange[0].toLong()
                val endByte = byteRange[1].toLong()

                Log.d(
                  TAG,
                  "Content-Range: $contentRange. Start bytes: ${startByte}, end bytes: $endByte",
                )

                downloadedBytes += startByte
              } else {
                Log.d(TAG, "Download starts from beginning.")
              }
            } else {
              throw IOException("HTTP error code: ${connection.responseCode}")
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputTmpFile, true /* append */)

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int
            var lastSetProgressTs: Long = 0
            var deltaBytes = 0L
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
              outputStream.write(buffer, 0, bytesRead)
              downloadedBytes += bytesRead
              deltaBytes += bytesRead

              // Report progress every 200 ms.
              val curTs = System.currentTimeMillis()
              if (curTs - lastSetProgressTs > 200) {
                // Calculate download rate.
                var bytesPerMs = 0f
                if (lastSetProgressTs != 0L) {
                  if (bytesReadSizeBuffer.size == 5) {
                    bytesReadSizeBuffer.removeAt(0)
                  }
                  bytesReadSizeBuffer.add(deltaBytes)
                  if (bytesReadLatencyBuffer.size == 5) {
                    bytesReadLatencyBuffer.removeAt(0)
                  }
                  bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)
                  deltaBytes = 0L
                  bytesPerMs = bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                }

                // Calculate remaining seconds
                var remainingMs = 0f
                if (bytesPerMs > 0f && totalBytes > 0L) {
                  remainingMs = (totalBytes - downloadedBytes) / bytesPerMs
                }

                setProgress(
                  Data.Builder()
                    .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                    .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                    .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                    .build()
                )
                setForeground(
                  createForegroundInfo(
                    progress = (downloadedBytes * 100 / totalBytes).toInt(),
                    modelName = modelName,
                  )
                )
                Log.d(TAG, "downloadedBytes: $downloadedBytes")
                lastSetProgressTs = curTs
              }
            }

            outputStream.close()
            inputStream.close()

            // Rename the tmp file to the original file name by removing the tmp file ext.
            val originalFilePath = outputTmpFile.absolutePath.replace(".$TMP_FILE_EXT", "")
            val originalFile = File(originalFilePath)
            if (originalFile.exists()) {
              originalFile.delete()
            }
            outputTmpFile.renameTo(originalFile)
            Log.d(TAG, "Download done")

            // Unpack the main archive once (not extra data files).
            if (isZip && unzippedDir != null && file.fileName == fileName) {
              setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

              val destParts = mutableListOf(modelDir, version)
              if (!unzippedDir.isNullOrEmpty()) {
                destParts.add(unzippedDir)
              }
              val destDir = File(externalFilesDir, destParts.joinToString(File.separator))
              if (!destDir.exists()) {
                destDir.mkdirs()
              }

              val archivePath =
                "${externalFilesDir}${File.separator}$modelDir${File.separator}$version${File.separator}${fileName}"
              extractArchive(File(archivePath), destDir)
              flattenSingleTopLevelDir(destDir)

              // Delete the original archive.
              File(archivePath).delete()
            }
          }
          Result.success()
        } catch (e: IOException) {
          Log.e(TAG, e.message, e)
          Result.failure(
            Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message).build()
          )
        }
      }
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    // Initial progress is 0
    return createForegroundInfo(0)
  }

  /**
   * Creates a [ForegroundInfo] object for the download worker's ongoing notification. This
   * notification is used to keep the worker running in the foreground, indicating to the user that
   * an active download is in progress.
   */
  private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
    // Create a notification for the foreground service
    var title = "Downloading model"
    if (modelName != null) {
      title = "Downloading \"$modelName\""
    }
    val content = "Downloading in progress: $progress%"

    val intent =
      Intent(applicationContext, Class.forName("com.google.ai.edge.gallery.MainActivity")).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        applicationContext,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification =
      NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true) // Makes the notification non-dismissable
        .setProgress(100, progress, false) // Show progress
        .setContentIntent(pendingIntent)
        .build()

    return ForegroundInfo(
      notificationId,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  private fun extractArchive(archive: File, destDir: File) {
    val name = archive.name.lowercase()
    when {
      name.endsWith(".zip") -> extractZip(archive, destDir)
      name.endsWith(".tar.bz2") || name.endsWith(".tbz2") ->
        extractTar(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))), destDir)
      name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
        extractTar(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive))), destDir)
      name.endsWith(".tar") ->
        extractTar(BufferedInputStream(FileInputStream(archive)), destDir)
      else ->
        throw IOException("Unsupported archive format: ${archive.name}")
    }
  }

  private fun extractZip(archive: File, destDir: File) {
    val buffer = ByteArray(8192)
    ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zipIn ->
      var entry: ZipEntry? = zipIn.nextEntry
      while (entry != null) {
        writeArchiveEntry(destDir, entry.name, entry.isDirectory, zipIn, buffer)
        zipIn.closeEntry()
        entry = zipIn.nextEntry
      }
    }
  }

  private fun extractTar(compressed: java.io.InputStream, destDir: File) {
    val buffer = ByteArray(8192)
    TarArchiveInputStream(compressed).use { tarIn ->
      var entry: TarArchiveEntry? = tarIn.nextEntry
      while (entry != null) {
        writeArchiveEntry(destDir, entry.name, entry.isDirectory, tarIn, buffer)
        entry = tarIn.nextEntry
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
    // Reject path traversal.
    val normalized = entryName.replace('\\', '/').trimStart('/')
    if (normalized.isEmpty() || normalized.contains("..")) {
      return
    }
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
    FileOutputStream(outFile).use { bos ->
      var len: Int
      while (input.read(buffer).also { len = it } > 0) {
        bos.write(buffer, 0, len)
      }
    }
  }

  /** If the archive unpacked a single top-level folder, hoist its contents up. */
  private fun isDownloadAlreadyComplete(
    modelDir: String,
    version: String,
    mainFileName: String,
    isZip: Boolean,
    unzippedDir: String?,
    extraFileNames: List<String>,
  ): Boolean {
    val baseDir = File(externalFilesDir, listOf(modelDir, version).joinToString(File.separator))
    val mainFile = File(baseDir, mainFileName)
    val unzipReady =
      isZip &&
        !unzippedDir.isNullOrEmpty() &&
        File(baseDir, unzippedDir).isDirectory &&
        File(baseDir, unzippedDir).walkTopDown().any { it.isFile }
    val primaryReady = mainFile.isFile || unzipReady
    if (!primaryReady) {
      return false
    }
    for (extra in extraFileNames) {
      if (extra.isEmpty()) continue
      if (!File(baseDir, extra).isFile) {
        return false
      }
    }
    return true
  }

  private fun flattenSingleTopLevelDir(destDir: File) {
    val children = destDir.listFiles()?.filter { it.name != "." && it.name != ".." } ?: return
    if (children.size != 1 || !children[0].isDirectory) return
    val nested = children[0]
    nested.listFiles()?.forEach { child ->
      val target = File(destDir, child.name)
      if (!child.renameTo(target)) {
        child.copyRecursively(target, overwrite = true)
        child.deleteRecursively()
      }
    }
    nested.delete()
  }
}
