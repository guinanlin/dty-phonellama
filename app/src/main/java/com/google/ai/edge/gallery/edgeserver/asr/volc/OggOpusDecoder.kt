/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.ai.edge.gallery.edgeserver.asr.volc

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusException

/**
 * Incremental Ogg demuxer + Opus decoder for 16 kHz mono PCM (float samples).
 *
 * Accepts raw Ogg bytes from Volcengine TaskRequest frames (VoiceStick OggOpusMuxer).
 */
class OggOpusDecoder(
  private val sampleRate: Int = SAMPLE_RATE,
) {
  private val oggBuffer = ByteArrayOutputStream()
  private val packetScratch = ByteArrayOutputStream()
  private var opusDecoder: OpusDecoder? = null
  private var headerParsed = false
  private var serialNumber: Int? = null
  private var pagesDecoded = 0
  private var packetsDecoded = 0
  private var opusErrors = 0

  fun reset() {
    oggBuffer.reset()
    packetScratch.reset()
    opusDecoder = null
    headerParsed = false
    serialNumber = null
    pagesDecoded = 0
    packetsDecoded = 0
    opusErrors = 0
  }

  fun stats(): String =
    "pages=$pagesDecoded packets=$packetsDecoded opusErrors=$opusErrors header=$headerParsed"

  /**
   * Feed Ogg bytes and return newly decoded PCM float samples (may be empty).
   */
  @Synchronized
  fun feed(oggBytes: ByteArray): FloatArray {
    if (oggBytes.isEmpty()) return FloatArray(0)
    oggBuffer.write(oggBytes)
    val pcmChunks = ArrayList<Short>()
    parseOggPages(pcmChunks)
    return shortsToFloat(pcmChunks.toShortArray())
  }

  @Synchronized
  fun flush(): FloatArray {
    val pcmChunks = ArrayList<Short>()
    parseOggPages(pcmChunks, flush = true)
    return shortsToFloat(pcmChunks.toShortArray())
  }

  private fun parseOggPages(outPcm: MutableList<Short>, flush: Boolean = false) {
    val data = oggBuffer.toByteArray()
    var offset = 0
    var consumed = 0
    while (offset + 27 <= data.size) {
      if (data[offset].toInt() != 'O'.code ||
        data[offset + 1].toInt() != 'g'.code ||
        data[offset + 2].toInt() != 'g'.code ||
        data[offset + 3].toInt() != 'S'.code
      ) {
        offset++
        continue
      }
      val pageSegments = data[offset + 26].toInt() and 0xFF
      val headerSize = 27 + pageSegments
      if (offset + headerSize > data.size) break
      var bodySize = 0
      for (i in 0 until pageSegments) {
        bodySize += data[offset + 27 + i].toInt() and 0xFF
      }
      val pageSize = headerSize + bodySize
      if (offset + pageSize > data.size) break

      val pageSerial =
        ByteBuffer.wrap(data, offset + 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
      if (serialNumber == null) serialNumber = pageSerial

      val segmentTableOffset = offset + 27
      var bodyOffset = offset + headerSize
      var segIdx = 0
      packetScratch.reset()
      while (segIdx < pageSegments) {
        val segLen = data[segmentTableOffset + segIdx].toInt() and 0xFF
        if (bodyOffset + segLen > data.size) break
        if (segLen > 0) {
          packetScratch.write(data, bodyOffset, segLen)
        }
        bodyOffset += segLen
        segIdx++
        val continues = segLen == 255
        if (!continues && packetScratch.size() > 0) {
          decodeOpusPacket(packetScratch.toByteArray(), outPcm)
          packetScratch.reset()
        }
      }
      pagesDecoded++
      offset += pageSize
      consumed = offset
    }
    if (consumed > 0) {
      val remaining = data.copyOfRange(consumed, data.size)
      oggBuffer.reset()
      oggBuffer.write(remaining)
    } else if (flush) {
      oggBuffer.reset()
    }
  }

  private fun decodeOpusPacket(packet: ByteArray, outPcm: MutableList<Short>) {
    if (packet.isEmpty()) return
    // Always skip Ogg Opus identification / comment packets. After OpusHead we used to
    // set headerParsed=true and then try to decode OpusTags as audio → "corrupted stream".
    val sigLen = minOf(8, packet.size)
    if (sigLen >= 8) {
      val sig = String(packet, 0, sigLen, Charsets.US_ASCII)
      if (sig.startsWith("OpusHead")) {
        ensureDecoder()
        headerParsed = true
        Log.i(TAG, "skip OpusHead packet bytes=${packet.size}")
        return
      }
      if (sig.startsWith("OpusTags")) {
        headerParsed = true
        Log.i(TAG, "skip OpusTags packet bytes=${packet.size}")
        return
      }
    }
    val decoder = ensureDecoder() ?: return
    val maxFrame = 5760
    val pcm = ShortArray(maxFrame)
    try {
      val decoded =
        decoder.decode(packet, 0, packet.size, pcm, 0, maxFrame, false)
      if (decoded > 0) {
        packetsDecoded++
        for (i in 0 until decoded) {
          outPcm.add(pcm[i])
        }
      }
    } catch (e: OpusException) {
      opusErrors++
      if (opusErrors <= 3 || opusErrors % 50 == 0) {
        Log.w(TAG, "Opus decode error #$opusErrors: ${e.message}")
      }
    }
  }

  private fun ensureDecoder(): OpusDecoder? {
    if (opusDecoder != null) return opusDecoder
    return try {
      OpusDecoder(sampleRate, 1).also {
        opusDecoder = it
        headerParsed = true
      }
    } catch (_: OpusException) {
      null
    }
  }

  private fun shortsToFloat(pcm: ShortArray): FloatArray {
    val out = FloatArray(pcm.size)
    for (i in pcm.indices) {
      out[i] = pcm[i] / 32768.0f
    }
    return out
  }

  companion object {
    private const val TAG = "OggOpusDecoder"
    const val SAMPLE_RATE = 16000
  }
}
