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

package com.google.ai.edge.gallery.edgeserver

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.asr.AsrEngine
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

private const val TAG = "EdgeServer"

/**
 * On-device HTTP server that exposes an OpenAI-compatible REST API backed by
 * AI Edge Gallery's GPU-accelerated LLM inference.
 *
 * Endpoints:
 *   GET  /health                  → server & model status
 *   GET  /v1/models               → list loaded models
 *   POST /v1/chat/completions     → chat completions (streaming & non-streaming)
 */
class EdgeServer(
  hostname: String = DEFAULT_HOST,
  port: Int = DEFAULT_PORT,
  private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
  private val uiHtml: String = DEFAULT_UI_HTML,
) : NanoHTTPD(hostname, port) {

  companion object {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 8888
    const val DEFAULT_TIMEOUT_SECONDS = 180L  // 3 min — allows large/reasoning models to respond
    // Fallback mini-UI used when the assets HTML is not available.
    const val DEFAULT_UI_HTML = """<!DOCTYPE html><html><head><meta charset="UTF-8"><title>PhoneLlama</title></head><body><h1>PhoneLlama</h1><p>UI asset not found. Please open the app.</p></body></html>"""
    private const val MIME_JSON = "application/json"
    private const val MIME_HTML = "text/html"
    // Hard prompt character budget to prevent native KV-cache buffer-overflow crashes in LiteRT.
    // Math: 5000 chars ÷ 3.5 chars/tok ≈ 1429 prompt tokens.
    // Models are configured with maxContextLength=4096 and maxTokens=1024, so:
    //   1429 prompt + 1024 output = 2453 total << 4096 — safe margin of ~1600 tokens.
    // DO NOT raise this above 7000 without also raising maxContextLength in the catalog,
    // or you will re-introduce SIGSEGV (SEGV_ACCERR) in liblitertlm_jni.so.
    private const val MAX_PROMPT_CHARS = 5000
    // Keep raw JSON body reads bounded before parsing them with Gson.
    private const val MAX_JSON_BODY_BYTES = 10L * 1024L * 1024L
    private const val MAX_AUDIO_BODY_BYTES = 60L * 1024L * 1024L
    // Sentinel pushed to the SSE queue to signal end-of-stream.
    // Cannot use null — LinkedBlockingQueue.offer(null) throws NullPointerException.
    private const val STREAM_EOF = "\u0000EOF"

    /**
     * Models that are downloaded but known to be unusable — OOM on load, chain-of-thought timeout,
     * or otherwise unreliable. Hidden from /v1/models and rejected by /activate.
     */
    val BROKEN_MODEL_NAMES = setOf(
      "Qwen3-4B",        // chain-of-thought timeout; 500 errors after first request
      "Gemma-4-E4B-it",  // OOM kill on Pixel Fold (12 GB RAM)
    )

    /**
     * Global runtime config — readable by any class that needs to react to admin flags
     * (e.g. LlmChatModelHelper can check skipClose without a direct reference to EdgeServer).
     */
    val globalConfig = RuntimeConfig()
  }

  @Volatile var activeModel: Model? = null
  @Volatile var activeModelHelper: LlmModelHelper? = null
  @Volatile var activeAsrEngine: AsrEngine? = null
  @Volatile var activeModelDisplayName: String = ""
  @Volatile var modelFinder: (() -> Unit)? = null
  @Volatile var knownModelNames: List<String> = emptyList()
  // Returns true if model was found and switch was queued, false if model not found
  @Volatile var modelSwitcher: ((String) -> Boolean)? = null
  // Returns fresh list of all downloaded model names; called on GET /v1/models
  @Volatile var modelLister: (() -> List<String>)? = null

  // Set when a model switch is initiated via /v1/models/{name}/activate
  @Volatile var pendingModelName: String? = null
  // Set when a model fails to load — reported by EdgeServerManager.reportModelLoadError()
  @Volatile var lastModelLoadError: String? = null

  private val inferenceLock = ReentrantLock()
  private val gson = Gson()

  // Timestamp (ms) when the last inference's native onDone/onError callback fired.
  private val lastNativeDoneTimeMs = java.util.concurrent.atomic.AtomicLong(0L)

  // Rolling tokens/sec tracking: count tokens in a sliding 5s window.
  val recentTokenCount = java.util.concurrent.atomic.AtomicLong(0L)
  val recentTokenWindowStartMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

  /** Returns the approximate tokens/sec over the last measurement window, or 0 if idle. */
  fun tokensPerSec(): Float {
    val elapsed = (System.currentTimeMillis() - recentTokenWindowStartMs.get()).coerceAtLeast(1L)
    val tokens = recentTokenCount.get()
    // Reset window if > 10s since last activity (consider idle)
    return if (elapsed > 10_000L) 0f else tokens.toFloat() / (elapsed / 1000f)
  }

  /** Convenience accessor — always backed by the companion globalConfig singleton. */
  private val config get() = globalConfig

  class RuntimeConfig {
    @Volatile var settleMsStr: String = "2000"   // ms to wait after onDone before next close()
    @Volatile var skipClose: Boolean = true      // skip conversation.close() (diagnostic)
    @Volatile var maxTokens: Int = 0             // 0 = unlimited; >0 = stop after N tokens
    @Volatile var debugLog: Boolean = false      // extra verbose logging
    /** Max model file size in MB allowed to load. 0 = no limit. Default 0 (unlimited).
     *  Set via POST /admin/config {"max_model_size_mb":2900} to block models >2.9GB on
     *  devices where large models cause kernel OOM kills (e.g. Pixel Fold w/ 12GB RAM). */
    @Volatile var maxModelSizeMb: Int = 0

    val settleMs: Long get() = settleMsStr.toLongOrNull() ?: 2000L

    fun toJson(): String = """{"settle_ms":${settleMs},"skip_close":$skipClose,"max_tokens":$maxTokens,"debug_log":$debugLog,"max_model_size_mb":$maxModelSizeMb}"""
  }

  /**
   * Blocks until any in-progress inference has finished, then returns.
   * Called by the model switcher before closing a model's engine to prevent
   * engine.close() from racing against an active inference and crashing.
   */
  fun waitForIdle(timeoutMs: Long = 12_000L) {
    if (inferenceLock.tryLock(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
      inferenceLock.unlock()
    }
  }

  private fun tryAutoDiscoverModel() {
    try {
      modelFinder?.invoke()
    } catch (_: Exception) {}
  }

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri ?: ""
    val method = session.method
    if (activeModel?.instance == null) tryAutoDiscoverModel()
    return try {
      when {
        uri == "/" || uri == "/ui" -> handleWebUi()
        uri == "/health" -> handleHealth()
        uri == "/v1/models" && method == Method.GET -> handleListModels()
        uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletions(session)
        uri == "/v1/audio/transcriptions" && method == Method.POST ->
          handleAudioTranscription(session)
        uri.startsWith("/v1/models/") && uri.endsWith("/activate") && method == Method.POST ->
          handleActivateModel(uri.removePrefix("/v1/models/").removeSuffix("/activate"))
        uri == "/admin/config" && method == Method.GET -> handleGetConfig()
        uri == "/admin/config" && method == Method.POST -> handleSetConfig(session)
        method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").applyCors()
        else -> newFixedLengthResponse(
          Response.Status.NOT_FOUND, MIME_JSON,
          """{"error":{"message":"Not found: $uri","type":"invalid_request_error"}}"""
        ).applyCors()
      }
    } catch (e: Throwable) {
      Log.e(TAG, "Unhandled server error", e)
      errorResponse(500, e.message ?: "Internal server error")
    }
  }

  private fun handleHealth(): Response {
    val loaded = activeModel?.instance != null
    val pending = pendingModelName
    val loadError = lastModelLoadError
    val status = when {
      loadError != null -> "error"
      pending != null && !loaded -> "loading"
      else -> "ok"
    }
    val errorField = if (loadError != null) ""","last_error":${gson.toJson(loadError)}""" else ""
    val pendingField = if (pending != null) ""","pending_model":${gson.toJson(pending)}""" else ""
    val json = """{"status":"$status","model_loaded":$loaded,"model":"$activeModelDisplayName","known_models":${knownModelNames.size}$pendingField$errorField}"""
    return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json).applyCors()
  }

  private fun handleWebUi(): Response {
    return newFixedLengthResponse(Response.Status.OK, MIME_HTML, uiHtml).applyCors()
  }

  private fun handleGetConfig(): Response =
    newFixedLengthResponse(Response.Status.OK, MIME_JSON, config.toJson()).applyCors()

  private fun handleSetConfig(session: IHTTPSession): Response {
    return try {
      val body = readJsonBody(session)
      if (body.isEmpty()) return errorResponse(400, "Empty body")
      val obj = JsonParser.parseString(body).asJsonObject
      if (obj.has("settle_ms"))          config.settleMsStr    = obj.get("settle_ms").asString
      if (obj.has("skip_close"))         config.skipClose      = obj.get("skip_close").asBoolean
      if (obj.has("max_tokens"))         config.maxTokens      = obj.get("max_tokens").asInt
      if (obj.has("debug_log"))          config.debugLog       = obj.get("debug_log").asBoolean
      if (obj.has("max_model_size_mb"))  config.maxModelSizeMb = obj.get("max_model_size_mb").asInt
      Log.i(TAG, "Config updated: ${config.toJson()}")
      newFixedLengthResponse(Response.Status.OK, MIME_JSON, config.toJson()).applyCors()
    } catch (e: Exception) {
      errorResponse(400, "Bad config JSON: ${e.message}")
    }
  }

  private fun handleActivateModel(modelName: String): Response {
    val decoded = java.net.URLDecoder.decode(modelName, "UTF-8")
    if (decoded in BROKEN_MODEL_NAMES) {
      return errorResponse(400, "Model '$decoded' is disabled — it is known to be unreliable on this device. Choose a different model.")
    }
    val switcher = modelSwitcher
      ?: return errorResponse(503, "Model switching not available — open the app first")
    return try {
      val queued = switcher(decoded)
      if (queued) {
        lastModelLoadError = null   // clear any prior error
        pendingModelName = decoded
        newFixedLengthResponse(Response.Status.OK, MIME_JSON,
          """{"status":"loading","model":"$decoded","message":"Model switch initiated. Poll /health until model_loaded is true."}"""
        ).applyCors()
      } else {
        errorResponse(404, "Model '$decoded' not found in downloaded models. Check /v1/models for available IDs.")
      }
    } catch (e: Exception) {
      errorResponse(500, "Model switch failed: ${e.message}")
    }
  }

  private fun handleListModels(): Response {
    val ts = System.currentTimeMillis() / 1000
    val activeId = activeModelDisplayName.ifEmpty { activeModel?.name ?: "" }
    // Get fresh list from ViewModel if available, otherwise fall back to cached knownModelNames
    val freshNames = try { modelLister?.invoke() } catch (_: Throwable) { null }
    val names = (freshNames ?: knownModelNames).filter { it !in BROKEN_MODEL_NAMES }.toMutableList()
    if (activeId.isNotEmpty() && activeId !in names) names.add(0, activeId)

    val body = if (names.isNotEmpty()) {
      val items = names.joinToString(",") { name ->
        val obj = JsonObject().apply {
          addProperty("id", name)
          addProperty("object", "model")
          addProperty("owned_by", "edge-host")
          addProperty("created", ts)
          addProperty("active", name == activeId)
        }
        gson.toJson(obj)
      }
      """{"object":"list","data":[$items]}"""
    } else if (activeModel?.instance != null) {
      val obj = JsonObject().apply {
        addProperty("id", activeId)
        addProperty("object", "model")
        addProperty("owned_by", "edge-host")
        addProperty("created", ts)
        addProperty("active", true)
      }
      """{"object":"list","data":[${gson.toJson(obj)}]}"""
    } else {
      """{"object":"list","data":[]}"""
    }
    return newFixedLengthResponse(Response.Status.OK, MIME_JSON, body).applyCors()
  }

  private fun handleChatCompletions(session: IHTTPSession): Response {
    val bodyStr = try {
      readJsonBody(session)
    } catch (e: Exception) {
      return errorResponse(400, "Failed to parse request body: ${e.message}")
    }
    if (bodyStr.isEmpty()) return errorResponse(400, "Empty request body")

    val body: JsonObject = try {
      val reader = JsonReader(java.io.StringReader(bodyStr))
      reader.isLenient = true
      JsonParser.parseReader(reader).asJsonObject
    } catch (e: Exception) {
      return errorResponse(400, "Invalid JSON: ${e.message}")
    }

    val model = activeModel
    val helper = activeModelHelper
    if (model == null || helper == null || model.instance == null) {
      return errorResponse(503, "No model loaded. Open the app and load a model first.")
    }

    val messages = body.getAsJsonArray("messages")
    if (messages == null || messages.size() == 0) {
      return errorResponse(400, "\"messages\" array is required and must not be empty")
    }

    val tools = body.getAsJsonArray("tools")
    // buildPrompt applies smart message trimming — oldest turns are dropped first until the
    // result fits within MAX_PROMPT_CHARS, preventing native KV-cache overflow in LiteRT.
    val prompt = buildPrompt(messages, tools)
    if (prompt.isBlank()) return errorResponse(400, "No user message content found in messages array")
    val stream = body.get("stream")?.asBoolean ?: false
    val requestId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
    val modelId = activeModelDisplayName.ifEmpty { model.name }
    val hasTools = tools != null && tools.size() > 0
    EdgeServerManager.incrementRequestCount()

    return if (stream) handleStreamingResponse(model, helper, prompt, requestId, modelId)
    else handleNonStreamingResponse(model, helper, prompt, requestId, modelId, hasTools)
  }

  private fun handleAudioTranscription(session: IHTTPSession): Response {
    val engine = activeAsrEngine
      ?: return errorResponse(503, "No SenseVoice ASR model loaded")
    val contentType = session.headers.entries
      .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
      ?.value
      ?.lowercase()
      ?: ""
    return try {
      val wavBytes = if (contentType.startsWith("multipart/form-data")) {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val filePath = files["file"] ?: files["postData"]
          ?: return errorResponse(400, "Multipart field 'file' is required")
        java.io.File(filePath).readBytes()
      } else {
        readBinaryBody(session, MAX_AUDIO_BODY_BYTES)
      }
      if (wavBytes.isEmpty()) return errorResponse(400, "Empty audio request body")
      val result = engine.transcribe(wavBytes)
      val response = JsonObject().apply {
        addProperty("text", result.text)
        result.language?.let { addProperty("language", it) }
        result.emotion?.let { addProperty("emotion", it) }
        add("events", gson.toJsonTree(result.events))
      }
      EdgeServerManager.incrementRequestCount()
      newFixedLengthResponse(Response.Status.OK, MIME_JSON, gson.toJson(response)).applyCors()
    } catch (e: Exception) {
      Log.e(TAG, "Audio transcription failed", e)
      errorResponse(500, "Audio transcription failed: ${e.message ?: "unknown error"}")
    }
  }

  private fun readBinaryBody(session: IHTTPSession, maxBytes: Long): ByteArray {
    val contentLength = session.headers.entries
      .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
      ?.value
      ?.toLongOrNull()
      ?: throw IllegalArgumentException("Content-Length header is required")
    if (contentLength <= 0L || contentLength > maxBytes) {
      throw IllegalArgumentException("Invalid audio body size: $contentLength bytes")
    }
    val input = session.inputStream
    val body = ByteArrayOutputStream(contentLength.toInt())
    val buffer = ByteArray(8192)
    var remaining = contentLength
    while (remaining > 0L) {
      val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
      if (read < 0) throw IllegalArgumentException("Unexpected end of audio body")
      body.write(buffer, 0, read)
      remaining -= read
    }
    return body.toByteArray()
  }

  /**
   * Reads a JSON request without going through NanoHTTPD's parseBody().
   *
   * NanoHTTPD's ContentType.getEncoding() defaults to US-ASCII when a
   * Content-Type header has no charset. Most JSON clients send only
   * "application/json", so parseBody() corrupts non-ASCII UTF-8 prompts before
   * Gson sees them. JSON request bodies are UTF-8 by default (RFC 8259).
   */
  private fun readJsonBody(session: IHTTPSession): String {
    val contentLength = session.headers.entries
      .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
      ?.value
      ?.toLongOrNull()
      ?: throw IllegalArgumentException("Content-Length header is required")

    if (contentLength < 0 || contentLength > MAX_JSON_BODY_BYTES) {
      throw IllegalArgumentException("Invalid request body size: $contentLength bytes")
    }

    val input = session.inputStream
    val body = ByteArrayOutputStream(contentLength.toInt())
    val buffer = ByteArray(8192)
    var remaining = contentLength
    while (remaining > 0) {
      val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
      if (read < 0) throw IllegalArgumentException("Unexpected end of request body")
      body.write(buffer, 0, read)
      remaining -= read
    }
    return String(body.toByteArray(), Charsets.UTF_8)
  }

  /**
   * Builds the prompt from messages, applying smart message trimming to prevent KV-cache overflow.
   *
   * Strategy:
   *  1. Always include system messages and tool definitions (they set essential context).
   *  2. Include as many turns as possible from the END of the history (most recent first).
   *  3. Drop the oldest non-system turns until the total fits within MAX_PROMPT_CHARS.
   *  4. If a single turn is itself larger than the budget, it is hard-truncated from the front.
   *
   * This is safer than raw-string takeLast() which can cut in the middle of a role prefix.
   */
  private fun buildPrompt(
    messages: com.google.gson.JsonArray,
    tools: com.google.gson.JsonArray? = null,
  ): String {
    // --- System section (always included) ---
    val systemParts = mutableListOf<String>()

    // Inject tool definitions into a system prompt block if tools are provided.
    if (tools != null && tools.size() > 0) {
      val toolDefs = buildString {
        append("You are a helpful assistant with access to the following tools.\n")
        append("To call a tool, respond ONLY with a JSON object in this exact format (no other text):\n")
        append("{\"name\": \"<tool_name>\", \"arguments\": {<args>}}\n\n")
        append("Available tools:\n")
        for (tool in tools) {
          val t = tool.asJsonObject
          val fn = t.getAsJsonObject("function") ?: t
          val name = fn.get("name")?.asString ?: continue
          val desc = fn.get("description")?.asString ?: ""
          val params = fn.get("parameters")?.toString() ?: "{}"
          append("- $name: $desc\n  parameters: $params\n")
        }
        append("\nIf you don't need any tool, answer normally as text.")
      }
      systemParts.add("[system]: $toolDefs")
    }

    // --- Turn history section ---
    val turnParts = mutableListOf<String>()
    var hasToolResults = false

    for (el in messages) {
      val obj = el.asJsonObject
      val role = obj.get("role")?.asString ?: "user"
      when (role) {
        "system" -> {
          val content = extractContent(obj.get("content"))
          if (content.isNotEmpty()) systemParts.add("[system]: $content")
        }
        "assistant" -> {
          val toolCalls = obj.getAsJsonArray("tool_calls")
          if (toolCalls != null && toolCalls.size() > 0) {
            val tc = toolCalls[0].asJsonObject
            val fn = tc.getAsJsonObject("function")
            val name = fn?.get("name")?.asString ?: ""
            val args = fn?.get("arguments")?.asString ?: "{}"
            turnParts.add("[assistant]: {\"name\": \"$name\", \"arguments\": $args}")
          } else {
            val content = extractContent(obj.get("content"))
            if (content.isNotEmpty()) turnParts.add("[assistant]: $content")
          }
        }
        "tool" -> {
          hasToolResults = true
          val content = extractContent(obj.get("content"))
          val toolCallId = obj.get("tool_call_id")?.asString ?: ""
          turnParts.add("[tool result ($toolCallId)]: $content")
        }
        else -> {
          val content = extractContent(obj.get("content"))
          if (content.isNotEmpty()) turnParts.add("[$role]: $content")
        }
      }
    }

    val suffix = if (hasToolResults) "\n\nUsing the tool results above, provide a final answer." else ""

    // --- Smart trimming ---
    val systemText = systemParts.joinToString("\n")
    // Reserve space for system block + suffix + separator newlines.
    val budget = MAX_PROMPT_CHARS - systemText.length - suffix.length - 10

    if (budget <= 0) {
      // Edge case: system text alone exceeds budget — just return the last user turn.
      Log.w(TAG, "System text exceeds budget; returning only last user turn")
      return (turnParts.lastOrNull() ?: "") + suffix
    }

    // Drop oldest turns from the front until combined turn text fits in budget.
    while (turnParts.size > 1) {
      val combined = turnParts.joinToString("\n")
      if (combined.length <= budget) break
      Log.w(TAG, "Context too long (${combined.length} > $budget chars); dropping oldest turn")
      turnParts.removeAt(0)
    }

    // If a single remaining turn is still too large, hard-truncate it from the front.
    val turnsText = run {
      val combined = turnParts.joinToString("\n")
      if (combined.length > budget) {
        Log.w(TAG, "Single turn still too long (${combined.length}); truncating")
        combined.takeLast(budget)
      } else combined
    }

    return buildString {
      if (systemText.isNotEmpty()) { append(systemText); append("\n") }
      append(turnsText)
      append(suffix)
    }
  }

  private fun extractContent(element: com.google.gson.JsonElement?): String {
    if (element == null || element.isJsonNull) return ""
    if (element.isJsonPrimitive) return element.asString
    if (element.isJsonArray) {
      return buildString {
        for (part in element.asJsonArray) {
          if (part.isJsonObject) {
            val p = part.asJsonObject
            if (p.get("type")?.asString == "text") append(p.get("text")?.asString ?: "")
          }
        }
      }
    }
    if (element.isJsonObject) return element.asJsonObject.get("text")?.asString ?: ""
    return ""
  }

  /** Returns true if the inference error is a dead/invalidated conversation that can be retried. */
  private fun isDeadConversationError(msg: String): Boolean {
    val lower = msg.lowercase()
    return lower.contains("not alive") ||
      lower.contains("conversation has been closed") ||
      lower.contains("conversation is closed") ||
      lower.contains("session is not valid") ||
      lower.contains("invalid conversation")
  }

  private fun handleNonStreamingResponse(
    model: Model, helper: LlmModelHelper, prompt: String,
    requestId: String, modelId: String, hasTools: Boolean = false
  ): Response {
    val fullResponse = StringBuilder()
    val latch = CountDownLatch(1)
    val error = StringBuilder()

    // Hold the lock for the ENTIRE request — runInference() is async, callbacks fire later.
    // Releasing early (before latch.await) allowed concurrent requests to call resetConversation()
    // simultaneously, corrupting the runtime and crashing the app.
    inferenceLock.lock()
    try {
      // Retry once if we get a dead-conversation error (e.g. "Conversation is not alive").
      // This recovers automatically from bad native state without requiring a manual model reload.
      for (attempt in 0 until 2) {
        fullResponse.clear()
        error.clear()
        val attemptLatch = CountDownLatch(1)

        // Same settling wait as streaming: ensure config.settleMs has elapsed since the last
        // inference's onDone callback before calling resetConversation() → conversation.close().
        val lastDone = lastNativeDoneTimeMs.get()
        if (lastDone > 0L) {
          val wait = config.settleMs - (System.currentTimeMillis() - lastDone)
          if (wait > 0) Thread.sleep(wait)
        }
        helper.resetConversation(model = model)
        helper.runInference(
          model = model,
          input = prompt,
          resultListener = { partial, done, _ ->
            if (done) lastNativeDoneTimeMs.set(System.currentTimeMillis())
            fullResponse.append(partial)
            if (done) attemptLatch.countDown()
          },
          cleanUpListener = {},
          onError = { message ->
            lastNativeDoneTimeMs.set(System.currentTimeMillis())
            error.append(message)
            attemptLatch.countDown()
          },
        )
        if (!attemptLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
          try { helper.stopResponse(model) } catch (_: Throwable) {}
          attemptLatch.await(8, TimeUnit.SECONDS)
          return errorResponse(504, "Inference timed out after ${timeoutSeconds}s")
        }

        // If we got a dead-conversation error and this is the first attempt, retry.
        if (error.isNotEmpty() && isDeadConversationError(error.toString()) && attempt == 0) {
          Log.w(TAG, "Dead conversation on attempt 1 — resetting and retrying automatically")
          continue
        }
        break
      }
      // inferenceLock is released in finally. The next request will wait in its settling
      // sleep (NATIVE_SETTLE_MS from lastNativeDoneTimeMs) before the next conversation.close().
    } catch (e: Throwable) {
      Log.e(TAG, "Non-streaming inference crash", e)
      return errorResponse(500, "Inference error: ${e.message ?: e.javaClass.simpleName}")
    } finally {
      inferenceLock.unlock()
    }

    if (error.isNotEmpty()) return errorResponse(500, error.toString())

    val created = System.currentTimeMillis() / 1000
    val responseText = fullResponse.toString()
    val completionTokens = responseText.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    val promptTokens = prompt.split(Regex("\\s+")).filter { it.isNotBlank() }.size

    // Detect tool call: model responded with {"name": "...", "arguments": {...}}
    val toolCall = if (hasTools) parseToolCall(responseText) else null

    val choiceJson = if (toolCall != null) {
      val callId = "call_${UUID.randomUUID().toString().take(8)}"
      val argsStr = gson.toJson(toolCall.second)
      """{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"$callId","type":"function","function":{"name":${gson.toJson(toolCall.first)},"arguments":${gson.toJson(argsStr)}}}]},"finish_reason":"tool_calls"}"""
    } else {
      """{"index":0,"message":{"role":"assistant","content":${gson.toJson(responseText)}},"finish_reason":"stop"}"""
    }

    val responseJson = """{"id":"$requestId","object":"chat.completion","created":$created,"model":"$modelId","choices":[$choiceJson],"usage":{"prompt_tokens":$promptTokens,"completion_tokens":$completionTokens,"total_tokens":${promptTokens + completionTokens}}}"""
    return newFixedLengthResponse(Response.Status.OK, MIME_JSON, responseJson).applyCors()
  }

  /** Attempts to parse a tool call JSON from the model's raw output. Returns name+args or null. */
  private fun parseToolCall(text: String): Pair<String, Map<String, Any>>? {
    return try {
      // Find the first {...} block in the response
      val start = text.indexOf('{')
      val end = text.lastIndexOf('}')
      if (start < 0 || end <= start) return null
      val candidate = text.substring(start, end + 1)
      val obj = JsonParser.parseString(candidate).asJsonObject
      val name = obj.get("name")?.asString ?: return null
      val argsEl = obj.get("arguments") ?: return null
      val args: Map<String, Any> = if (argsEl.isJsonObject) {
        argsEl.asJsonObject.entrySet().associate { (k, v) ->
          k to (v.asString ?: v.toString())
        }
      } else emptyMap()
      Pair(name, args)
    } catch (_: Throwable) { null }
  }

  private fun handleStreamingResponse(
    model: Model, helper: LlmModelHelper, prompt: String,
    requestId: String, modelId: String
  ): Response {
    val created = System.currentTimeMillis() / 1000
    val queue = LinkedBlockingQueue<String>()

    // Use AtomicReferences so the retry path can swap in fresh latches while the same
    // lambda closures continue to call .get().countDown() on whichever latch is current.
    val doneLatchRef = java.util.concurrent.atomic.AtomicReference(CountDownLatch(1))
    val nativeDoneLatchRef = java.util.concurrent.atomic.AtomicReference(CountDownLatch(1))

    // Background thread holds inferenceLock for the full inference duration.
    // This prevents concurrent requests from calling resetConversation() simultaneously.
    // cancelled: set by InputStream.close() when client disconnects mid-stream.
    // inferenceDone: set when LiteRT fires resultListener(isDone=true) — completed normally.
    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    val inferenceDone = java.util.concurrent.atomic.AtomicBoolean(false)
    val tokenCount = java.util.concurrent.atomic.AtomicInteger(0)
    val deadConversation = java.util.concurrent.atomic.AtomicBoolean(false)

    Thread {
      inferenceLock.lock()
      try {
        // Retry once if we get a dead-conversation error before any tokens are emitted.
        for (attempt in 0 until 2) {
          deadConversation.set(false)
          inferenceDone.set(false)
          // Fresh latches for this attempt — swap into the refs so callbacks use the new ones.
          doneLatchRef.set(CountDownLatch(1))
          nativeDoneLatchRef.set(CountDownLatch(1))

          // Settling wait: ensure config.settleMs has elapsed since previous inference callback.
          val lastDone = lastNativeDoneTimeMs.get()
          if (lastDone > 0L) {
            val wait = config.settleMs - (System.currentTimeMillis() - lastDone)
            if (wait > 0) Thread.sleep(wait)
          }
          helper.resetConversation(model = model)
          helper.runInference(
            model = model,
            input = prompt,
            resultListener = { partial, isDone, _ ->
              try {
                if (isDone) {
                  inferenceDone.set(true)
                  lastNativeDoneTimeMs.set(System.currentTimeMillis())
                  nativeDoneLatchRef.get().countDown()
                }
                if (cancelled.get()) return@runInference
                if (partial.isNotEmpty()) {
                  val delta = JsonObject().apply { addProperty("content", partial) }
                  val choice = JsonObject().apply {
                    addProperty("index", 0)
                    add("delta", delta)
                    addProperty("finish_reason", if (isDone) "stop" else null.toString())
                  }
                  val chunk = JsonObject().apply {
                    addProperty("id", requestId)
                    addProperty("object", "chat.completion.chunk")
                    addProperty("created", created)
                    addProperty("model", modelId)
                    add("choices", gson.toJsonTree(listOf(choice)))
                  }
                  queue.offer("data: ${gson.toJson(chunk)}\n\n")
                  val now = System.currentTimeMillis()
                  if (now - recentTokenWindowStartMs.get() > 5_000L) {
                    recentTokenCount.set(0L)
                    recentTokenWindowStartMs.set(now)
                  }
                  recentTokenCount.incrementAndGet()
                  val mt = config.maxTokens
                  if (mt > 0 && tokenCount.incrementAndGet() >= mt && !isDone) {
                    inferenceDone.set(true)
                    lastNativeDoneTimeMs.set(System.currentTimeMillis())
                    nativeDoneLatchRef.get().countDown()
                    queue.offer("data: [DONE]\n\n")
                    queue.offer(STREAM_EOF)
                    doneLatchRef.get().countDown()
                    try { helper.stopResponse(model) } catch (_: Throwable) {}
                    return@runInference
                  }
                }
                if (isDone) {
                  queue.offer("data: [DONE]\n\n")
                  queue.offer(STREAM_EOF)
                  doneLatchRef.get().countDown()
                }
              } catch (e: Throwable) {
                Log.e(TAG, "resultListener threw inside JNI callback — must not propagate", e)
                nativeDoneLatchRef.get().countDown()
                queue.offer(STREAM_EOF)
                doneLatchRef.get().countDown()
              }
            },
            cleanUpListener = { doneLatchRef.get().countDown() },
            onError = { message ->
              Log.e(TAG, "Streaming inference error: $message")
              lastNativeDoneTimeMs.set(System.currentTimeMillis())
              nativeDoneLatchRef.get().countDown()
              if (isDeadConversationError(message) && tokenCount.get() == 0) {
                Log.w(TAG, "Dead conversation in streaming (attempt ${attempt + 1}) — ${if (attempt == 0) "will retry" else "giving up"}")
                deadConversation.set(true)
              }
              queue.offer(STREAM_EOF)
              doneLatchRef.get().countDown()
            },
          )
          val timedOut = !doneLatchRef.get().await(timeoutSeconds, TimeUnit.SECONDS)
          if (timedOut && !cancelled.get()) {
            try { helper.stopResponse(model) } catch (_: Throwable) {}
          }
          nativeDoneLatchRef.get().await(8, TimeUnit.SECONDS)

          // Retry if conversation was dead and no tokens were sent to the client yet.
          if (deadConversation.get() && tokenCount.get() == 0 && attempt == 0) {
            Log.w(TAG, "Streaming: retrying after dead conversation (no tokens sent)")
            queue.clear() // discard the STREAM_EOF from the failed attempt
            continue
          }
          break
        }
      } catch (e: Throwable) {
        Log.e(TAG, "Streaming thread crash", e)
        queue.offer(STREAM_EOF)
      } finally {
        inferenceLock.unlock()
        queue.offer(STREAM_EOF) // guarantee EOF even on error
      }
    }.also { it.isDaemon = true; it.start() }

    val sseStream = object : InputStream() {
      private var buf: ByteArray = ByteArray(0)
      private var pos = 0
      private var eof = false

      private fun nextChunk(): Boolean {
        val next = queue.poll(timeoutSeconds, TimeUnit.SECONDS)
        if (next == null || next === STREAM_EOF) { eof = true; return false }
        buf = next.toByteArray(Charsets.UTF_8)
        pos = 0
        return true
      }

      override fun read(): Int {
        if (eof) return -1
        if (pos >= buf.size && !nextChunk()) return -1
        return buf[pos++].toInt() and 0xFF
      }

      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (eof) return -1
        if (pos >= buf.size && !nextChunk()) return -1
        val toCopy = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, toCopy)
        pos += toCopy
        return toCopy
      }

      override fun close() {
        // Only cancel if inference is still in progress (client disconnected mid-stream).
        // Guard against two false-positive cases that would call stopResponse() on a
        // completed inference → native crash:
        //   1. eof=true:  NanoHTTPD read past the null sentinel — inference definitely done.
        //   2. inferenceDone=true: resultListener(isDone=true) already fired — inference done.
        //      Race: client closes TCP before NanoHTTPD reads the null from queue → eof still
        //      false, but inference has actually completed. Without this guard, stopResponse()
        //      would be called on a finished conversation → SIGSEGV in native code.
        if (!eof && !inferenceDone.get()) {
          cancelled.set(true)
          try { helper.stopResponse(model) } catch (_: Throwable) {}
          doneLatchRef.get().countDown() // unblock the background thread's await
        }
        super.close()
      }
    }

    return newChunkedResponse(Response.Status.OK, "text/event-stream", sseStream)
      .also { resp ->
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
      }
  }

  private fun errorResponse(httpCode: Int, message: String): Response {
    val status = when (httpCode) {
      400 -> Response.Status.BAD_REQUEST
      404 -> Response.Status.NOT_FOUND
      503 -> Response.Status.SERVICE_UNAVAILABLE
      504 -> Response.Status.lookup(504) ?: Response.Status.INTERNAL_ERROR
      else -> Response.Status.INTERNAL_ERROR
    }
    val body = """{"error":{"message":${gson.toJson(message)},"code":$httpCode}}"""
    return newFixedLengthResponse(status, MIME_JSON, body).applyCors()
  }

  private fun Response.applyCors(): Response {
    addHeader("Access-Control-Allow-Origin", "*")
    addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    return this
  }
}
