/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.google.ai.edge.gallery.edgeserver.asr.volc

import android.util.Log
import com.google.ai.edge.gallery.edgeserver.EdgeServer
import com.google.ai.edge.gallery.runtime.asr.AsrEngine
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

/**
 * Volcengine wire-compatible streaming ASR WebSocket server (SenseVoice backend).
 */
class VolcAsrWebSocketServer(
  private val bindHost: String,
  private val bindPort: Int,
  private val engineSupplier: () -> AsrEngine?,
) : WebSocketServer(InetSocketAddress(bindHost, bindPort)) {

  private val sessions = ConcurrentHashMap<WebSocket, VolcAsrSession>()

  override fun onStart() {
    Log.i(
      TAG,
      "Volc ASR WebSocket listening on $bindHost:$bindPort " +
        "paths=${EdgeServer.VOLC_ASR_WS_PATH}|${EdgeServer.VOLC_ASR_WS_PATH_ALIAS}",
    )
  }

  override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
    val path = (handshake.resourceDescriptor ?: "").substringBefore('?')
    if (!isAcceptedPath(path)) {
      Log.w(TAG, "Rejecting WS path: $path (expect ${EdgeServer.VOLC_ASR_WS_PATH} or alias)")
      conn.close(1008, "Invalid path")
      return
    }
    val apiKey = handshake.getFieldValue("X-Api-Key")
    val resourceId = handshake.getFieldValue("X-Api-Resource-Id")
    val requestId = handshake.getFieldValue("X-Api-Request-Id")
    val sequence = handshake.getFieldValue("X-Api-Sequence")
    val remote = conn.remoteSocketAddress?.toString() ?: "?"
    val hasEngine = engineSupplier() != null
    val sessionId = requestId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    Log.i(
      TAG,
      "WS open remote=$remote path=$path request=$requestId seq=$sequence " +
        "resource=$resourceId key=${apiKey?.take(6)}… engineBound=$hasEngine session=$sessionId",
    )
    val session =
      VolcAsrSession(
        sessionId = sessionId,
        engineSupplier = engineSupplier,
        sendBinary = { bytes ->
          if (conn.isOpen) conn.send(bytes)
          else Log.w(TAG, "[$sessionId] send skipped: socket closed bytes=${bytes.size}")
        },
        closeConnection = {
          Log.i(TAG, "[$sessionId] closing socket reason=session done")
          if (conn.isOpen) conn.close(1000, "session done")
        },
      )
    sessions[conn] = session
  }

  override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
    val session = sessions.remove(conn)
    Log.i(
      TAG,
      "WS closed code=$code reason=$reason remote=$remote " +
        "session=${session?.let { "known" } ?: "none"} activeSessions=${sessions.size}",
    )
  }

  override fun onMessage(conn: WebSocket, message: String) {
    Log.w(TAG, "Ignoring text WS message len=${message.length} (binary protocol expected)")
  }

  override fun onMessage(conn: WebSocket, message: ByteBuffer) {
    val session = sessions[conn]
    if (session == null) {
      Log.w(TAG, "Binary message on unknown session remaining=${message.remaining()}")
      return
    }
    val bytes = ByteArray(message.remaining())
    message.get(bytes)
    val frame = VolcAsrProtocol.parseFrame(bytes)
    if (frame == null) {
      Log.e(TAG, "Parse failed bytes=${bytes.size} head=${bytes.take(8).joinToString(",")}")
      session.onInvalidFrame("Invalid binary frame (${bytes.size} bytes)")
      return
    }
    val eventName = eventName(frame.event)
    when (frame.event) {
      VolcAsrProtocol.START_CONNECTION -> {
        Log.i(TAG, "← event=1 START_CONNECTION payload=${frame.payload.size}")
        session.onStartConnection()
      }
      VolcAsrProtocol.START_SESSION -> {
        Log.i(
          TAG,
          "← event=100 START_SESSION sid=${frame.sessionId} payload=${frame.payload.size}",
        )
        session.onStartSession(frame.sessionId)
      }
      VolcAsrProtocol.TASK_REQUEST -> session.onTaskRequest(frame.payload)
      VolcAsrProtocol.FINISH_SESSION -> {
        Log.i(TAG, "← event=102 FINISH_SESSION sid=${frame.sessionId}")
        session.onFinishSession()
      }
      VolcAsrProtocol.CANCEL_SESSION -> {
        Log.i(TAG, "← event=101 CANCEL_SESSION sid=${frame.sessionId}")
        session.onCancelSession()
      }
      else -> {
        Log.w(
          TAG,
          "← unsupported event=${frame.event}($eventName) type=${frame.messageType} " +
            "sid=${frame.sessionId} payload=${frame.payload.size}",
        )
        session.onInvalidFrame("Unsupported event ${frame.event}")
      }
    }
  }

  override fun onError(conn: WebSocket?, ex: Exception) {
    Log.e(TAG, "WS error conn=${conn?.remoteSocketAddress}", ex)
  }

  companion object {
    private const val TAG = "VolcAsrWebSocket"

    fun isAcceptedPath(path: String): Boolean {
      val p = path.trim().ifEmpty { return false }
      return p == EdgeServer.VOLC_ASR_WS_PATH ||
        p.startsWith(EdgeServer.VOLC_ASR_WS_PATH + "/") ||
        p == EdgeServer.VOLC_ASR_WS_PATH_ALIAS ||
        p.startsWith(EdgeServer.VOLC_ASR_WS_PATH_ALIAS + "/")
    }

    private fun eventName(event: Int): String =
      when (event) {
        VolcAsrProtocol.START_CONNECTION -> "START_CONNECTION"
        VolcAsrProtocol.FINISH_CONNECTION -> "FINISH_CONNECTION"
        VolcAsrProtocol.START_SESSION -> "START_SESSION"
        VolcAsrProtocol.CANCEL_SESSION -> "CANCEL_SESSION"
        VolcAsrProtocol.FINISH_SESSION -> "FINISH_SESSION"
        VolcAsrProtocol.TASK_REQUEST -> "TASK_REQUEST"
        VolcAsrProtocol.CONNECTION_STARTED -> "CONNECTION_STARTED"
        VolcAsrProtocol.SESSION_STARTED -> "SESSION_STARTED"
        VolcAsrProtocol.SESSION_FINISHED -> "SESSION_FINISHED"
        VolcAsrProtocol.ASR_RESPONSE -> "ASR_RESPONSE"
        else -> "UNKNOWN"
      }
  }
}
