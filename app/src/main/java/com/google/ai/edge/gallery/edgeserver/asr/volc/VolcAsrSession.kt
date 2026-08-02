/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.ai.edge.gallery.edgeserver.asr.volc

import android.util.Log
import com.google.ai.edge.gallery.runtime.asr.AsrEngine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Volcengine-compatible ASR session: Ogg Opus in → simulated streaming SenseVoice → 451/152 out.
 */
class VolcAsrSession(
  private var sessionId: String,
  private val engineSupplier: () -> AsrEngine?,
  private val sendBinary: (ByteArray) -> Unit,
  private val closeConnection: () -> Unit,
) {
  enum class State {
    AWAITING_CONNECTION,
    CONNECTION_STARTED,
    SESSION_STARTED,
    SESSION_FINISHED,
    CANCELLED,
  }

  @Volatile var state: State = State.AWAITING_CONNECTION
    private set

  private val lock = Any()
  private val oggDecoder = OggOpusDecoder()
  private val pendingOgg = ArrayBlockingQueue<ByteArray>(256)
  private val pcmBuffer = FloatArrayList()
  private var accumulatedText = ""
  private var silenceSamples = 0
  private var speechSamples = 0
  private var lastPartialDecodeMs = 0L
  private var sessionReady = false
  private var taskRequestCount = 0
  private var totalOggBytes = 0L
  private var totalPcmSamples = 0L
  private var decodeCount = 0
  private var emittedPartialCount = 0
  private val createdAtMs = System.currentTimeMillis()

  fun onStartConnection() {
    synchronized(lock) {
      if (state != State.AWAITING_CONNECTION) {
        Log.w(TAG, "[$sessionId] StartConnection ignored state=$state")
        return
      }
      state = State.CONNECTION_STARTED
      val json = VolcAsrProtocol.buildConnectionLevelJson()
      sendBinary(
        VolcAsrProtocol.buildServerEventFrame(
          VolcAsrProtocol.CONNECTION_STARTED,
          sessionId = null,
          jsonPayload = json,
        ),
      )
      Log.i(TAG, "[$sessionId] → event=50 CONNECTION_STARTED")
    }
  }

  fun onStartSession(clientSessionId: String?) {
    synchronized(lock) {
      if (!clientSessionId.isNullOrBlank()) {
        sessionId = clientSessionId
      }
      if (state != State.CONNECTION_STARTED) {
        Log.e(TAG, "[$sessionId] StartSession rejected state=$state")
        sendError(VolcAsrProtocol.ERROR_INVALID_PARAM, "StartSession before ConnectionStarted")
        return
      }
      val engine = engineSupplier()
      if (engine == null) {
        Log.e(TAG, "[$sessionId] StartSession rejected: no ASR engine bound")
        sendError(
          VolcAsrProtocol.ERROR_NO_ENGINE,
          "No SenseVoice ASR model loaded. Activate SenseVoice-Small first.",
        )
        return
      }
      state = State.SESSION_STARTED
      sessionReady = true
      val json = VolcAsrProtocol.buildConnectionLevelJson()
      sendBinary(
        VolcAsrProtocol.buildServerEventFrame(
          VolcAsrProtocol.SESSION_STARTED,
          sessionId = sessionId,
          jsonPayload = json,
        ),
      )
      val pending = pendingOgg.size
      Log.i(
        TAG,
        "[$sessionId] → event=150 SESSION_STARTED engine=${engine.javaClass.simpleName} pendingOgg=$pending",
      )
      flushPendingOgg()
    }
  }

  fun onTaskRequest(oggBytes: ByteArray) {
    synchronized(lock) {
      if (state == State.CANCELLED || state == State.SESSION_FINISHED) {
        Log.w(TAG, "[$sessionId] TaskRequest ignored state=$state bytes=${oggBytes.size}")
        return
      }
      taskRequestCount++
      totalOggBytes += oggBytes.size
      if (!sessionReady) {
        val ok = pendingOgg.offer(oggBytes)
        if (taskRequestCount <= 3 || taskRequestCount % 20 == 0) {
          Log.i(
            TAG,
            "[$sessionId] TaskRequest#$taskRequestCount queued ready=false bytes=${oggBytes.size} " +
              "pending=${pendingOgg.size} accepted=$ok",
          )
        }
        return
      }
      if (taskRequestCount <= 3 || taskRequestCount % 20 == 0) {
        Log.d(
          TAG,
          "[$sessionId] TaskRequest#$taskRequestCount bytes=${oggBytes.size} " +
            "totalOgg=$totalOggBytes pcmBuf=${pcmBuffer.size} (~${"%.2f".format(pcmBuffer.size / SAMPLE_RATE.toFloat())}s)",
        )
      }
      processOgg(oggBytes)
    }
  }

  fun onFinishSession() {
    synchronized(lock) {
      if (state == State.CANCELLED || state == State.SESSION_FINISHED) {
        Log.w(TAG, "[$sessionId] FinishSession ignored state=$state")
        return
      }
      val elapsed = System.currentTimeMillis() - createdAtMs
      Log.i(
        TAG,
        "[$sessionId] FinishSession begin tasks=$taskRequestCount oggBytes=$totalOggBytes " +
          "pcmSamples=$totalPcmSamples (~${"%.2f".format(totalPcmSamples / SAMPLE_RATE.toFloat())}s) " +
          "pending=${pendingOgg.size} pcmBuf=${pcmBuffer.size} elapsedMs=$elapsed " +
          "ogg=${oggDecoder.stats()}",
      )
      flushPendingOgg()
      val tail = oggDecoder.flush()
      if (tail.isNotEmpty()) {
        Log.d(TAG, "[$sessionId] ogg flush pcm=${tail.size}")
        appendPcm(tail)
      }
      decodeAndEmit(force = true, finalFlush = true)
      flushRemainingPcm(final = true)
      state = State.SESSION_FINISHED
      val text = accumulatedText.trim()
      sendBinary(
        VolcAsrProtocol.buildServerAsrResultFrame(
          VolcAsrProtocol.SESSION_FINISHED,
          sessionId = sessionId,
          text = text,
        ),
      )
      Log.i(
        TAG,
        "[$sessionId] → event=152 SESSION_FINISHED textLen=${text.length} " +
          "decodes=$decodeCount partials=$emittedPartialCount text='${text.take(120)}'",
      )
      oggDecoder.reset()
      closeConnection()
    }
  }

  fun onCancelSession() {
    synchronized(lock) {
      Log.i(TAG, "[$sessionId] CancelSession tasks=$taskRequestCount")
      state = State.CANCELLED
      pendingOgg.clear()
      pcmBuffer.clear()
      oggDecoder.reset()
      val json = VolcAsrProtocol.buildConnectionLevelJson()
      sendBinary(
        VolcAsrProtocol.buildServerEventFrame(
          VolcAsrProtocol.SESSION_CANCELED,
          sessionId = sessionId,
          jsonPayload = json,
        ),
      )
      Log.i(TAG, "[$sessionId] → event=151 SESSION_CANCELED")
      closeConnection()
    }
  }

  fun onInvalidFrame(message: String) {
    Log.e(TAG, "[$sessionId] invalid frame: $message")
    sendError(VolcAsrProtocol.ERROR_INVALID_PARAM, message)
  }

  private fun flushPendingOgg() {
    var n = 0
    while (true) {
      val chunk = pendingOgg.poll() ?: break
      n++
      processOgg(chunk)
    }
    if (n > 0) Log.i(TAG, "[$sessionId] flushed pendingOgg count=$n")
  }

  private fun processOgg(oggBytes: ByteArray) {
    val pcm = oggDecoder.feed(oggBytes)
    if (pcm.isNotEmpty()) {
      totalPcmSamples += pcm.size
      appendPcm(pcm)
    }
  }

  private fun appendPcm(pcm: FloatArray) {
    for (sample in pcm) {
      pcmBuffer.add(sample)
      val amp = kotlin.math.abs(sample)
      if (amp >= SILENCE_THRESHOLD) {
        silenceSamples = 0
        speechSamples++
      } else {
        silenceSamples++
      }
      if (speechSamples > 0 && silenceSamples >= SILENCE_SAMPLES) {
        Log.d(
          TAG,
          "[$sessionId] silence cut pcmBuf=${pcmBuffer.size} " +
            "(~${"%.2f".format(pcmBuffer.size / SAMPLE_RATE.toFloat())}s)",
        )
        decodeAndEmit(force = true, finalFlush = false)
        silenceSamples = 0
        speechSamples = 0
      } else if (pcmBuffer.size >= maxSegmentSamples) {
        Log.d(TAG, "[$sessionId] max-segment cut pcmBuf=${pcmBuffer.size}")
        decodeAndEmit(force = true, finalFlush = false)
      } else {
        val now = System.currentTimeMillis()
        if (speechSamples >= PARTIAL_MIN_SAMPLES &&
          now - lastPartialDecodeMs >= PARTIAL_INTERVAL_MS
        ) {
          decodeAndEmit(force = false, finalFlush = false)
          lastPartialDecodeMs = now
        }
      }
    }
  }

  private fun flushRemainingPcm(final: Boolean) {
    if (pcmBuffer.isEmpty()) return
    decodeAndEmit(force = true, finalFlush = final)
  }

  private fun decodeAndEmit(force: Boolean, finalFlush: Boolean) {
    if (pcmBuffer.isEmpty()) return
    if (!force && pcmBuffer.size < MIN_DECODE_SAMPLES) return
    val engine = engineSupplier()
    if (engine == null) {
      Log.e(TAG, "[$sessionId] decode skipped: engine gone")
      return
    }
    val segment = pcmBuffer.toFloatArray()
    pcmBuffer.clear()
    if (!finalFlush) {
      silenceSamples = 0
      speechSamples = 0
    }
    val sec = segment.size / SAMPLE_RATE.toFloat()
    val t0 = System.currentTimeMillis()
    decodeCount++
    try {
      val result = engine.transcribeSamples(segment)
      val dt = System.currentTimeMillis() - t0
      val piece = result.text.trim()
      Log.i(
        TAG,
        "[$sessionId] decode#$decodeCount finalFlush=$finalFlush samples=${segment.size} " +
          "(${"%.2f".format(sec)}s) took=${dt}ms textLen=${piece.length} " +
          "lang=${result.language} text='${piece.take(80)}'",
      )
      if (piece.isNotBlank()) {
        accumulatedText = appendTranscript(accumulatedText, piece)
        emittedPartialCount++
        sendBinary(
          VolcAsrProtocol.buildServerAsrResultFrame(
            VolcAsrProtocol.ASR_RESPONSE,
            sessionId = sessionId,
            text = accumulatedText.trim(),
          ),
        )
        Log.i(
          TAG,
          "[$sessionId] → event=451 ASR_RESPONSE #$emittedPartialCount " +
            "accLen=${accumulatedText.length} acc='${accumulatedText.take(100)}'",
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "[$sessionId] decode#$decodeCount failed samples=${segment.size}", e)
      if (finalFlush) {
        sendError(VolcAsrProtocol.ERROR_INTERNAL, "ASR decode failed: ${e.message}")
      }
    }
  }

  private fun sendError(code: Int, message: String) {
    Log.e(TAG, "[$sessionId] → errorFrame code=$code msg=$message")
    sendBinary(VolcAsrProtocol.buildErrorFrame(code, message))
  }

  private val maxSegmentSamples: Int
    get() = SAMPLE_RATE * MAX_SEGMENT_SECONDS

  private class FloatArrayList {
    private var data = FloatArray(4096)
    var size = 0
      private set

    fun add(v: Float) {
      if (size >= data.size) {
        data = data.copyOf(data.size * 2)
      }
      data[size++] = v
    }

    fun clear() {
      size = 0
    }

    fun isEmpty(): Boolean = size == 0

    fun toFloatArray(): FloatArray = data.copyOf(size)
  }

  companion object {
    private const val TAG = "VolcAsrSession"
    private const val SAMPLE_RATE = OggOpusDecoder.SAMPLE_RATE
    private const val SILENCE_THRESHOLD = 0.01f
    private const val SILENCE_MS = 800
    private const val SILENCE_SAMPLES = SAMPLE_RATE * SILENCE_MS / 1000
    private const val MAX_SEGMENT_SECONDS = 25
    private const val MIN_DECODE_SAMPLES = SAMPLE_RATE / 5
    private const val PARTIAL_MIN_SAMPLES = SAMPLE_RATE * 2
    private const val PARTIAL_INTERVAL_MS = 1500L

    private val connSeq = AtomicInteger(0)

    fun nextTraceId(): String = "c${connSeq.incrementAndGet()}"

    /** Join segments without inserting spaces between CJK characters. */
    fun appendTranscript(acc: String, piece: String): String {
      val a = acc.trim()
      val b = piece.trim()
      if (a.isEmpty()) return b
      if (b.isEmpty()) return a
      val left = a.last()
      val right = b.first()
      val needSpace = !isCjk(left) && !isCjk(right) && !left.isWhitespace() && !right.isWhitespace()
      return if (needSpace) "$a $b" else "$a$b"
    }

    private fun isCjk(c: Char): Boolean {
      val block = Character.UnicodeBlock.of(c)
      return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
        block == Character.UnicodeBlock.GENERAL_PUNCTUATION
    }
  }
}
