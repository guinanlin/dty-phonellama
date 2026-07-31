/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.edgeserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.asr.AsrEngine
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "EdgeServerManager"

data class EdgeServerState(
  val isRunning: Boolean = false,
  val port: Int = EdgeServer.DEFAULT_PORT,
  val lanMode: Boolean = true,
  val activeModelName: String = "",
  val requestCount: Int = 0,
  val lastError: String? = null,
  val lanIp: String = ""
)

/**
 * Singleton manager for the EdgeServer lifecycle, state, and model binding.
 * Model bindings are persisted so that starting the server after model load works correctly.
 */
object EdgeServerManager {
  private var server: EdgeServer? = null

  /** Public read-only accessor for stats overlays (e.g. tokens/sec in DeviceStatsBar). */
  val currentServer: EdgeServer? get() = server

  // Persisted binding — survives server start/stop cycles
  private var boundModel: Model? = null
  private var boundHelper: LlmModelHelper? = null
  private var boundAsrEngine: AsrEngine? = null
  private var boundDisplayName: String = ""
  private var _knownModelNames: List<String> = emptyList()

  private val _state = MutableStateFlow(EdgeServerState())
  val state: StateFlow<EdgeServerState> = _state.asStateFlow()

  /**
   * Registered by MainActivity after the ViewModel is ready.
   * Called by the API server when POST /v1/models/{id}/load is received.
   * Implementations should call ModelManagerViewModel.initializeModel on the UI scope.
   */
  var loadModelCallback: ((modelId: String, onResult: (success: Boolean, message: String) -> Unit) -> Unit)? = null

  fun start(context: Context, port: Int = EdgeServer.DEFAULT_PORT, lanMode: Boolean = true) {
    if (server != null) stop(context)
    val host = if (lanMode) "0.0.0.0" else "127.0.0.1"
    val lanIp = getLanIp(context)
    server = EdgeServer(hostname = host, port = port)
    // Apply any already-bound model immediately so the server is ready right away
    applyBoundModelToServer()
    server?.knownModelNames = _knownModelNames
    try {
      server!!.start(NanoHTTPDDefaultTimeout, true)
      _state.value = EdgeServerState(
        isRunning = true,
        port = port,
        lanMode = lanMode,
        activeModelName = boundDisplayName,
        requestCount = _state.value.requestCount,
        lanIp = lanIp
      )
      Log.i(TAG, "EdgeServer started on $host:$port (model=${boundDisplayName.ifEmpty { "none" }})")
      val intent = Intent(context, EdgeServerService::class.java).apply {
        action = EdgeServerService.ACTION_START
        putExtra(EdgeServerService.EXTRA_PORT, port)
      }
      context.startForegroundService(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start server", e)
      _state.value = _state.value.copy(isRunning = false, lastError = e.message)
    }
  }

  fun stop(context: Context) {
    server?.stop()
    server = null
    _state.value = _state.value.copy(isRunning = false)
    val intent = Intent(context, EdgeServerService::class.java).apply {
      action = EdgeServerService.ACTION_STOP
    }
    context.startService(intent)
    Log.i(TAG, "EdgeServer stopped")
  }

  /**
   * Binds a model to the server. Persists the binding so it survives server restarts.
   * If the server is already running, updates it immediately.
   */
  fun bindModel(model: Model, helper: LlmModelHelper, displayName: String = "") {
    val name = displayName.ifEmpty { model.displayName.ifEmpty { model.name } }
    boundModel = model
    boundHelper = helper
    boundAsrEngine = null
    boundDisplayName = name
    applyBoundModelToServer()
    server?.pendingModelName = null   // switch completed successfully
    server?.lastModelLoadError = null
    _state.value = _state.value.copy(activeModelName = name)
    Log.i(TAG, "Model bound: $name (server running=${server != null})")
  }

  fun unbindModel() {
    boundModel = null
    boundHelper = null
    boundAsrEngine = null
    boundDisplayName = ""
    server?.activeModel = null
    server?.activeModelHelper = null
    server?.activeAsrEngine = null
    server?.activeModelDisplayName = ""
    _state.value = _state.value.copy(activeModelName = "")
  }

  fun bindAsrEngine(model: Model, engine: AsrEngine, displayName: String = "") {
    val name = displayName.ifEmpty { model.displayName.ifEmpty { model.name } }
    boundModel = model
    boundHelper = null
    boundAsrEngine = engine
    boundDisplayName = name
    applyBoundModelToServer()
    server?.pendingModelName = null
    server?.lastModelLoadError = null
    _state.value = _state.value.copy(activeModelName = name)
    Log.i(TAG, "ASR model bound: $name (server running=${server != null})")
  }

  fun unbindAsrEngine() {
    boundAsrEngine = null
    if (boundHelper == null) {
      boundModel = null
      boundDisplayName = ""
      server?.activeModel = null
      server?.activeModelHelper = null
      server?.activeAsrEngine = null
      server?.activeModelDisplayName = ""
      _state.value = _state.value.copy(activeModelName = "")
    }
  }

  /**
   * Fully unloads the active model: closes LiteRT engine + conversation, frees native memory,
   * then unbinds from the server. Must be called from a non-main thread (uses Dispatchers.Default
   * internally so the caller doesn't need to worry about dispatch).
   */
  fun unloadActiveModel(scope: CoroutineScope, onDone: () -> Unit = {}) {
    val model = boundModel
    val helper = boundHelper
    val asrEngine = boundAsrEngine
    if (model != null && asrEngine != null) {
      unbindAsrEngine()
      scope.launch(Dispatchers.Default) {
        try {
          asrEngine.close()
          model.instance = null
          model.initializing = false
          Log.i(TAG, "ASR model unloaded successfully")
        } catch (e: Exception) {
          Log.e(TAG, "Error during ASR model unload: ${e.message}")
        } finally {
          onDone()
        }
      }
      return
    }
    if (model == null || helper == null) {
      Log.w(TAG, "unloadActiveModel: nothing to unload")
      onDone()
      return
    }
    Log.i(TAG, "Unloading model '${boundDisplayName}'…")
    // Unbind first so the API immediately reports no active model
    unbindModel()
    scope.launch(Dispatchers.Default) {
      try {
        helper.cleanUp(model = model) {
          model.instance = null
          model.initializing = false
          Log.i(TAG, "Model unloaded successfully")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error during model unload: ${e.message}")
      } finally {
        onDone()
      }
    }
  }

  /**
   * Called when a model fails to load (OOM or native error).
   * Updates the server's last-error state so /health can report it instead of staying in
   * "loading" limbo. The previously-bound model (if any) remains active.
   */
  fun reportModelLoadError(modelName: String, error: String) {
    Log.e(TAG, "Model load failed for '$modelName': $error")
    server?.lastModelLoadError = error
    server?.pendingModelName = null   // clear the pending switch — it failed
  }

  fun incrementRequestCount() {
    _state.value = _state.value.copy(requestCount = _state.value.requestCount + 1)
  }

  /** Called whenever the downloaded model list changes — keeps /v1/models accurate. */
  fun updateKnownModels(models: List<Model>) {
    val names = models.map { it.displayName.ifEmpty { it.name } }
    _knownModelNames = names
    server?.knownModelNames = names
    Log.i(TAG, "Known models updated: ${names.joinToString()}")
  }

  fun getServer(): EdgeServer? = server

  /** Blocks until the server has no in-progress inference, safe to call from any thread. */
  fun waitForIdle(timeoutMs: Long = 12_000L) {
    server?.waitForIdle(timeoutMs)
  }

  private fun applyBoundModelToServer() {
    val s = server ?: return
    s.activeModel = boundModel
    s.activeModelHelper = boundHelper
    s.activeAsrEngine = boundAsrEngine
    s.activeModelDisplayName = boundDisplayName
    s.modelSwitcher = modelSwitcher
    s.modelLister = modelLister
  }

  fun setModelSwitcher(switcher: (String) -> Boolean) {
    modelSwitcher = switcher
    server?.modelSwitcher = switcher
  }

  fun setModelLister(lister: () -> List<String>) {
    modelLister = lister
    server?.modelLister = lister
  }

  private var modelSwitcher: ((String) -> Boolean)? = null
  private var modelLister: (() -> List<String>)? = null

  fun getKnownLanIp(context: Context): String = getLanIp(context)

  /**
   * Returns the ZeroTier IPv4 address if ZeroTier is connected, or empty string if not.
   * Checks VPN transport (how ZeroTier works on Android) then zt* interfaces as fallback.
   */
  fun getZeroTierIp(context: Context): String {
    return try {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      for (network in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
          val linkProps = cm.getLinkProperties(network) ?: continue
          val vpnIp = linkProps.linkAddresses
            .map { it.address }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
          if (!vpnIp.isNullOrEmpty()) return vpnIp
        }
      }
      // Fallback: zt* NetworkInterface (rooted / non-Android ZT path)
      val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
      interfaces
        .filter { iface -> iface.name.startsWith("zt") && iface.isUp }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { addr -> !addr.isLoopbackAddress && addr is java.net.Inet4Address }
        ?.hostAddress ?: ""
    } catch (e: Exception) {
      Log.e(TAG, "getZeroTierIp failed", e)
      ""
    }
  }

  /**
   * Posts a high-priority system notification alerting the user that ZeroTier has gone down.
   * Uses a separate HIGH importance channel so it appears as a heads-up banner even with
   * the app in the background.
   */
  fun postZeroTierDownNotification(context: Context) {
    try {
      val channelId = "zerotier_alert"
      val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (mgr.getNotificationChannel(channelId) == null) {
        val channel = NotificationChannel(
          channelId, "ZeroTier Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
          description = "Alerts when ZeroTier VPN connection drops"
          enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
      }
      val tapIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
      val pendingIntent = PendingIntent.getActivity(
        context, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      val notification = NotificationCompat.Builder(context, channelId)
        .setContentTitle("⚠️ ZeroTier Disconnected")
        .setContentText("Remote clients can no longer reach PhoneLlama. Tap to open app.")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
      mgr.notify(9002, notification)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to post ZeroTier down notification", e)
    }
  }

  private fun getLanIp(context: Context): String {
    return try {
      // On Android, ZeroTier uses the VPN Service API — check VPN networks via ConnectivityManager first
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      for (network in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
          val linkProps = cm.getLinkProperties(network) ?: continue
          val vpnIp = linkProps.linkAddresses
            .map { it.address }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
          if (!vpnIp.isNullOrEmpty()) {
            Log.i(TAG, "Using VPN/ZeroTier IP: $vpnIp")
            return vpnIp
          }
        }
      }
      // Fallback: zt* prefixed NetworkInterface (direct ZeroTier on non-Android / rooted)
      val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
      val ztIp = interfaces
        .filter { iface -> iface.name.startsWith("zt") && iface.isUp }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { addr -> !addr.isLoopbackAddress && addr is java.net.Inet4Address }
        ?.hostAddress
      if (!ztIp.isNullOrEmpty()) {
        Log.i(TAG, "Using ZeroTier NetworkInterface IP: $ztIp")
        return ztIp
      }
      // Final fallback: first non-loopback IPv4 (WiFi/LTE)
      interfaces
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { addr -> !addr.isLoopbackAddress && addr is java.net.Inet4Address }
        ?.hostAddress ?: ""
    } catch (e: Exception) {
      Log.e(TAG, "getLanIp failed", e)
      ""
    }
  }
}

private const val NanoHTTPDDefaultTimeout = -1
