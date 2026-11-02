package dev.dagent.core.agent

import dev.dagent.core.llm.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Liquid's LFM2/LFM2.5 models write tool calls as a Python list between special tokens:
 * `<|tool_call_start|>[web_fetch(url='https://x.com'), weather(place="Bay St. Louis")]<|tool_call_end|>`.
 * When the server doesn't turn that into a native call, this reads it so it runs instead of showing up as text.
 */
object PythonicCalls {
    const val OPEN = "<|tool_call_start|>"
    const val CLOSE = "<|tool_call_end|>"

    fun has(text: String) = text.contains(OPEN)

    /** The text with every call block removed, and the calls found in them. */
    fun parse(text: String): TextToolProtocol.Parsed {
        val calls = mutableListOf<ToolCall>()
        val rest = StringBuilder()
        var i = 0
        while (i < text.length) {
            val s = text.indexOf(OPEN, i)
            if (s < 0) { rest.append(text, i, text.length); break }
            rest.append(text, i, s)
            val e = text.indexOf(CLOSE, s + OPEN.length).let { if (it < 0) text.length else it }
            calls += parseList(text.substring(s + OPEN.length, e), calls.size)
            i = if (e >= text.length) text.length else e + CLOSE.length
        }
        return TextToolProtocol.Parsed(rest.toString().trim(), calls)
    }

    /** `[name(a=1, b='x'), other()]` (the brackets are optional). */
    fun parseList(src: String, start: Int = 0): List<ToolCall> {
        val p = Reader(src.trim().removePrefix("[").removeSuffix("]"))
        val out = mutableListOf<ToolCall>()
        while (true) {
            p.skip(',')
            if (p.done()) break
            val name = p.ident() ?: break
            p.ws()
            if (!p.eat('(')) break
            val args = LinkedHashMap<String, JsonElement>()
            while (true) {
                p.skip(',')
                if (p.eat(')') || p.done()) break
                val key = p.ident() ?: break
                p.ws()
                if (!p.eat('=')) break
                args[key] = p.value() ?: break
            }
            out += ToolCall("lfm_call_${System.nanoTime()}_${start + out.size}", name, JsonObject(args).toString())
        }
        return out
    }

    private class Reader(val s: String) {
        var i = 0
        fun done(): Boolean { ws(); return i >= s.length }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun skip(c: Char) { ws(); while (i < s.length && (s[i] == c || s[i].isWhitespace())) i++ }
        fun eat(c: Char): Boolean { ws(); return if (i < s.length && s[i] == c) { i++; true } else false }
        fun ident(): String? {
            ws()
            val b = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.' || s[i] == '-')) i++
            return if (i > b) s.substring(b, i) else null
        }

        fun value(): JsonElement? {
            ws()
            if (i >= s.length) return null
            return when (val c = s[i]) {
                '\'', '"' -> JsonPrimitive(str(c))
                '[', '(' -> list(if (c == '[') ']' else ')')
                '{' -> dict()
                else -> {
                    val b = i
                    while (i < s.length && s[i] != ',' && s[i] != ')' && s[i] != ']' && s[i] != '}') i++
                    when (val w = s.substring(b, i).trim()) {
                        "True", "true" -> JsonPrimitive(true)
                        "False", "false" -> JsonPrimitive(false)
                        "None", "null" -> JsonNull
                        else -> w.toLongOrNull()?.let { JsonPrimitive(it) } ?: w.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(w)
                    }
                }
            }
        }

        fun str(q: Char): String {
            i++
            val sb = StringBuilder()
            while (i < s.length && s[i] != q) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    i++
                    sb.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] })
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }

        fun list(end: Char): JsonElement {
            i++
            val items = mutableListOf<JsonElement>()
            while (true) {
                skip(',')
                if (eat(end) || done()) break
                items += value() ?: break
            }
            return JsonArray(items)
        }

        fun dict(): JsonElement {
            i++
            val m = LinkedHashMap<String, JsonElement>()
            while (true) {
                skip(',')
                if (eat('}') || done()) break
                val k = (value() as? JsonPrimitive)?.content ?: break
                if (!eat(':')) break
                m[k] = value() ?: break
            }
            return JsonObject(m)
        }
    }
}
