package dev.dagent.core.agent

import dev.dagent.core.llm.ChatRequest
import dev.dagent.core.llm.LlmClient
import dev.dagent.core.llm.LlmEvent
import dev.dagent.core.llm.LlmException
import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.Role
import dev.dagent.core.llm.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class AgentOptions(
    val maxSteps: Int = 40,
    val think: Boolean = false,
    val temperature: Double? = null,
    val maxTokens: Int = 8192,
    /** false: describe tools in the prompt and parse <tool_call> tags (for small local models). */
    val nativeTools: Boolean = true,
    /** Rough size, in characters, the conversation may reach before old parts are trimmed. */
    val contextChars: Int = 240_000,
    /** How many times a tool may be called with identical arguments in one run. */
    val repeatLimit: Int = 3,
)

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class ToolStart(val call: ToolCall, val title: String) : AgentEvent
    data class ToolEnd(val call: ToolCall, val result: ToolResult, val millis: Long) : AgentEvent
    /** A message to add to the transcript (an assistant turn or a tool result). */
    data class Message(val msg: Msg) : AgentEvent
    data class Usage(val input: Int, val output: Int) : AgentEvent
    data class Notice(val text: String) : AgentEvent
}

/**
 * The reason, act, observe loop. Each step streams one model reply; if it asks for tools they
 * run one after another, their results go back in, and the loop goes on until the model answers
 * without calling anything, the step limit is hit, or the collector cancels.
 */
class AgentLoop(
    private val client: LlmClient,
    private val model: String,
    private val tools: List<Tool>,
    private val options: AgentOptions = AgentOptions(),
) {
    private val byName = tools.associateBy { it.name }
    private var nativeTools = options.nativeTools

    fun run(system: String, history: List<Msg>, ctx: ToolContext): Flow<AgentEvent> = flow {
        val transcript = history.toMutableList()
        val seen = HashMap<String, Int>()
        var step = 0
        while (true) {
            step++
            val lastStep = step >= options.maxSteps
            val offerTools = tools.isNotEmpty() && !lastStep
            val sys = buildString {
                append(system)
                if (offerTools && !nativeTools) append("\n\n").append(TextToolProtocol.manual(tools))
                if (lastStep) append("\n\nYou have used every step you are allowed for this request. Do not call tools. Tell the person what you finished, what is left, and how to continue.")
            }
            val sent = Context.fit(transcript, options.contextChars)
            if (sent.trimmed) emit(AgentEvent.Notice("Older parts of this chat were shortened to fit the model's memory."))
            val request = ChatRequest(
                model = model,
                system = sys,
                messages = if (nativeTools) sent.messages else TextToolProtocol.flatten(sent.messages),
                tools = if (offerTools && nativeTools) tools.map { it.spec() } else emptyList(),
                temperature = options.temperature,
                maxTokens = options.maxTokens,
                think = options.think,
            )
            val reply = streamWithRetry(request, offerTools) ?: continue
            transcript += reply
            emit(AgentEvent.Message(reply))
            if (reply.toolCalls.isEmpty()) break

            for (call in reply.toolCalls) {
                val result = runTool(call, ctx, seen)
                val msg = Msg.tool(call, clip(result.text), result.images)
                transcript += msg
                emit(AgentEvent.Message(msg))
            }
        }
    }

    /** Streams one reply. Returns null when it switched tool mode and the step should be redone. */
    private suspend fun FlowCollector<AgentEvent>.streamWithRetry(request: ChatRequest, offerTools: Boolean): Msg? {
        var attempt = 0
        while (true) {
            try {
                return streamOnce(request, offerTools)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                if (nativeTools && offerTools && e.toolsUnsupported) {
                    nativeTools = false
                    emit(AgentEvent.Notice("This model does not take tools natively, so D-Agent is describing them in the prompt instead."))
                    return null
                }
                val msg = e.message.orEmpty()
                val stalled = e.status == 0 && (msg.contains("timeout", true) || msg.contains("timed out", true))
                val refused = e.status == 0 && (msg.contains("refused", true) || msg.contains("failed to connect", true))
                val retryable = e.status == 429 || e.status >= 500 || e.status == 0 && msg.startsWith("Network") && !refused
                // A stalled model gets one more try, not two: each try can take the full idle limit.
                if (!retryable || attempt >= (if (stalled) 1 else 2)) throw e
                attempt++
                emit(AgentEvent.Notice("The provider hiccuped (${e.status.takeIf { it > 0 } ?: "network"}). Retrying..."))
                delay(1500L * attempt * attempt)
            }
        }
    }

    private suspend fun FlowCollector<AgentEvent>.streamOnce(request: ChatRequest, offerTools: Boolean): Msg {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var calls = emptyList<ToolCall>()
        val filter = if (!nativeTools && offerTools) TextToolProtocol.StreamFilter() else null
        // Some models write their thinking inline between <think> tags.
        val think = ThinkSplitter()
        client.stream(request).collect { ev ->
            when (ev) {
                is LlmEvent.Text -> {
                    text.append(ev.delta)
                    val (visible, thought) = think.feed(ev.delta)
                    if (thought.isNotEmpty()) { reasoning.append(thought); emit(AgentEvent.ReasoningDelta(thought)) }
                    val shown = filter?.feed(visible) ?: visible
                    if (shown.isNotEmpty()) emit(AgentEvent.TextDelta(shown))
                }
                is LlmEvent.Reasoning -> { reasoning.append(ev.delta); emit(AgentEvent.ReasoningDelta(ev.delta)) }
                is LlmEvent.ToolCalls -> calls = ev.calls
                is LlmEvent.Usage -> emit(AgentEvent.Usage(ev.input, ev.output))
                is LlmEvent.Finish -> Unit
            }
        }
        filter?.flush()?.let { if (it.isNotEmpty()) emit(AgentEvent.TextDelta(it)) }
        var finalText = ThinkSplitter.strip(text.toString())
        if (calls.isEmpty() && offerTools) {
            // Models without native tools (and some with) write calls as text.
            val parsed = TextToolProtocol.parse(finalText)
            if (parsed.calls.isNotEmpty()) {
                calls = parsed.calls
                finalText = parsed.text
            }
        }
        return Msg.assistant(finalText.trim(), calls, reasoning.toString())
    }

    private suspend fun FlowCollector<AgentEvent>.runTool(call: ToolCall, ctx: ToolContext, seen: HashMap<String, Int>): ToolResult {
        val tool = byName[call.name]
        val parsed: JsonObject? = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject }.getOrNull()
        val title = (parsed?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: defaultTitle(call.name)
        emit(AgentEvent.ToolStart(call, title))
        val start = System.currentTimeMillis()
        val key = call.name + "\u0000" + (parsed?.let { JsonObject(it - "title").toString() } ?: call.arguments)
        val count = (seen[key] ?: 0) + 1
        seen[key] = count
        val result = when {
            tool == null -> ToolResult.error("There is no tool named \"${call.name}\". Available: ${byName.keys.joinToString()}.")
            parsed == null -> ToolResult.error("The arguments were not valid JSON: ${call.arguments.take(300)}. Call the tool again with a JSON object.")
            count > options.repeatLimit -> ToolResult.error("You already made this exact call ${count - 1} times and got the same result. Try a different approach or tell the person what is blocking you.")
            else -> try {
                tool.run(Args(parsed), ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                ToolResult.error("Bad arguments: ${e.message}")
            } catch (e: Throwable) {
                ToolResult.error("${e::class.simpleName}: ${e.message}")
            }
        }
        emit(AgentEvent.ToolEnd(call, result, System.currentTimeMillis() - start))
        return if (result.isError && !result.text.startsWith("Error")) result.copy(text = "Error: ${result.text}") else result
    }

    private fun defaultTitle(name: String) = name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Keeps the conversation inside a size budget without breaking tool call / result pairs. */
object Context {
    data class Fitted(val messages: List<Msg>, val trimmed: Boolean)

    fun size(m: Msg): Int = m.text.length + m.toolCalls.sumOf { it.arguments.length + 40 } + m.images.size * 3_000 + 20

    fun fit(messages: List<Msg>, budget: Int): Fitted {
        if (messages.sumOf(::size) <= budget) return Fitted(messages, false)
        // 1. Shorten old tool outputs, keeping the last 6 messages whole.
        val keepWhole = 6
        val shortened = messages.mapIndexed { i, m ->
            if (i < messages.size - keepWhole && m.role == Role.TOOL && m.text.length > 600) {
                m.copy(text = m.text.take(400) + "\n[... older output shortened ...]", images = emptyList())
            } else if (i < messages.size - keepWhole && m.images.isNotEmpty()) m.copy(images = emptyList()) else m
        }
        if (shortened.sumOf(::size) <= budget) return Fitted(shortened, true)
        // 2. Drop whole turns from the start (a turn begins at a user message).
        val starts = shortened.indices.filter { shortened[it].role == Role.USER }
        for (s in starts.drop(1)) {
            val rest = shortened.subList(s, shortened.size)
            if (rest.sumOf(::size) <= budget) {
                val note = Msg.user("[Earlier messages in this chat were removed to save space. Ask if you need something from them.]")
                return Fitted(listOf(note) + rest, true)
            }
        }
        // 3. Even the last turn is too big: keep it, with every tool output cut hard.
        val lastStart = starts.lastOrNull() ?: 0
        val tail = shortened.subList(lastStart, shortened.size).map {
            if (it.role == Role.TOOL) it.copy(text = clip(it.text, 2_000)) else it.copy(text = clip(it.text, 20_000))
        }
        return Fitted(tail, true)
    }
}

/** Separates `<think>...</think>` blocks that some models stream inside their text. */
class ThinkSplitter {
    private var inThink = false
    private val pending = StringBuilder()

    /** Returns (visible text, thinking text) for this chunk. */
    fun feed(chunk: String): Pair<String, String> {
        pending.append(chunk)
        val visible = StringBuilder()
        val thought = StringBuilder()
        while (true) {
            val s = pending.toString()
            val tag = if (inThink) "</think>" else "<think>"
            val i = s.indexOf(tag)
            if (i >= 0) {
                (if (inThink) thought else visible).append(s, 0, i)
                pending.delete(0, i + tag.length)
                inThink = !inThink
                continue
            }
            // Hold back a possible partial tag at the end.
            val hold = (1 until tag.length).lastOrNull { s.endsWith(tag.substring(0, it)) } ?: 0
            (if (inThink) thought else visible).append(s, 0, s.length - hold)
            pending.delete(0, s.length - hold)
            break
        }
        return visible.toString() to thought.toString()
    }

    companion object {
        fun strip(text: String): String = text.replace(Regex("<think>[\\s\\S]*?(</think>|$)"), "").trimStart()
    }
}
