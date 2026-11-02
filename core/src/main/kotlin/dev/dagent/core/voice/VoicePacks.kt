package dev.dagent.core.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Free voice packs that run on the phone with no internet once downloaded: talking voices
 * (Kokoro, Kitten) and listening models (Moonshine, Whisper) for the sherpa-onnx engine,
 * plus the Silero voice detector used for hands-free turns and talking over the reply.
 *
 * Files come from the sherpa-onnx model mirrors on Hugging Face, the same host our
 * on-phone chat models already download from.
 */
object VoicePacks {
    enum class Kind { TALK, LISTEN, DETECT }

    data class Voice(val id: Int, val label: String)

    data class Pack(
        val id: String,
        val kind: Kind,
        val name: String,
        val blurb: String,
        val repo: String,
        val files: List<String>,
        val approxMb: Int,
        val ramMb: Int,
        /** Pull the English parts of espeak-ng-data (needed by Kokoro and Kitten). */
        val espeak: Boolean = false,
        val voices: List<Voice> = emptyList(),
    )

    private val KOKORO_VOICES = listOf(
        3 to "Heart (US woman)", 2 to "Bella (US woman)", 6 to "Nicole (US woman, soft)", 7 to "Nova (US woman)",
        9 to "Sarah (US woman)", 10 to "Sky (US woman)", 0 to "Alloy (US woman)", 1 to "Aoede (US woman)",
        4 to "Jessica (US woman)", 5 to "Kore (US woman)", 8 to "River (US woman)",
        16 to "Michael (US man)", 13 to "Eric (US man)", 11 to "Adam (US man)", 12 to "Echo (US man)",
        14 to "Fenrir (US man)", 15 to "Liam (US man)", 17 to "Onyx (US man, deep)", 18 to "Puck (US man)",
        19 to "Santa (US man)",
        21 to "Emma (UK woman)", 20 to "Alice (UK woman)", 22 to "Isabella (UK woman)", 23 to "Lily (UK woman)",
        26 to "George (UK man)", 25 to "Fable (UK man)", 24 to "Daniel (UK man)", 27 to "Lewis (UK man)",
    ).map { (id, label) -> Voice(id, label) }

    private val KITTEN_VOICES = listOf(
        1 to "Bree (woman)", 0 to "Max (man)", 3 to "Ivy (woman)", 2 to "Cole (man)",
        5 to "Rose (woman)", 4 to "Leo (man)", 7 to "Kiki (woman)", 6 to "Hugo (man)",
    ).map { (id, label) -> Voice(id, label) }

    val KOKORO = Pack(
        "kokoro", Kind.TALK, "Kokoro", "Most natural. 28 American and British voices.",
        "csukuangfj/kokoro-int8-multi-lang-v1_0",
        listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt"),
        approxMb = 160, ramMb = 650, espeak = true, voices = KOKORO_VOICES,
    )
    val KITTEN = Pack(
        "kitten", Kind.TALK, "Kitten", "Tiny and quick. 8 voices, good for older phones.",
        "csukuangfj/kitten-nano-en-v0_2-fp16",
        listOf("model.fp16.onnx", "voices.bin", "tokens.txt"),
        approxMb = 26, ramMb = 250, espeak = true, voices = KITTEN_VOICES,
    )
    val KITTEN_MINI = Pack(
        "kitten-mini", Kind.TALK, "Kitten Mini", "Kitten's bigger, clearer model. 8 voices.",
        "csukuangfj/kitten-mini-en-v0_1-fp16",
        listOf("model.fp16.onnx", "voices.bin", "tokens.txt"),
        approxMb = 168, ramMb = 450, espeak = true, voices = KITTEN_VOICES,
    )

    val MOONSHINE_TINY = Pack(
        "moonshine-tiny", Kind.LISTEN, "Moonshine Tiny", "Fastest. Made for phones; great for short questions.",
        "csukuangfj/sherpa-onnx-moonshine-tiny-en-int8",
        listOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt"),
        approxMb = 124, ramMb = 300,
    )
    val MOONSHINE_BASE = Pack(
        "moonshine-base", Kind.LISTEN, "Moonshine Base", "More accurate, still fast.",
        "csukuangfj/sherpa-onnx-moonshine-base-en-int8",
        listOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt"),
        approxMb = 287, ramMb = 600,
    )
    val WHISPER_BASE = Pack(
        "whisper-base", Kind.LISTEN, "Whisper Base", "Handles accents and noise well.",
        "csukuangfj/sherpa-onnx-whisper-base.en",
        listOf("base.en-encoder.int8.onnx", "base.en-decoder.int8.onnx", "base.en-tokens.txt"),
        approxMb = 160, ramMb = 450,
    )
    val WHISPER_SMALL = Pack(
        "whisper-small", Kind.LISTEN, "Whisper Small", "Most accurate. Slower on most phones.",
        "csukuangfj/sherpa-onnx-whisper-small.en",
        listOf("small.en-encoder.int8.onnx", "small.en-decoder.int8.onnx", "small.en-tokens.txt"),
        approxMb = 376, ramMb = 900,
    )
    val SILERO = Pack(
        "silero-vad", Kind.DETECT, "Voice detector", "Hears when you start and stop talking.",
        "csukuangfj/vad", listOf("silero_vad.onnx"), approxMb = 2, ramMb = 20,
    )

    val all = listOf(KOKORO, KITTEN, KITTEN_MINI, MOONSHINE_TINY, MOONSHINE_BASE, WHISPER_BASE, WHISPER_SMALL, SILERO)
    val talk get() = all.filter { it.kind == Kind.TALK }
    val listen get() = all.filter { it.kind == Kind.LISTEN }

    fun byId(id: String): Pack? = all.firstOrNull { it.id == id }

    /** espeak-ng-data minus the other languages' dictionaries (keeps it about 2 MB). */
    fun wantEspeak(path: String): Boolean {
        if (!path.startsWith("espeak-ng-data/")) return false
        val name = path.substringAfterLast('/')
        return !name.endsWith("_dict") || name == "en_dict"
    }

    /** Files in a Hugging Face `tree?recursive=true` listing. */
    fun parseTree(json: String): List<Pair<String, Long>> =
        (Json.parseToJsonElement(json) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if ((o["type"] as? JsonPrimitive)?.content != "file") return@mapNotNull null
            val path = (o["path"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            path to ((o["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
        }
}

/** Downloads and tracks [VoicePacks] under [root]/<pack id>. */
class VoicePackStore(
    private val root: File,
    private val http: OkHttpClient,
    private val hub: String = "https://huggingface.co",
) {
    fun dir(pack: VoicePacks.Pack) = File(root, pack.id)
    fun installed(pack: VoicePacks.Pack) = File(dir(pack), ".complete").exists()
    fun installed(id: String) = VoicePacks.byId(id)?.let { installed(it) } == true

    fun delete(pack: VoicePacks.Pack) { dir(pack).deleteRecursively() }

    /** Every file the pack needs, relative to its folder. */
    fun plan(pack: VoicePacks.Pack): List<String> {
        if (!pack.espeak) return pack.files
        val tree = get("$hub/api/models/${pack.repo}/tree/main/espeak-ng-data?recursive=true").use { it.body!!.string() }
        val espeak = VoicePacks.parseTree(tree).map { it.first }.filter(VoicePacks::wantEspeak)
        if (espeak.none { it.endsWith("phontab") }) throw IOException("Couldn't list the speech dictionary files.")
        return pack.files + espeak
    }

    /**
     * Fetches the pack (skipping files already there) and marks it complete.
     * [progress] gets (files done, files total, name of the current file).
     */
    fun install(pack: VoicePacks.Pack, progress: (Int, Int, String) -> Unit = { _, _, _ -> }) {
        val dir = dir(pack).apply { mkdirs() }
        val files = plan(pack)
        files.forEachIndexed { i, path ->
            progress(i, files.size, path)
            val out = File(dir, path)
            if (out.exists() && out.length() > 0) return@forEachIndexed
            out.parentFile?.mkdirs()
            val part = File(out.path + ".part")
            get("$hub/${pack.repo}/resolve/main/$path").use { r ->
                r.body!!.byteStream().use { input -> part.outputStream().use { input.copyTo(it, 256 * 1024) } }
            }
            if (!part.renameTo(out)) throw IOException("Couldn't save $path")
        }
        progress(files.size, files.size, "")
        File(dir, ".complete").writeText(files.size.toString())
    }

    private fun get(url: String): okhttp3.Response {
        val r = http.newCall(Request.Builder().url(url).header("User-Agent", "D-Agent").build()).execute()
        if (!r.isSuccessful) {
            r.close()
            throw IOException("Download failed (${r.code}) for ${url.substringAfterLast('/')}")
        }
        return r
    }
}
