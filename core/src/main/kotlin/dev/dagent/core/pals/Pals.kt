package dev.dagent.core.pals

import dev.dagent.core.llm.Sampling
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Which brain a Pal prefers. Empty fields mean "use whatever the app is set to". */
@Serializable
data class PalModel(
    /** An AI provider id from Settings (e.g. "freellmapi", "lmstudio", "local"). */
    val provider: String = "",
    /** A model name for that provider. */
    val model: String = "",
    /** An on-phone model: a catalog id ("qwen35-4b") or a downloaded .gguf file name. */
    val local: String = "",
)

/**
 * A Pal: a ready-made companion with its own job, personality, starter questions and,
 * optionally, its own model, word-picking settings and voice. Shared as a small JSON file
 * (.dpal) or through D's Hub.
 */
@Serializable
data class Pal(
    val id: String,
    val name: String,
    val tagline: String = "",
    val emoji: String = "✨",
    /** Card color as 0xAARRGGBB. */
    val color: Long = 0xFF2A2F3A,
    /** What this Pal does and how. Goes into its system prompt. */
    val instructions: String = "",
    /** One of the persona styles (witty, warm, straight) or empty to keep the app's. */
    val style: String = "",
    val starters: List<String> = emptyList(),
    /** A built-in character id to show as its face, or empty. */
    val character: String = "",
    val model: PalModel? = null,
    val sampling: Sampling? = null,
    /** On-phone voice pack and speaker number, or empty to keep the app's voice. */
    val voicePack: String = "",
    val voiceId: Int? = null,
    /** Wants the camera (live video help). */
    val camera: Boolean = false,
    val tags: List<String> = emptyList(),
    val author: String = "D-Agent",
    val version: Int = 1,
    val builtin: Boolean = false,
)

/** D's Hub list: what the app and the website read. */
@Serializable
data class HubIndex(val updated: String = "", val pals: List<Pal> = emptyList())

object PalFormat {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false; explicitNulls = false }

    const val EXT = "dpal"
    private const val MAX_TEXT = 12_000

    fun encode(pal: Pal): String = json.encodeToString(Pal.serializer(), pal.copy(builtin = false))

    /** Reads a shared Pal and makes it safe to keep: trims sizes, drops the built-in flag. */
    fun decode(text: String): Pal {
        val p = json.decodeFromString(Pal.serializer(), text.trim())
        require(p.name.isNotBlank()) { "This Pal has no name." }
        return clean(p)
    }

    fun clean(p: Pal): Pal = p.copy(
        id = p.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(60).ifBlank { "pal-" + UUID.randomUUID().toString().take(8) },
        name = p.name.trim().take(40),
        tagline = p.tagline.trim().take(160),
        emoji = p.emoji.take(8),
        instructions = p.instructions.take(MAX_TEXT),
        starters = p.starters.map { it.take(200) }.filter { it.isNotBlank() }.take(6),
        tags = p.tags.map { it.take(24) }.take(8),
        builtin = false,
    )

    fun decodeHub(text: String): HubIndex = json.decodeFromString(HubIndex.serializer(), text).let { h ->
        h.copy(pals = h.pals.mapNotNull { runCatching { clean(it) }.getOrNull() })
    }
}

/** Your Pals: the built-in ones plus any you made or installed, one JSON file each. */
class PalStore(private val dir: File) {
    init { dir.mkdirs() }

    fun mine(): List<Pal> = dir.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { f ->
        runCatching { PalFormat.json.decodeFromString(Pal.serializer(), f.readText()) }.getOrNull()
    }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun all(): List<Pal> = BuiltinPals.all + mine()

    fun get(id: String): Pal? = all().firstOrNull { it.id == id }

    fun save(pal: Pal): Pal {
        val p = PalFormat.clean(pal).let { if (BuiltinPals.all.any { b -> b.id == it.id }) it.copy(id = it.id + "-mine") else it }
        File(dir, "${p.id}.json").writeText(PalFormat.encode(p))
        return p
    }

    /** Adds a shared Pal; if one with the same id exists and differs, keeps both. */
    fun install(pal: Pal): Pal {
        val existing = get(pal.id)
        val p = if (existing != null && existing != pal && !existing.builtin && existing.version >= pal.version) pal.copy(id = pal.id + "-" + UUID.randomUUID().toString().take(4)) else pal
        return save(p)
    }

    fun delete(id: String) { File(dir, "$id.json").delete() }

    fun installed(id: String) = mine().any { it.id == id }
}
