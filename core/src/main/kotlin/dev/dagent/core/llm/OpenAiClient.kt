package dev.dagent.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** How a provider wants to be asked to think harder. */
enum class ReasoningStyle { NONE, EFFORT, OPENROUTER }

/**
 * Any OpenAI-compatible chat completions endpoint: OpenAI, OpenRouter, Groq, xAI, Gemini's
 * compatibility layer, Mistral, Together, Ollama, LM Studio, FreeLLMAPI and the on-phone
 * llama.cpp server. Streams text, reasoning and native tool calls.
 */
class OpenAiClient(
    private val http: OkHttpClient,
    baseUrl: String,
    private val apiKey: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val reasoning: ReasoningStyle = ReasoningStyle.NONE,
) : LlmClient {
    private val base = baseUrl.trimEnd('/')

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val body = buildBody(request).toString()
        val http = requestBuilder("$base/chat/completions")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON))
            .build()

        val calls = sortedMapOf<Int, PartialCall>()
        var finish = "stop"
        this@OpenAiClient.http.sse(http).collect { ev ->
            if (ev.data == "[DONE]") return@collect
            val obj = runCatching { Json.parseToJsonElement(ev.data).jsonObject }.getOrNull() ?: return@collect
            obj["error"]?.let { err ->
                val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
                throw LlmException(msg, 0, ev.data)
            }
            obj["usage"]?.let { u ->
                if (u is JsonObject) {
                    val i = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                    val o = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                    if (i + o > 0) emit(LlmEvent.Usage(i, o))
                }
            }
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@collect
            choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finish = it }
            val delta = (choice["delta"] ?: choice["message"]) as? JsonObject ?: return@collect
            delta.str("reasoning_content")?.let { if (it.isNotEmpty()) emit(LlmEvent.Reasoning(it)) }
            delta.str("reasoning")?.let { if (it.isNotEmpty()) emit(LlmEvent.Reasoning(it)) }
            delta.str("content")?.let { if (it.isNotEmpty()) emit(LlmEvent.Text(it)) }
            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { pos, el ->
                val tc = el.jsonObject
                val index = tc["index"]?.jsonPrimitive?.intOrNull ?: pos
                val part = calls.getOrPut(index) { PartialCall() }
                tc.str("id")?.let { if (it.isNotEmpty()) part.id = it }
                (tc["function"] as? JsonObject)?.let { fn ->
                    fn.str("name")?.let { if (it.isNotEmpty()) part.name = it }
                    fn.str("arguments")?.let { part.args.append(it) }
                }
            }
        }
        val complete = calls.values.filter { it.name.isNotEmpty() }.mapIndexed { i, p ->
            ToolCall(p.id.ifEmpty { "call_${System.nanoTime()}_$i" }, p.name, p.args.toString().ifBlank { "{}" })
        }
        if (complete.isNotEmpty()) emit(LlmEvent.ToolCalls(complete))
        emit(LlmEvent.Finish(finish))
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(requestBuilder("$base/models").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                val root = Json.parseToJsonElement(r.body?.string().orEmpty())
                val arr = (root as? JsonObject)?.get("data") as? JsonArray ?: (root as? JsonArray) ?: return@use emptyList()
                arr.mapNotNull { (it as? JsonObject)?.str("id") }.sorted()
            }
        }.getOrDefault(emptyList())
    }

    private fun requestBuilder(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        if (apiKey.isNotBlank()) b.header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    internal fun buildBody(req: ChatRequest): JsonObject = buildJsonObject {
        put("model", req.model)
        put("stream", true)
        req.temperature?.let { put("temperature", it) }
        if (req.think) when (reasoning) {
            ReasoningStyle.EFFORT -> put("reasoning_effort", "medium")
            ReasoningStyle.OPENROUTER -> putJsonObject("reasoning") { put("effort", "medium") }
            ReasoningStyle.NONE -> Unit
        }
        put("messages", encodeMessages(req.system, req.messages))
        if (req.tools.isNotEmpty()) {
            putJsonArray("tools") {
                req.tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        }
                    })
                }
            }
        }
    }

    private class PartialCall {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun encodeMessages(system: String, messages: List<Msg>): JsonArray = buildJsonArray {
            if (system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
            // Images returned by tools cannot ride on a tool message here, so they follow as a
            // user message once every pending tool result has been sent.
            val pendingImages = mutableListOf<ImagePart>()
            fun flushImages() {
                if (pendingImages.isEmpty()) return
                add(userContent("Images returned by the tools above:", pendingImages.toList()))
                pendingImages.clear()
            }
            for (m in messages) {
                if (m.role != Role.TOOL) flushImages()
                when (m.role) {
                    Role.USER -> add(userContent(m.text, m.images))
                    Role.ASSISTANT -> add(buildJsonObject {
                        put("role", "assistant")
                        put("content", m.text)
                        if (m.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                            m.toolCalls.forEach { c ->
                                add(buildJsonObject {
                                    put("id", c.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", c.name)
                                        put("arguments", c.arguments)
                                    }
                                })
                            }
                        }
                    })
                    Role.TOOL -> {
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", m.toolCallId ?: "")
                            put("content", m.text.ifEmpty { "(no output)" })
                        })
                        pendingImages += m.images
                    }
                }
            }
            flushImages()
        }

        private fun userContent(text: String, images: List<ImagePart>): JsonObject = buildJsonObject {
            put("role", "user")
            if (images.isEmpty()) {
                put("content", text)
            } else {
                putJsonArray("content") {
                    if (text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", text) })
                    images.forEach { img ->
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:${img.mime};base64,${img.base64}") }
                        })
                    }
                }
            }
        }
    }
}

internal val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
