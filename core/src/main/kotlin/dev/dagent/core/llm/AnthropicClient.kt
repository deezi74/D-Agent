package dev.dagent.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Anthropic's Messages API with streaming, tool use, images and extended thinking. */
class AnthropicClient(
    private val http: OkHttpClient,
    baseUrl: String,
    private val apiKey: String,
) : LlmClient {
    private val base = baseUrl.trimEnd('/').removeSuffix("/v1")

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        val req = builder("$base/v1/messages")
            .header("Accept", "text/event-stream")
            .post(buildBody(request).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val blocks = sortedMapOf<Int, Block>()
        var finish = "end_turn"
        var inputTokens = 0
        http.sse(req).collect { ev ->
            val obj = runCatching { Json.parseToJsonElement(ev.data).jsonObject }.getOrNull() ?: return@collect
            when (obj.str("type")) {
                "message_start" -> {
                    val usage = (obj["message"] as? JsonObject)?.get("usage") as? JsonObject
                    inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                }
                "content_block_start" -> {
                    val index = obj["index"]?.jsonPrimitive?.intOrNull ?: return@collect
                    val cb = obj["content_block"] as? JsonObject ?: return@collect
                    val b = Block(cb.str("type") ?: "text", cb.str("id") ?: "", cb.str("name") ?: "")
                    blocks[index] = b
                    cb.str("text")?.let { if (it.isNotEmpty()) emit(LlmEvent.Text(it)) }
                }
                "content_block_delta" -> {
                    val index = obj["index"]?.jsonPrimitive?.intOrNull ?: return@collect
                    val d = obj["delta"] as? JsonObject ?: return@collect
                    when (d.str("type")) {
                        "text_delta" -> d.str("text")?.let { emit(LlmEvent.Text(it)) }
                        "thinking_delta" -> d.str("thinking")?.let { emit(LlmEvent.Reasoning(it)) }
                        "input_json_delta" -> blocks[index]?.json?.append(d.str("partial_json") ?: "")
                    }
                }
                "message_delta" -> {
                    (obj["delta"] as? JsonObject)?.str("stop_reason")?.let { finish = it }
                    val out = ((obj["usage"] as? JsonObject)?.get("output_tokens"))?.jsonPrimitive?.intOrNull ?: 0
                    if (out > 0) emit(LlmEvent.Usage(inputTokens, out))
                }
                "error" -> {
                    val msg = (obj["error"] as? JsonObject)?.str("message") ?: ev.data
                    throw LlmException(msg, 0, ev.data)
                }
            }
        }
        val calls = blocks.values.filter { it.type == "tool_use" }.map {
            ToolCall(it.id, it.name, it.json.toString().ifBlank { "{}" })
        }
        if (calls.isNotEmpty()) emit(LlmEvent.ToolCalls(calls))
        emit(LlmEvent.Finish(finish))
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(builder("$base/v1/models?limit=100").get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                val data = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["data"] as? JsonArray
                data?.mapNotNull { (it as? JsonObject)?.str("id") } ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun builder(url: String) = Request.Builder().url(url)
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")

    internal fun buildBody(req: ChatRequest): JsonObject = buildJsonObject {
        put("model", req.model)
        put("stream", true)
        val budget = 4096
        put("max_tokens", if (req.think) maxOf(req.maxTokens, budget + 4096) else req.maxTokens)
        if (req.think) putJsonObject("thinking") { put("type", "enabled"); put("budget_tokens", budget) }
        else req.temperature?.let { put("temperature", it) }
        if (req.system.isNotBlank()) put("system", req.system)
        put("messages", encodeMessages(req.messages))
        if (req.tools.isNotEmpty()) putJsonArray("tools") {
            req.tools.forEach { t ->
                add(buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("input_schema", t.parameters)
                })
            }
        }
    }

    private class Block(val type: String, val id: String, val name: String) {
        val json = StringBuilder()
    }

    companion object {
        /** Anthropic needs strictly alternating roles; tool results ride in a user turn. */
        fun encodeMessages(messages: List<Msg>): JsonArray {
            val turns = mutableListOf<Pair<String, MutableList<JsonElement>>>()
            fun push(role: String, block: JsonElement) {
                val last = turns.lastOrNull()
                if (last != null && last.first == role) last.second += block else turns += role to mutableListOf(block)
            }
            for (m in messages) when (m.role) {
                Role.USER -> {
                    m.images.forEach { push("user", image(it)) }
                    push("user", text(m.text.ifEmpty { "(image)" }))
                }
                Role.ASSISTANT -> {
                    if (m.text.isNotBlank()) push("assistant", text(m.text))
                    m.toolCalls.forEach { c ->
                        val input = runCatching { Json.parseToJsonElement(c.arguments) as? JsonObject }.getOrNull()
                            ?: buildJsonObject { }
                        push("assistant", buildJsonObject {
                            put("type", "tool_use")
                            put("id", c.id)
                            put("name", c.name)
                            put("input", input)
                        })
                    }
                    if (m.text.isBlank() && m.toolCalls.isEmpty()) push("assistant", text("(no reply)"))
                }
                Role.TOOL -> push("user", buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", m.toolCallId ?: "")
                    putJsonArray("content") {
                        add(text(m.text.ifEmpty { "(no output)" }))
                        m.images.forEach { add(image(it)) }
                    }
                })
            }
            return buildJsonArray {
                turns.forEach { (role, blocks) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("content", JsonArray(blocks))
                    })
                }
            }
        }

        private fun text(t: String) = buildJsonObject { put("type", "text"); put("text", t) }
        private fun image(i: ImagePart) = buildJsonObject {
            put("type", "image")
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", i.mime)
                put("data", i.base64)
            }
        }
    }
}
