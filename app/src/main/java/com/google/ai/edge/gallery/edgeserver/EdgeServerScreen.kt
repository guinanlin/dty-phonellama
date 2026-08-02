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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeServerScreen(modelManagerViewModel: ModelManagerViewModel) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val uiState by modelManagerViewModel.uiState.collectAsState()
  // Show ALL downloaded LLM models — not just those already initialized.
  val downloadedModels = remember(uiState) {
    modelManagerViewModel.getAllDownloadedModels()
  }
  val serverState by EdgeServerManager.state.collectAsState()
  var portText by remember { mutableStateOf(EdgeServer.DEFAULT_PORT.toString()) }
  var lanMode by remember { mutableStateOf(true) }
  var showLanWarning by remember { mutableStateOf(false) }
  var snippetsExpanded by remember { mutableStateOf(false) }
  var zeroTierIp by remember { mutableStateOf("") }
  var zeroTierWasConnected by remember { mutableStateOf(false) }
  var zeroTierDropped by remember { mutableStateOf(false) }

  // Poll ZeroTier status every 5 seconds; detect drops and fire system notification
  LaunchedEffect(Unit) {
    while (true) {
      val ip = EdgeServerManager.getZeroTierIp(context)
      val wasConnected = zeroTierWasConnected
      if (ip.isNotEmpty()) {
        zeroTierWasConnected = true
        zeroTierDropped = false
      } else if (wasConnected && ip.isEmpty()) {
        // Was connected, now gone — fire alert
        zeroTierDropped = true
        EdgeServerManager.postZeroTierDownNotification(context)
      }
      zeroTierIp = ip
      delay(5_000)
    }
  }

  LaunchedEffect(serverState.isRunning, serverState.port, serverState.lanMode) {
    if (serverState.isRunning) {
      portText = serverState.port.toString()
      lanMode = serverState.lanMode
    }
  }

  val scrollState = rememberScrollState()
  val knownLanIp = if (serverState.lanIp.isNotEmpty()) serverState.lanIp else EdgeServerManager.getKnownLanIp(context)
  val baseUrl =
    if (serverState.isRunning) {
      if (serverState.lanIp.isNotEmpty()) {
        "http://${serverState.lanIp}:${serverState.port}"
      } else {
        "http://localhost:${serverState.port}"
      }
    } else {
      val ip = EdgeServerManager.getKnownLanIp(context)
      if (ip.isNotEmpty()) "http://$ip:${portText.ifEmpty { "8888" }}"
      else "http://localhost:${portText.ifEmpty { "8888" }}"
    }
  val wsBaseUrl =
    if (serverState.isRunning) {
      val host = serverState.lanIp.ifEmpty { "localhost" }
      "ws://$host:${serverState.wsPort}${EdgeServer.VOLC_ASR_WS_PATH}"
    } else {
      val ip = EdgeServerManager.getKnownLanIp(context)
      val wsPort = (portText.ifEmpty { "8888" }).toIntOrNull()?.plus(1) ?: EdgeServer.DEFAULT_WS_PORT
      if (ip.isNotEmpty()) "ws://$ip:$wsPort${EdgeServer.VOLC_ASR_WS_PATH}"
      else "ws://localhost:$wsPort${EdgeServer.VOLC_ASR_WS_PATH}"
    }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {

    // ZeroTier dropped banner — shown prominently at top when ZT was connected and dropped
    AnimatedVisibility(
      visible = zeroTierDropped,
      enter = expandVertically(),
      exit = shrinkVertically(),
    ) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C)),
        shape = RoundedCornerShape(12.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(28.dp),
          )
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              "ZeroTier Disconnected",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = Color.White,
            )
            Text(
              "Remote clients cannot reach this server. Restart ZeroTier to restore access.",
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFFFFCDD2),
            )
          }
        }
      }
    }
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors =
        CardDefaults.cardColors(
          containerColor =
            if (serverState.isRunning) {
              MaterialTheme.colorScheme.primaryContainer
            } else {
              MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
      Row(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = if (serverState.isRunning) Icons.Default.Wifi else Icons.Default.WifiOff,
          contentDescription = null,
          tint =
            if (serverState.isRunning) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            if (serverState.isRunning) "PhoneLlama API — Running" else "PhoneLlama API — Stopped",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          val statusPort =
            if (serverState.isRunning) serverState.port
            else portText.toIntOrNull() ?: EdgeServer.DEFAULT_PORT
          val statusWsPort =
            if (serverState.isRunning) serverState.wsPort else statusPort + 1
          Text(
            "HTTP localhost:$statusPort  ·  WS :$statusWsPort",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.secondary,
          )
          Text(
            "ws://127.0.0.1:$statusWsPort${EdgeServer.VOLC_ASR_WS_PATH}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.tertiary,
          )
          if (knownLanIp.isNotEmpty()) {
            Text(
              if (serverState.lanMode && serverState.isRunning) {
                "LAN $knownLanIp:$statusPort / ws:$statusWsPort"
              } else {
                "$knownLanIp:$statusPort (ZeroTier) / ws:$statusWsPort"
              },
              style = MaterialTheme.typography.bodySmall,
              fontFamily = FontFamily.Monospace,
              color = MaterialTheme.colorScheme.secondary,
            )
          }
        }
        Switch(
          checked = serverState.isRunning,
          onCheckedChange = { checked ->
            val port = portText.toIntOrNull() ?: EdgeServer.DEFAULT_PORT
            if (checked) {
              EdgeServerManager.start(context, port = port, lanMode = lanMode)
            } else {
              EdgeServerManager.stop(context)
            }
          },
        )
      }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            if (serverState.activeModelName.isNotEmpty()) Icons.Default.Psychology else Icons.Default.HourglassEmpty,
            contentDescription = null,
            tint =
              if (serverState.activeModelName.isNotEmpty()) MaterialTheme.colorScheme.secondary
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              "Active Model",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (serverState.activeModelName.isNotEmpty()) {
              Text(
                serverState.activeModelName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
              )
            } else {
              Text(
                "None — open a model in the app to bind it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        // Unload button — only visible when a model is loaded
        if (serverState.activeModelName.isNotEmpty()) {
          Button(
            onClick = {
              EdgeServerManager.unloadActiveModel(scope)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.errorContainer,
              contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
          ) {
            Icon(Icons.Default.PowerOff, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Unload ${serverState.activeModelName}")
          }
        }
      }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          "Select API Model",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        if (downloadedModels.isEmpty()) {
          Text(
            "No models downloaded yet. Open the Models tab to download one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          downloadedModels.forEach { model ->
            val modelName = model.displayName.ifEmpty { model.name }
            val isInitialized = model.instance != null
            val isInitializing = uiState.modelInitializationStatus[model.name]?.status ==
              ModelInitializationStatusType.INITIALIZING
            val isActive = serverState.activeModelName == modelName
            Row(
              modifier =
                Modifier.fillMaxWidth().selectable(
                  selected = isActive,
                  onClick = {
                    if (isInitialized) {
                      EdgeServerManager.bindModel(
                        model,
                        model.runtimeHelper as LlmModelHelper,
                        modelName,
                      )
                    } else {
                      // Trigger initialization — bindModel is called automatically when done.
                      val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT)
                      if (task != null) {
                        modelManagerViewModel.initializeModel(context, task, model)
                      }
                    }
                  },
                ).padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = isActive,
                onClick = {
                  if (isInitialized) {
                    EdgeServerManager.bindModel(
                      model,
                      model.runtimeHelper as LlmModelHelper,
                      modelName,
                    )
                  } else {
                    val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT)
                    if (task != null) {
                      modelManagerViewModel.initializeModel(context, task, model)
                    }
                  }
                },
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(modelName, style = MaterialTheme.typography.bodyMedium)
                Text(
                  when {
                    isActive -> "Active"
                    isInitializing -> "Loading…"
                    isInitialized -> "Ready — tap to set active"
                    else -> "Downloaded — tap to load"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = when {
                    isActive -> Color(0xFF2E7D32)
                    isInitializing -> MaterialTheme.colorScheme.primary
                    isInitialized -> Color(0xFF2E7D32)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                  },
                )
              }
            }
          }
        }
      }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          "Configuration",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        OutlinedTextField(
          value = portText,
          onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
          label = { Text("Port") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          enabled = !serverState.isRunning,
          modifier = Modifier.fillMaxWidth(),
          leadingIcon = { Icon(Icons.Default.Router, contentDescription = null) },
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              "LAN Mode",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Medium,
            )
            Text(
              "Expose to local network (trusted networks only)",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = lanMode,
            onCheckedChange = { if (it) showLanWarning = true else lanMode = false },
            enabled = !serverState.isRunning,
          )
        }
        if (lanMode) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp),
          ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
              Spacer(Modifier.width(8.dp))
              Text(
                "LAN mode active — visible to all devices on this network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
          }
        }
      }
    }

    if (serverState.isRunning) {
      Card(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.padding(16.dp).fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
          StatItem(serverState.requestCount.toString(), "Requests", Icons.Default.FlashOn)
          StatItem(serverState.port.toString(), "Port", Icons.Default.Router)
          StatItem(
            if (serverState.lanMode) "LAN" else "Local",
            "Mode",
            if (serverState.lanMode) Icons.Default.Wifi else Icons.Default.Lock,
          )
        }
      }
    }

    // ZeroTier status card
    val ztConnected = zeroTierIp.isNotEmpty()
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
        containerColor = when {
          ztConnected    -> MaterialTheme.colorScheme.secondaryContainer
          zeroTierDropped -> Color(0xFFFFEBEE)  // light red — dropped
          else           -> MaterialTheme.colorScheme.surfaceVariant  // never connected
        },
      ),
    ) {
      Row(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = if (zeroTierDropped && !ztConnected) Icons.Default.Warning else Icons.Rounded.Hub,
          contentDescription = null,
          tint = when {
            ztConnected     -> MaterialTheme.colorScheme.secondary
            zeroTierDropped -> Color(0xFFC62828)
            else            -> MaterialTheme.colorScheme.onSurfaceVariant
          },
          modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            "ZeroTier",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (ztConnected) {
            Text(
              zeroTierIp,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
              fontFamily = FontFamily.Monospace,
            )
            Text(
              "Connected — remote clients can reach this device",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else if (zeroTierDropped) {
            Text(
              "Connection lost",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = Color(0xFFC62828),
            )
            Text(
              "ZeroTier went down — remote clients are unreachable",
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFFC62828),
            )
          } else {
            Text(
              "Not connected",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              "Start the ZeroTier app to enable remote access",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          "Endpoints",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        EndpointRow("health", "GET", "$baseUrl/health", context)
        EndpointRow("models", "GET", "$baseUrl/v1/models", context)
        EndpointRow("chat", "POST", "$baseUrl/v1/chat/completions", context)
      }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth().clickable { snippetsExpanded = !snippetsExpanded },
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(8.dp))
            Text(
              "Test & Connect",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
            )
          }
          Icon(
            if (snippetsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
          )
        }

        AnimatedVisibility(
          visible = snippetsExpanded,
          enter = expandVertically(),
          exit = shrinkVertically(),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp),
          ) {
            SnippetSection("curl — health check", """
curl $baseUrl/health
            """.trimIndent(), context)

            SnippetSection("curl — list models", """
curl $baseUrl/v1/models
            """.trimIndent(), context)

            SnippetSection("curl — chat (non-streaming)", """
curl $baseUrl/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "phonellama",
    "messages": [
      {"role": "user", "content": "Hello, what can you do?"}
    ]
  }'
            """.trimIndent(), context)

            SnippetSection("curl — chat (streaming)", """
curl $baseUrl/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "phonellama",
    "messages": [
      {"role": "user", "content": "Write a haiku about mobile AI."}
    ],
    "stream": true
  }'
            """.trimIndent(), context)

            SnippetSection("Volc ASR — streaming WebSocket (SenseVoice)", """
Endpoint: $wsBaseUrl
Protocol: Volcengine bigmodel_async binary (Ogg Opus TaskRequest)
Handshake headers:
  X-Api-Key: local
  X-Api-Resource-Id: volc.seedasr.sauc.duration
  X-Api-Request-Id: <uuid>
  X-Api-Sequence: -1
Flow: StartConnection(1) → 50 → StartSession(100) → 150 → TaskRequest(200) → 451 → FinishSession(102) → 152
Requires SenseVoice-Small active on device.
            """.trimIndent(), context)

            SnippetSection("Python — openai client", """
from openai import OpenAI

client = OpenAI(
    base_url="${baseUrl}/v1",
    api_key="phonellama"
)

response = client.chat.completions.create(
    model="phonellama",
    messages=[{"role": "user", "content": "Hello!"}]
)
print(response.choices[0].message.content)
            """.trimIndent(), context)

            SnippetSection("DeerFlow config (YAML)", """
models:
  - name: phone_llama
    display_name: PhoneLlama on-device
    use: langchain_openai:ChatOpenAI
    model: phonellama
    api_key: phonellama
    base_url: ${baseUrl}/v1
            """.trimIndent(), context)

            SnippetSection("Open WebUI — base URL", """
${baseUrl}/v1
(API key: phonellama or leave blank)
            """.trimIndent(), context)

            SnippetSection("LM Studio / Jan — OpenAI-compatible URL", """
${baseUrl}/v1
            """.trimIndent(), context)
          }
        }
      }
    }

    serverState.lastError?.let { error ->
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
          Spacer(Modifier.width(8.dp))
          Text(
            error,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }

    Spacer(Modifier.height(16.dp))
  }

  if (showLanWarning) {
    AlertDialog(
      onDismissRequest = { showLanWarning = false },
      icon = { Icon(Icons.Default.Warning, contentDescription = null) },
      title = { Text("Enable LAN Mode?") },
      text = {
        Text(
          "This exposes your PhoneLlama server to all devices on your local network. Only use on trusted networks.",
        )
      },
      confirmButton = {
        TextButton(onClick = { lanMode = true; showLanWarning = false }) {
          Text("Enable", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showLanWarning = false }) { Text("Cancel") }
      },
    )
  }
}

@Composable
private fun StatItem(value: String, label: String, icon: ImageVector) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun EndpointRow(name: String, method: String, url: String, context: Context) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Surface(
      color = if (method == "GET") Color(0xFF1A5C20) else Color(0xFF1A2D5C),
      shape = RoundedCornerShape(4.dp),
    ) {
      Text(
        method,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (method == "GET") Color(0xFF4ADE80) else Color(0xFF60A5FA),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        url,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.secondary,
      )
    }
    IconButton(onClick = { copyToClipboard(context, url) }, modifier = Modifier.size(28.dp)) {
      Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
    }
  }
}

@Composable
private fun SnippetSection(title: String, code: String, context: Context) {
  Column {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.SemiBold,
      )
      IconButton(onClick = { copyToClipboard(context, code) }, modifier = Modifier.size(28.dp)) {
        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp))
      }
    }
    val hScroll = rememberScrollState()
    Surface(
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
      shape = RoundedCornerShape(6.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = code,
        modifier = Modifier.horizontalScroll(hScroll).padding(10.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 18.sp,
      )
    }
  }
}

private fun copyToClipboard(context: Context, text: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText("phonellama", text))
}
