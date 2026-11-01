package dev.dagent.core.agent

import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.Role
import dev.dagent.core.llm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Tool calling for models that have no native function calling: the tools are described in
 * the system prompt and the model writes `<tool_call>{"name": ..., "arguments": {...}}</tool_call>`.
 * Also catches models that print calls as text even when native tools are on.
 */
object TextToolProtocol {
    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun manual(tools: List<Tool>): String = buildString {
        append("# Tools\n")
        append("You can call tools. To call one, write exactly:\n")
        append("$OPEN{\"name\": \"tool_name\", \"arguments\": {...}}$CLOSE\n")
        append("You may write several calls in one reply. After your calls, stop and wait: the results come back in the next message inside <tool_result> tags. ")
        append("When you have the final answer, reply normally without any $OPEN.\n\n")
        append("Available tools (arguments are JSON Schema):\n")
        tools.forEach { t ->
            append("- ").append(t.name).append(": ").append(t.description).append('\n')
            append("  arguments: ").append(t.parameters.toString()).append('\n')
        }
    }

    /** Rewrites a native-tools transcript into plain text turns. */
    fun flatten(messages: List<Msg>): List<Msg> {
        val out = mutableListOf<Msg>()
        for (m in messages) when (m.role) {
            Role.USER -> out += m
            Role.ASSISTANT -> {
                val calls = m.toolCalls.joinToString("\n") { c ->
                    "$OPEN{\"name\": ${JsonPrimitive(c.name)}, \"arguments\": ${c.arguments.ifBlank { "{}" }}}$CLOSE"
                }
                out += Msg.assistant(listOf(m.text, calls).filter { it.isNotBlank() }.joinToString("\n"))
            }
            Role.TOOL -> {
                val block = "<tool_result name=\"${m.toolName}\">\n${m.text}\n</tool_result>"
                val last = out.lastOrNull()
                if (last != null && last.role == Role.USER && last.text.startsWith("<tool_result")) {
                    out[out.size - 1] = last.copy(text = last.text + "\n" + block, images = last.images + m.images)
                } else {
                    out += Msg.user(block, m.images)
                }
            }
        }
        return out
    }

    data class Parsed(val text: String, val calls: List<ToolCall>)

    fun parse(text: String): Parsed {
        val calls = mutableListOf<ToolCall>()
        val remaining = StringBuilder()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf(OPEN, i)
            if (start < 0) { remaining.append(text, i, text.length); break }
            remaining.append(text, i, start)
            val end = text.indexOf(CLOSE, start + OPEN.length).let { if (it < 0) text.length else it }
            parseOne(text.substring(start + OPEN.length, end).trim(), calls.size)?.let { calls += it }
            i = if (end >= text.length) text.length else end + CLOSE.length
        }
        // Also accept a fenced JSON block that is clearly a call, which small models often write.
        if (calls.isEmpty()) {
            Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\"name\"[\\s\\S]*?\\})\\s*```").findAll(text).forEach { m ->
                parseOne(m.groupValues[1], calls.size)?.let { calls += it }
            }
            if (calls.isNotEmpty()) return Parsed(text.replace(Regex("```(?:json)?\\s*\\{[\\s\\S]*?\"name\"[\\s\\S]*?\\}\\s*```"), "").trim(), calls)
        }
        return Parsed(remaining.toString().trim(), calls)
    }

    private fun parseOne(body: String, n: Int): ToolCall? {
        val obj = runCatching { lenient.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val args = when (val a = obj["arguments"] ?: obj["parameters"] ?: obj["args"]) {
            null -> "{}"
            is JsonObject -> a.toString()
            is JsonPrimitive -> a.contentOrNull ?: "{}"
            else -> a.toString()
        }
        return ToolCall("text_call_${System.nanoTime()}_$n", name, args)
    }

    /** Hides `<tool_call>` blocks from streamed text so the person sees only prose. */
    class StreamFilter {
        private val buf = StringBuilder()
        private var hiding = false

        fun feed(chunk: String): String {
            buf.append(chunk)
            val out = StringBuilder()
            while (buf.isNotEmpty()) {
                val s = buf.toString()
                if (hiding) {
                    val e = s.indexOf(CLOSE)
                    if (e < 0) {
                        if (buf.length > CLOSE.length) buf.delete(0, buf.length - CLOSE.length)
                        break
                    }
                    buf.delete(0, e + CLOSE.length)
                    hiding = false
                } else {
                    val o = s.indexOf(OPEN)
                    if (o >= 0) {
                        out.append(s, 0, o)
                        buf.delete(0, o + OPEN.length)
                        hiding = true
                    } else {
                        val hold = (1 until OPEN.length).lastOrNull { s.endsWith(OPEN.substring(0, it)) } ?: 0
                        out.append(s, 0, s.length - hold)
                        buf.delete(0, s.length - hold)
                        break
                    }
                }
            }
            return out.toString()
        }

        fun flush(): String = if (hiding) "" else buf.toString().also { buf.setLength(0) }
    }
}
