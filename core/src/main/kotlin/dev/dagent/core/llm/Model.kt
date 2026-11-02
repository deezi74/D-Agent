package dev.dagent.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Who said a message. */
@Serializable
enum class Role { USER, ASSISTANT, TOOL }

/** An image sent to (or produced for) the model, base64 without the data: prefix. */
@Serializable
data class ImagePart(val mime: String, val base64: String)

/** One function call the model asked for. [arguments] is the raw JSON text it produced. */
@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * One message in a conversation, provider-neutral.
 *
 * - USER: [text] and optional [images].
 * - ASSISTANT: [text] (may be empty) plus any [toolCalls]; [reasoning] is kept for display only.
 * - TOOL: the result of one call: [toolCallId], [toolName], [text], optional [images].
 */
@Serializable
data class Msg(
    val role: Role,
    val text: String = "",
    val images: List<ImagePart> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val reasoning: String = "",
) {
    companion object {
        fun user(text: String, images: List<ImagePart> = emptyList()) = Msg(Role.USER, text, images)
        fun assistant(text: String, calls: List<ToolCall> = emptyList(), reasoning: String = "") =
            Msg(Role.ASSISTANT, text, toolCalls = calls, reasoning = reasoning)
        fun tool(call: ToolCall, text: String, images: List<ImagePart> = emptyList()) =
            Msg(Role.TOOL, text, images, toolCallId = call.id, toolName = call.name)
    }
}

/** A tool the model may call. [parameters] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class ChatRequest(
    val model: String,
    val system: String,
    val messages: List<Msg>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int = 8192,
    /** Ask models that support it to think before answering. */
    val think: Boolean = false,
)

/** What a streaming call produces, in order. */
sealed interface LlmEvent {
    data class Text(val delta: String) : LlmEvent
    data class Reasoning(val delta: String) : LlmEvent
    /** Emitted once, after the stream ends, with every complete call. */
    data class ToolCalls(val calls: List<ToolCall>) : LlmEvent
    data class Usage(val input: Int, val output: Int) : LlmEvent
    data class Finish(val reason: String) : LlmEvent
    /** The on-phone server reading the prompt: [done] of [total] tokens. */
    data class Progress(val done: Int, val total: Int) : LlmEvent
}

/** Thrown for a failed request; [status] is the HTTP status when there was one. */
class LlmException(message: String, val status: Int = 0, val body: String = "") : Exception(message) {
    /** True when the server most likely rejected the `tools` field itself. */
    val toolsUnsupported: Boolean
        get() = status in 400..422 && !contextOverflow && Regex("tool|function", RegexOption.IGNORE_CASE).containsMatchIn(body)

    /** True when the conversation was longer than the model's context (LM Studio, llama.cpp, Ollama, OpenAI-style APIs). */
    val contextOverflow: Boolean
        get() = (status == 0 || status in 400..599) && CONTEXT.containsMatchIn((message.orEmpty() + " " + body))

    companion object {
        private val CONTEXT = Regex(
            "context.{0,12}(length|size|window|limit|overflow)|n_ctx|maximum context|context_length_exceeded|too many tokens|prompt is too long|" +
                "tokens to keep|exceeds? the (available )?context|longer than the (model|context)|input is too long|reduce the length",
            RegexOption.IGNORE_CASE,
        )
    }
}

interface LlmClient {
    fun stream(request: ChatRequest): Flow<LlmEvent>

    /** Lists the model ids the endpoint offers; empty when it cannot say. */
    suspend fun listModels(): List<String>
}
