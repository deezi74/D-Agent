package dev.dagent.phone

import dev.dagent.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Natural voices for spoken replies with the person's own key: OpenAI (gpt-4o-mini-tts),
 * OpenRouter (the same model routed) or ElevenLabs. Returns MP3 files; the speaker falls back
 * to the phone's voice on any error.
 */
class CloudVoice(private val settings: Settings, private val cacheDir: File, http: OkHttpClient) {
    private val http = http.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    data class Option(val id: String, val label: String, val voices: List<Pair<String, String>>)

    private fun key(id: String) = when (id) {
        ELEVENLABS -> settings.secret(Settings.ELEVENLABS_KEY)
        else -> settings.provider(id).apiKey
    }

    /** Every voice service, with whether its key is set. */
    fun options(): List<Pair<Option, Boolean>> = listOf(
        Option(PHONE, "Phone voice", emptyList()) to true,
        Option(OPENAI, "OpenAI", OPENAI_VOICES) to key(OPENAI).isNotBlank(),
        Option(OPENROUTER, "OpenRouter", OPENAI_VOICES) to key(OPENROUTER).isNotBlank(),
        Option(ELEVENLABS, "ElevenLabs", ELEVEN_VOICES) to key(ELEVENLABS).isNotBlank(),
    )

    /** True when a cloud voice is picked and its key is present. */
    fun active(): Boolean {
        val p = settings.current.voiceProvider
        return p != PHONE && key(p).isNotBlank()
    }

    suspend fun synthesize(text: String): File = withContext(Dispatchers.IO) {
        val s = settings.current
        val dir = File(cacheDir, "voice").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 10 * 60_000 }?.forEach { it.delete() }
        val out = File(dir, "say_${System.nanoTime()}.mp3")
        val request = when (s.voiceProvider) {
            ELEVENLABS -> {
                val voice = s.voiceName.takeIf { v -> ELEVEN_VOICES.any { it.first == v } || (v.length > 15 && OPENAI_VOICES.none { it.first == v }) }
                    ?: ELEVEN_VOICES.first().first
                val body = buildJsonObject { put("text", text); put("model_id", "eleven_flash_v2_5") }
                Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128")
                    .header("xi-api-key", key(ELEVENLABS))
                    .header("Accept", "audio/mpeg")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
            }
            OPENROUTER -> Request.Builder()
                .url("https://openrouter.ai/api/v1/audio/speech")
                .header("Authorization", "Bearer ${key(OPENROUTER)}")
                .header("X-Title", "D-Agent")
                .post(openAiBody("openai/gpt-4o-mini-tts-2025-12-15", text, s.voiceName).toString().toRequestBody(JSON))
                .build()
            else -> Request.Builder()
                .url("https://api.openai.com/v1/audio/speech")
                .header("Authorization", "Bearer ${key(OPENAI)}")
                .post(openAiBody("gpt-4o-mini-tts", text, s.voiceName).toString().toRequestBody(JSON))
                .build()
        }
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                val msg = runCatching {
                    val o = Json.parseToJsonElement(body) as JsonObject
                    ((o["error"] as? JsonObject)?.get("message") ?: (o["detail"] as? JsonObject)?.get("message"))?.let { (it as JsonPrimitive).content }
                }.getOrNull()
                throw IOException(msg?.takeIf { it.isNotBlank() } ?: "Voice service answered HTTP ${resp.code}")
            }
            if (resp.header("Content-Type").orEmpty().startsWith("application/json")) throw IOException("Voice service sent no audio")
            resp.body?.byteStream()?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: throw IOException("Empty audio")
        }
        if (out.length() < 200) throw IOException("Empty audio")
        out
    }

    private fun openAiBody(model: String, text: String, voice: String) = buildJsonObject {
        put("model", model)
        put("input", text)
        put("voice", voice.takeIf { v -> OPENAI_VOICES.any { it.first == v } } ?: "coral")
        put("response_format", "mp3")
        put("instructions", "Speak naturally and warmly, like a quick-witted American friend. Natural pace, expressive, not robotic.")
    }

    companion object {
        const val PHONE = "phone"
        const val OPENAI = "openai"
        const val OPENROUTER = "openrouter"
        const val ELEVENLABS = "elevenlabs"
        private val JSON = "application/json".toMediaType()

        val OPENAI_VOICES = listOf(
            "coral" to "Coral", "nova" to "Nova", "shimmer" to "Shimmer", "sage" to "Sage", "ballad" to "Ballad",
            "alloy" to "Alloy", "ash" to "Ash", "echo" to "Echo", "onyx" to "Onyx", "verse" to "Verse", "fable" to "Fable",
        )

        /** ElevenLabs premade voices (IDs from their public voice library). */
        val ELEVEN_VOICES = listOf(
            "21m00Tcm4TlvDq8ikWAM" to "Rachel", "EXAVITQu4vr4xnSDxMaL" to "Sarah", "JBFqnCBsd6RMkjVDRZzb" to "George",
            "pNInz6obpgDQGcFmaJgB" to "Adam", "TX3LPaxmHKxFdv7VOQHJ" to "Liam", "XrExE9yKIg1WjnnlVkGX" to "Matilda",
        )
    }
}
