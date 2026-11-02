package dev.dagent.core.agent

import dev.dagent.core.llm.ImagePart
import dev.dagent.core.llm.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** What a tool hands back to the model. [files] are shown to the person as attachments. */
data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    val images: List<ImagePart> = emptyList(),
    val files: List<File> = emptyList(),
) {
    companion object {
        fun ok(text: String, files: List<File> = emptyList()) = ToolResult(text, files = files)
        fun error(text: String) = ToolResult(text, isError = true)
    }
}

/** Facts a tool may need about the run it is part of. */
interface ToolContext {
    val sessionId: String
    /** Shows progress text under the running tool card. */
    fun progress(text: String) {}
    /** Asks the person to approve something risky; true when they allow it. */
    suspend fun confirm(title: String, detail: String): Boolean = true
}

interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    suspend fun run(args: Args, ctx: ToolContext): ToolResult

    fun spec() = ToolSpec(name, description, parameters)
}

/** Typed, forgiving access to the arguments a model produced. */
class Args(val raw: JsonObject) {
    fun string(key: String): String? = when (val v = raw[key]) {
        null -> null
        is JsonPrimitive -> v.contentOrNull
        else -> v.toString()
    }
    fun requireString(key: String): String = string(key)?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Missing required argument \"$key\"")
    fun int(key: String): Int? = (raw[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }
    fun double(key: String): Double? = (raw[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    fun bool(key: String): Boolean? = (raw[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.let { s -> s == "true" || s == "yes" } }
    fun list(key: String): List<JsonElement> = when (val v = raw[key]) {
        is JsonArray -> v
        is JsonPrimitive -> v.contentOrNull?.let { s ->
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(s) as? JsonArray }.getOrNull()
                ?: s.split(',').map { JsonPrimitive(it.trim()) }
        } ?: emptyList()
        else -> emptyList()
    }
    fun stringList(key: String): List<String> = list(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
}

/** A small builder for JSON Schema objects, so tool definitions stay readable. */
class SchemaBuilder {
    private val props = linkedMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, required, buildJsonObject {
            put("type", "string")
            put("description", description)
            if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
        })

    fun integer(name: String, description: String, required: Boolean = false) =
        add(name, required, buildJsonObject { put("type", "integer"); put("description", description) })

    fun number(name: String, description: String, required: Boolean = false) =
        add(name, required, buildJsonObject { put("type", "number"); put("description", description) })

    fun boolean(name: String, description: String, required: Boolean = false) =
        add(name, required, buildJsonObject { put("type", "boolean"); put("description", description) })

    fun stringArray(name: String, description: String, required: Boolean = false) =
        add(name, required, buildJsonObject {
            put("type", "array")
            put("description", description)
            putJsonObject("items") { put("type", "string") }
        })

    fun raw(name: String, schema: JsonObject, required: Boolean = false) = add(name, required, schema)

    private fun add(name: String, req: Boolean, schema: JsonObject) {
        props[name] = schema
        if (req) required += name
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

/** Every tool asks for a short title so the chat can show what is happening in plain words. */
fun SchemaBuilder.title() = string("title", "A 3 to 8 word summary of this step, shown to the person (e.g. \"Search flights to Denver\").")

/** Cuts long output in the middle so both the start and the end survive. */
fun clip(text: String, max: Int = 12_000): String {
    if (text.length <= max) return text
    val head = text.take(max * 2 / 3)
    val tail = text.takeLast(max / 3)
    return "$head\n\n[... ${text.length - head.length - tail.length} characters cut ...]\n\n$tail"
}

/**
 * A lighter copy of a tool for small on-phone models: one-sentence description and no
 * per-field descriptions, so the prompt the phone has to read is a fraction of the size.
 */
class SlimTool(private val inner: Tool) : Tool by inner {
    override val description: String = inner.description.let { d ->
        val cut = Regex("[.!?](\\s|$)").find(d)?.range?.first?.plus(1) ?: d.length
        d.take(minOf(cut, 180)).trim()
    }
    override val parameters: JsonObject = slim(inner.parameters)

    companion object {
        fun slim(schema: JsonObject): JsonObject {
            val props = schema["properties"] as? JsonObject ?: return schema
            val kept = props.filterKeys { it != "title" }.mapValues { (_, v) ->
                val o = v as? JsonObject ?: return@mapValues v
                JsonObject(o.filterKeys { it != "description" })
            }
            val req = (schema["required"] as? JsonArray)?.filter { (it as? JsonPrimitive)?.contentOrNull != "title" }
            return buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(kept))
                if (!req.isNullOrEmpty()) put("required", JsonArray(req))
            }
        }

        /** Tools worth their prompt space on a phone-sized model. */
        val PHONE_CHAT = setOf("web_search", "web_fetch", "weather", "places", "phone", "remember", "recall", "make_image", "make_website", "calculate")
        val PHONE_AGENT = PHONE_CHAT + setOf("shell", "browser", "file_read", "file_write", "file_list", "schedule", "termux", "ai_call", "reverse")
    }
}
