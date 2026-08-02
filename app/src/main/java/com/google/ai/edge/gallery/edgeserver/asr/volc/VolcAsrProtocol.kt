/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.ai.edge.gallery.edgeserver.asr.volc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import com.google.gson.JsonObject

/**
 * Volcengine bigmodel_async WebSocket binary protocol (wire-compatible subset).
 *
 * Frame layout (all multi-byte integers big-endian):
 *   byte0 = 0x11
 *   byte1 = (messageType << 4) | flags
 *   byte2 = (serialization << 4) | compression
 *   byte3 = 0x00
 *   BE32(event)
 *   [optional] BE32(session_id_len) + session_id UTF-8
 *   BE32(payload_len) + payload
 */
object VolcAsrProtocol {
  const val PROTOCOL_VERSION_HEADER: Int = 0x11
  const val CLIENT_FLAGS: Int = 0x04
  const val SERVER_EVENT_FLAGS: Int = 0x04

  const val MSG_FULL_JSON: Int = 0x01
  const val MSG_AUDIO_TASK: Int = 0x02
  const val MSG_SERVER_RESULT: Int = 0x09
  const val MSG_SERVER_EVENT: Int = 0x0B
  const val MSG_ERROR: Int = 0x0F

  const val SERIALIZATION_NONE: Int = 0x00
  const val SERIALIZATION_JSON: Int = 0x01

  // Client → server
  const val START_CONNECTION: Int = 1
  const val FINISH_CONNECTION: Int = 2
  const val START_SESSION: Int = 100
  const val CANCEL_SESSION: Int = 101
  const val FINISH_SESSION: Int = 102
  const val TASK_REQUEST: Int = 200

  // Server → client
  const val CONNECTION_STARTED: Int = 50
  const val CONNECTION_FAILED: Int = 51
  const val CONNECTION_FINISHED: Int = 52
  const val SESSION_STARTED: Int = 150
  const val SESSION_CANCELED: Int = 151
  const val SESSION_FINISHED: Int = 152
  const val ASR_INFO: Int = 450
  const val ASR_RESPONSE: Int = 451
  const val ASR_END: Int = 459

  const val ERROR_INVALID_PARAM: Int = 45000001
  const val ERROR_EMPTY_AUDIO: Int = 45000002
  const val ERROR_AUDIO_FORMAT: Int = 45000151
  const val ERROR_NO_ENGINE: Int = 45000152
  const val ERROR_INTERNAL: Int = 55000031

  data class ParsedFrame(
    val messageType: Int,
    val flags: Int,
    val serialization: Int,
    val event: Int,
    val sessionId: String?,
    val payload: ByteArray,
  )

  fun buildClientJsonFrame(
    event: Int,
    sessionId: String?,
    jsonPayload: String,
  ): ByteArray =
    buildFrame(
      messageType = MSG_FULL_JSON,
      flags = CLIENT_FLAGS,
      serialization = SERIALIZATION_JSON,
      event = event,
      sessionId = sessionId,
      payload = jsonPayload.toByteArray(StandardCharsets.UTF_8),
    )

  fun buildClientAudioFrame(
    event: Int,
    sessionId: String,
    audioBytes: ByteArray,
  ): ByteArray =
    buildFrame(
      messageType = MSG_AUDIO_TASK,
      flags = CLIENT_FLAGS,
      serialization = SERIALIZATION_NONE,
      event = event,
      sessionId = sessionId,
      payload = audioBytes,
    )

  fun buildServerEventFrame(
    event: Int,
    sessionId: String?,
    jsonPayload: String,
  ): ByteArray =
    buildFrame(
      messageType = MSG_SERVER_EVENT,
      flags = SERVER_EVENT_FLAGS,
      serialization = SERIALIZATION_JSON,
      event = event,
      sessionId = sessionId,
      payload = jsonPayload.toByteArray(StandardCharsets.UTF_8),
    )

  fun buildServerAsrResultFrame(
    event: Int,
    sessionId: String?,
    text: String,
  ): ByteArray {
    val json = buildAsrResultJson(text)
    return buildServerEventFrame(event, sessionId, json)
  }

  fun buildErrorFrame(errorCode: Int, message: String): ByteArray {
    val msgBytes = message.toByteArray(StandardCharsets.UTF_8)
    val buf = ByteBuffer.allocate(4 + 4 + 4 + msgBytes.size).order(ByteOrder.BIG_ENDIAN)
    buf.put(PROTOCOL_VERSION_HEADER.toByte())
    buf.put(((MSG_ERROR shl 4) or CLIENT_FLAGS).toByte())
    buf.put(((SERIALIZATION_JSON shl 4) or 0x00).toByte())
    buf.put(0x00)
    buf.putInt(errorCode)
    buf.putInt(msgBytes.size)
    buf.put(msgBytes)
    return buf.array()
  }

  fun buildAsrResultJson(text: String): String {
    val result = JsonObject()
    result.addProperty("text", text)
    val root = JsonObject()
    root.add("result", result)
    return root.toString()
  }

  fun buildConnectionLevelJson(resourceId: String = "volc.seedasr.sauc.duration"): String {
    val user = JsonObject()
    user.addProperty("uid", "phonellama-local")
    val audio = JsonObject()
    audio.addProperty("format", "ogg")
    audio.addProperty("codec", "opus")
    audio.addProperty("rate", 16000)
    audio.addProperty("bits", 16)
    audio.addProperty("channel", 1)
    val request = JsonObject()
    request.addProperty("model_name", "bigmodel")
    request.addProperty("enable_nonstream", true)
    request.addProperty("show_utterances", false)
    request.addProperty("result_type", "full")
    request.addProperty("enable_ddc", true)
    request.addProperty("resource_id", resourceId)
    val reqParams = JsonObject()
    reqParams.add("user", user)
    reqParams.add("audio", audio)
    reqParams.add("request", request)
    val root = JsonObject()
    root.addProperty("namespace", "BidirectionalASR")
    root.addProperty("event", 0)
    root.add("req_params", reqParams)
    return root.toString()
  }

  fun extractText(payload: ByteArray): String {
    val raw = String(payload, StandardCharsets.UTF_8).trim()
    if (raw.isEmpty()) return ""
    return try {
      val json = com.google.gson.JsonParser.parseString(raw).asJsonObject
      when {
        json.has("result") -> json.getAsJsonObject("result").get("text")?.asString ?: ""
        json.has("text") -> json.get("text").asString
        else -> raw
      }
    } catch (_: Exception) {
      raw
    }
  }

  fun parseFrame(bytes: ByteArray): ParsedFrame? {
    if (bytes.size < 12) return null
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val versionHeader = buf.get().toInt() and 0xFF
    if (versionHeader != PROTOCOL_VERSION_HEADER) return null
    val typeAndFlags = buf.get().toInt() and 0xFF
    val messageType = typeAndFlags shr 4
    val flags = typeAndFlags and 0x0F
    val serAndComp = buf.get().toInt() and 0xFF
    val serialization = serAndComp shr 4
    buf.get() // reserved
    if (messageType == MSG_ERROR) {
      if (bytes.size < 12) return null
      val errorCode = buf.int
      val msgLen = buf.int
      if (msgLen < 0 || buf.remaining() < msgLen) return null
      val msg = ByteArray(msgLen)
      buf.get(msg)
      return ParsedFrame(messageType, flags, serialization, errorCode, null, msg)
    }
    val event = buf.int
    var sessionId: String? = null
    if (needsSessionId(messageType, event) && buf.remaining() >= 4) {
      val idLen = buf.int
      if (idLen > 0 && buf.remaining() >= idLen) {
        val idBytes = ByteArray(idLen)
        buf.get(idBytes)
        sessionId = String(idBytes, StandardCharsets.UTF_8)
      }
    }
    if (buf.remaining() < 4) {
      return ParsedFrame(messageType, flags, serialization, event, sessionId, ByteArray(0))
    }
    val payloadLen = buf.int
    if (payloadLen < 0 || buf.remaining() < payloadLen) return null
    val payload = ByteArray(payloadLen)
    buf.get(payload)
    return ParsedFrame(messageType, flags, serialization, event, sessionId, payload)
  }

  private fun needsSessionId(messageType: Int, event: Int): Boolean {
    if (messageType == MSG_AUDIO_TASK) return true
    return event in setOf(
      START_SESSION,
      CANCEL_SESSION,
      FINISH_SESSION,
      TASK_REQUEST,
      SESSION_STARTED,
      SESSION_CANCELED,
      SESSION_FINISHED,
      ASR_INFO,
      ASR_RESPONSE,
      ASR_END,
    )
  }

  private fun buildFrame(
    messageType: Int,
    flags: Int,
    serialization: Int,
    event: Int,
    sessionId: String?,
    payload: ByteArray,
    includeEventBlock: Boolean = true,
  ): ByteArray {
    val sessionBytes = sessionId?.toByteArray(StandardCharsets.UTF_8)
    val sessionBlockSize = if (sessionBytes != null) 4 + sessionBytes.size else 0
    val size = 4 + (if (includeEventBlock) 4 else 0) + sessionBlockSize + 4 + payload.size
    val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
    buf.put(PROTOCOL_VERSION_HEADER.toByte())
    buf.put(((messageType shl 4) or (flags and 0x0F)).toByte())
    buf.put(((serialization shl 4) or 0x00).toByte())
    buf.put(0x00)
    if (includeEventBlock) {
      buf.putInt(event)
    }
    if (sessionBytes != null) {
      buf.putInt(sessionBytes.size)
      buf.put(sessionBytes)
    }
    buf.putInt(payload.size)
    buf.put(payload)
    return buf.array()
  }
}
