package dev.dagent.character

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** How the agent feels right now; each mood has its own pose of the character. */
enum class Mood(val label: String, val pose: String) {
    IDLE("Resting", "calm and relaxed, gentle smile, looking at the viewer"),
    WORKING("Working", "focused and busy, determined look, leaning in as if typing or building something"),
    HAPPY("Done", "delighted, big bright smile, celebrating with a little cheer"),
    WAITING("Waiting on you", "curious, head tilted, one hand raised as if asking a question"),
    ERROR("Oops", "sheepish and apologetic, awkward smile, scratching head"),
}

data class CharacterStyle(val id: String, val label: String, val phrase: String)

/** A character the person made: one base picture plus a pose per mood. */
data class CharacterLook(
    val id: String,
    val description: String,
    val style: String,
    val dir: File,
) {
    val base: File get() = File(dir, "base.png")
    fun moodFile(m: Mood): File = File(dir, m.name.lowercase() + ".png")
    fun forMood(m: Mood): File = moodFile(m).takeIf { it.isFile } ?: base
    fun posed(): Int = Mood.entries.count { moodFile(it).isFile }
}

/**
 * The character studio: describe a character, get four options in a chosen art style, pick one,
 * and D-Agent draws that character in each mood so its face in the chat reacts to what it is
 * doing. Pictures come from Pollinations (free) or, when an OpenAI key is set, from gpt-image-1,
 * which keeps the same character across moods by editing the picked picture.
 */
class CharacterStudio(
    private val context: Context,
    http: OkHttpClient,
    private val optionsDir: File,
    private val pollinationsKey: () -> String,
    /** Base URL and key of an OpenAI-compatible images API, or null to use Pollinations. */
    private val openAi: () -> Pair<String, String>?,
) {
    sealed interface Slot {
        data object Empty : Slot
        data object Loading : Slot
        data class Ready(val file: File) : Slot
        data class Failed(val message: String) : Slot
    }

    @Serializable
    private data class Meta(val id: String, val description: String, val style: String, val seed: Int)

    private val root = File(context.filesDir, "character").apply { mkdirs() }
    private val looksDir = File(root, "looks").apply { mkdirs() }
    private val activeFile = File(root, "active")
    private val json = Json { ignoreUnknownKeys = true }
    private val slow = http.newBuilder().readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Without a key the free image tier takes about one request at a time; with one, two in flight. */
    private val keyedLane = Semaphore(2)
    private val freeLane = Semaphore(1)
    private val lane: Semaphore get() = if (pollinationsKey().isBlank() && openAi() == null) freeLane else keyedLane

    private val _active = MutableStateFlow(loadActive())
    /** The character in use, or null for the photo / monogram. */
    val active: StateFlow<CharacterLook?> = _active.asStateFlow()

    private val _slots = MutableStateFlow<List<Slot>>(emptyList())
    val slots: StateFlow<List<Slot>> = _slots.asStateFlow()

    /** Status of drawing the mood poses, "" when nothing runs. */
    private val _posing = MutableStateFlow("")
    val posing: StateFlow<String> = _posing.asStateFlow()

    private var request: Triple<String, CharacterStyle, List<Int>>? = null
    private var optionsJob: Job? = null
    private var poseJob: Job? = null

    // ---- making options -------------------------------------------------------------------------

    /** Starts drawing four options; returns the job so a caller (the chat tool) can wait for it. */
    fun makeOptions(description: String, styleId: String): Job {
        val style = style(styleId)
        val seeds = List(OPTIONS) { (Math.random() * 9_000_000).toInt() + 1 }
        request = Triple(description.trim(), style, seeds)
        optionsJob?.cancel()
        _slots.value = List(OPTIONS) { Slot.Loading }
        optionsDir.mkdirs()
        optionsDir.listFiles()?.forEach { it.delete() }
        val stamp = System.currentTimeMillis()
        val job = scope.launch {
            seeds.mapIndexed { i, seed ->
                async {
                    val slot = lane.withPermit {
                        runCatching {
                            val f = File(optionsDir, "option-${i + 1}-$stamp.png")
                            pollinations(prompt(description, style, Mood.IDLE), seed, f)
                            Slot.Ready(f)
                        }.getOrElse { Slot.Failed(it.message ?: "Couldn't draw this one") }
                    }
                    _slots.update { list -> list.toMutableList().also { if (i < it.size) it[i] = slot } }
                }
            }.awaitAll()
        }
        optionsJob = job
        return job
    }

    /** Adopts option [index] (0-based) and starts drawing its mood poses. */
    suspend fun pick(index: Int): CharacterLook = withContext(Dispatchers.IO) {
        val req = request ?: throw IOException("Make some options first.")
        val slot = _slots.value.getOrNull(index) as? Slot.Ready ?: throw IOException("Option ${index + 1} isn't ready.")
        val id = "c" + System.currentTimeMillis()
        val dir = File(looksDir, id).apply { mkdirs() }
        val look = CharacterLook(id, req.first, req.second.id, dir)
        slot.file.copyTo(look.base, overwrite = true)
        slot.file.copyTo(look.moodFile(Mood.IDLE), overwrite = true)
        File(dir, "meta.json").writeText(json.encodeToString(Meta(id, req.first, req.second.id, req.third[index])))
        activate(look)
        pose(look, req.third[index])
        look
    }

    private fun pose(look: CharacterLook, seed: Int) {
        poseJob?.cancel()
        poseJob = scope.launch {
            val todo = Mood.entries.filter { it != Mood.IDLE && !look.moodFile(it).isFile }
            val done = java.util.concurrent.atomic.AtomicInteger()
            _posing.value = "Drawing moods 0/${todo.size}"
            val api = openAi()
            todo.map { mood ->
                async {
                    lane.withPermit {
                        runCatching {
                            if (api != null) openAiEdit(api, look.base, mood, look.moodFile(mood))
                            else pollinations(prompt(look.description, style(look.style), mood), seed, look.moodFile(mood))
                        }
                    }
                    _posing.value = "Drawing moods ${done.incrementAndGet()}/${todo.size}"
                    if (_active.value?.id == look.id) _active.value = look.copy()
                }
            }.awaitAll()
            _posing.value = ""
            if (_active.value?.id == look.id) _active.value = look.copy()
        }
    }

    /** Draws any missing mood poses of the active character again. */
    fun redoMissingMoods() {
        val look = _active.value ?: return
        val seed = runCatching { json.decodeFromString<Meta>(File(look.dir, "meta.json").readText()).seed }.getOrDefault(42)
        pose(look, seed)
    }

    // ---- built-in characters --------------------------------------------------------------------

    data class Preset(val id: String, val name: String, val look: String) {
        /** Asset path of this character's picture for [mood]. */
        fun asset(m: Mood) = "characters/$id/${m.name.lowercase()}.webp"
    }

    /** The ready-made characters shipped in the app (assets/characters). */
    val presets: List<Preset> by lazy {
        runCatching {
            val arr = Json.parseToJsonElement(context.assets.open("characters/index.json").bufferedReader().readText()) as kotlinx.serialization.json.JsonArray
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                Preset(str("id"), str("name"), str("look")).takeIf { p -> runCatching { context.assets.open(p.asset(Mood.IDLE)).close() }.isSuccess }
            }
        }.getOrDefault(emptyList())
    }

    /** Uses a built-in character: copies its five mood pictures into a saved character. */
    suspend fun usePreset(preset: Preset): CharacterLook = withContext(Dispatchers.IO) {
        saved().firstOrNull { it.id == "preset-${preset.id}" }?.let { if (it.posed() == Mood.entries.size) { activate(it); return@withContext it } }
        val id = "preset-${preset.id}"
        val dir = File(looksDir, id).apply { mkdirs() }
        val look = CharacterLook(id, "${preset.name}, ${preset.look}", "toy", dir)
        Mood.entries.forEach { m ->
            runCatching { context.assets.open(preset.asset(m)).use { input -> look.moodFile(m).outputStream().use { input.copyTo(it) } } }
        }
        look.moodFile(Mood.IDLE).copyTo(look.base, overwrite = true)
        File(dir, "meta.json").writeText(json.encodeToString(Meta(id, look.description, "toy", 7)))
        activate(look)
        look
    }

    // ---- saved characters -----------------------------------------------------------------------

    fun saved(): List<CharacterLook> = looksDir.listFiles()?.filter { File(it, "base.png").isFile }
        ?.sortedByDescending { it.name }?.mapNotNull { read(it) } ?: emptyList()

    fun use(look: CharacterLook) = activate(look)

    /** Back to the photo or monogram; saved characters stay. */
    fun clear() {
        activeFile.delete()
        _active.value = null
    }

    fun delete(look: CharacterLook) {
        if (_active.value?.id == look.id) clear()
        look.dir.deleteRecursively()
    }

    private fun activate(look: CharacterLook) {
        activeFile.writeText(look.id)
        _active.value = look
    }

    private fun loadActive(): CharacterLook? {
        val id = runCatching { activeFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return read(File(looksDir, id))?.takeIf { it.base.isFile }
    }

    private fun read(dir: File): CharacterLook? = runCatching {
        val m = json.decodeFromString<Meta>(File(dir, "meta.json").readText())
        CharacterLook(m.id, m.description, m.style, dir)
    }.getOrNull()

    // ---- image services -------------------------------------------------------------------------

    private fun prompt(description: String, style: CharacterStyle, mood: Mood) =
        "Character portrait of $description, ${mood.pose}. ${style.phrase}. Single character, head and shoulders, centered, " +
            "facing the viewer, simple soft background, avatar framing, no text, no watermark"

    /** Pollinations with patient retries: the free tier answers 402/429/503 when it is busy. */
    private fun pollinations(prompt: String, seed: Int, out: File) {
        var wait = 8_000L
        repeat(5) { attempt ->
            try {
                pollinationsOnce(prompt, seed, out)
                if (pollinationsKey().isBlank()) Thread.sleep(6_000)
                return
            } catch (e: IOException) {
                if (attempt == 4 || !e.message.orEmpty().contains("busy")) throw e
                Thread.sleep(wait)
                wait = (wait * 2).coerceAtMost(40_000)
            }
        }
    }

    private fun pollinationsOnce(prompt: String, seed: Int, out: File) {
        val url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
            "?width=768&height=768&model=flux&seed=$seed&nologo=true&private=true"
        val req = Request.Builder().url(url).header("User-Agent", "D-Agent")
        pollinationsKey().trim().takeIf { it.isNotEmpty() }?.let { req.header("Authorization", "Bearer $it") }
        slow.newCall(req.build()).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            if (!r.isSuccessful || !type.startsWith("image")) {
                throw IOException("Image service said HTTP ${r.code}" + if (r.code == 429 || r.code == 402 || r.code >= 500) " (busy; a free Pollinations key in Tools and behavior helps)" else "")
            }
            r.body!!.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }

    /** Same character, new mood: gpt-image-1 edits the picked picture so the face stays the same. */
    private fun openAiEdit(api: Pair<String, String>, base: File, mood: Mood, out: File) {
        val (baseUrl, key) = api
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "gpt-image-1")
            .addFormDataPart("prompt", "The exact same character, same art style, same colors and outfit, now ${mood.pose}. Keep the framing and background.")
            .addFormDataPart("size", "1024x1024")
            .addFormDataPart("image", "base.png", base.asRequestBody("image/png".toMediaType()))
            .build()
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/images/edits").header("Authorization", "Bearer $key").post(body).build()
        slow.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Images API said HTTP ${r.code}: ${text.take(200)}")
            val first = (Json.parseToJsonElement(text) as JsonObject)["data"].let { it as kotlinx.serialization.json.JsonArray }.first() as JsonObject
            val b64 = (first["b64_json"] as? JsonPrimitive)?.content ?: throw IOException("Images API sent no picture")
            out.writeBytes(Base64.decode(b64, Base64.DEFAULT))
        }
    }

    companion object {
        const val OPTIONS = 4

        val STYLES = listOf(
            CharacterStyle("toy", "3D toy", "cute 3D collectible figure render, soft matte plastic, rounded shapes, big expressive eyes, soft studio light"),
            CharacterStyle("anime", "Anime", "clean modern anime illustration, crisp line art, cel shading, vibrant colors"),
            CharacterStyle("comic", "Comic", "bold American comic book art, strong ink outlines, halftone shading, punchy colors"),
            CharacterStyle("clay", "Clay", "handmade claymation look, visible fingerprints in clay, warm stop-motion lighting"),
            CharacterStyle("pixel", "Pixel", "detailed 32-bit pixel art, limited palette, clean silhouette"),
            CharacterStyle("neon", "Neon", "cyberpunk neon portrait, glowing rim light in magenta and cyan, dark backdrop"),
            CharacterStyle("paint", "Painted", "rich digital painting, visible brush strokes, cinematic warm light"),
            CharacterStyle("sticker", "Sticker", "glossy die-cut sticker, thick white border, flat bold colors"),
            CharacterStyle("real", "Realistic", "photorealistic portrait photo, 85mm lens, shallow depth of field, natural light"),
        )

        fun style(id: String?): CharacterStyle = STYLES.firstOrNull { it.id == id || it.label.equals(id, true) } ?: STYLES.first()
    }
}
