package dev.dagent.media

import android.util.Base64
import dev.dagent.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Makes short AI videos with the user's own key:
 * - Gemini (Google AI Studio key): the gemini-omni-1.1-flash model through the
 *   Interactions API; can start from a photo.
 * - Pollinations (pollinations key): GET gen.pollinations.ai/video/{prompt}, MP4 back.
 */
class VideoMaker(private val settings: Settings) {
    private val geminiKey: String? get() = settings.provider("gemini").apiKey.ifBlank { null }
    private val pollinationsToken: String? get() = settings.secret(Settings.POLLINATIONS_KEY).ifBlank { null }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.MINUTES)
        .callTimeout(8, TimeUnit.MINUTES)
        .build()

    data class Source(val id: String, val label: String, val note: String)

    fun sources(): List<Source> = buildList {
        if (!geminiKey.isNullOrBlank()) add(Source(GEMINI, "Gemini Omni", "Your Google AI Studio key, can animate a photo"))
        if (!pollinationsToken.isNullOrBlank()) add(Source(POLLINATIONS, "Pollinations", "Your Pollinations key (Veo, Seedance, Wan…)"))
    }

    fun ready(): Boolean = sources().isNotEmpty()

    /** Writes an MP4 to [out]. [photo] is JPEG bytes to animate (Gemini only). */
    suspend fun make(prompt: String, vertical: Boolean, seconds: Int, photo: ByteArray?, out: File, sourceId: String? = null) =
        withContext(Dispatchers.IO) {
            require(prompt.isNotBlank()) { "Describe the video first" }
            val available = sources()
            if (available.isEmpty()) throw IOException(NEEDS_KEY)
            val source = available.firstOrNull { it.id == sourceId }?.id
                ?: if (photo != null && available.any { it.id == GEMINI }) GEMINI else available.first().id
            when (source) {
                GEMINI -> gemini(prompt, vertical, photo, out)
                else -> pollinations(prompt, vertical, seconds, out)
            }
            if (!out.isFile || out.length() < 1024) throw IOException("The video service returned an empty file")
        }

    private suspend fun gemini(prompt: String, vertical: Boolean, photo: ByteArray?, out: File) {
        val key = geminiKey ?: throw IOException(NEEDS_KEY)
        val input: Any = if (photo == null) prompt else JSONArray()
            .put(JSONObject().put("type", "image").put("data", Base64.encodeToString(photo, Base64.NO_WRAP)).put("mime_type", "image/jpeg"))
            .put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject()
            .put("model", GEMINI_MODEL)
            .put("input", input)
            .put("response_format", JSONObject().put("type", "video").put("aspect_ratio", if (vertical) "9:16" else "16:9").put("delivery", "uri"))
        val req = Request.Builder()
            .url("$GEMINI_BASE/interactions")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val text = http.newCall(req).execute().use { resp ->
            val t = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(geminiError(t) ?: "Gemini answered HTTP ${resp.code}")
            t
        }
        val video = findVideo(JSONObject(text)) ?: throw IOException("Gemini returned no video. It may have declined the prompt; try rewording it.")
        video.optString("data").takeIf { it.isNotEmpty() }?.let {
            out.writeBytes(Base64.decode(it, Base64.DEFAULT))
            return
        }
        val uri = video.optString("uri").takeIf { it.isNotEmpty() } ?: throw IOException("Gemini returned no video")
        val fileId = Regex("files/([A-Za-z0-9_-]+)").find(uri)?.groupValues?.get(1)
        if (fileId != null) {
            // Wait for the file to finish processing (up to ~6 minutes).
            for (i in 0 until 72) {
                val state = http.newCall(Request.Builder().url("$GEMINI_BASE/files/$fileId").header("x-goog-api-key", key).build()).execute().use { r ->
                    JSONObject(r.body?.string().orEmpty().ifBlank { "{}" }).optString("state")
                }
                if (state == "ACTIVE" || state.isEmpty()) break
                if (state == "FAILED") throw IOException("Gemini could not finish the video")
                delay(5_000)
            }
            download("$GEMINI_BASE/files/$fileId:download?alt=media", out, "x-goog-api-key" to key)
        } else {
            download(uri, out, "x-goog-api-key" to key)
        }
    }

    /** Finds the first video content part anywhere in the Interactions response. */
    private fun findVideo(o: JSONObject): JSONObject? {
        o.optJSONObject("output_video")?.let { return it }
        if (o.optString("type") == "video" && (o.has("data") || o.has("uri"))) return o
        for (k in o.keys()) {
            when (val v = o.opt(k)) {
                is JSONObject -> findVideo(v)?.let { return it }
                is JSONArray -> for (i in 0 until v.length()) (v.opt(i) as? JSONObject)?.let { c -> findVideo(c)?.let { return it } }
            }
        }
        return null
    }

    private fun pollinations(prompt: String, vertical: Boolean, seconds: Int, out: File) {
        val key = pollinationsToken ?: throw IOException(NEEDS_KEY)
        val url = "https://gen.pollinations.ai/video/".toHttpUrl().newBuilder()
            .addPathSegment(prompt)
            .addQueryParameter("model", POLLINATIONS_MODEL)
            .addQueryParameter("duration", seconds.coerceIn(4, 8).let { if (it <= 4) 4 else if (it <= 6) 6 else 8 }.toString())
            .addQueryParameter("aspectRatio", if (vertical) "9:16" else "16:9")
            .addQueryParameter("audio", "true")
            .build()
        download(url.toString(), out, "Authorization" to "Bearer $key")
    }

    private fun download(url: String, out: File, header: Pair<String, String>) {
        val req = Request.Builder().url(url).header(header.first, header.second).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val t = resp.body?.string().orEmpty()
                throw IOException(geminiError(t) ?: "Video service answered HTTP ${resp.code}")
            }
            val type = resp.header("Content-Type").orEmpty()
            if (type.startsWith("application/json") || type.startsWith("text/")) {
                throw IOException("Video service sent ${type.substringBefore(';')} instead of a video: " + resp.body?.string().orEmpty().take(200))
            }
            val tmp = File(out.parentFile, out.name + ".part")
            resp.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: throw IOException("Empty video")
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun geminiError(body: String): String? = try {
        val e = JSONObject(body).opt("error")
        when (e) {
            is JSONObject -> e.optString("message").takeIf { it.isNotBlank() }
            is String -> e.takeIf { it.isNotBlank() }
            else -> null
        }
    } catch (e: org.json.JSONException) {
        null
    }

    companion object {
        const val GEMINI = "gemini"
        const val POLLINATIONS = "pollinations"
        const val GEMINI_MODEL = "gemini-omni-1.1-flash"
        const val POLLINATIONS_MODEL = "google/veo-3.1-fast"
        private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json".toMediaType()
        const val NEEDS_KEY = "AI video needs a key: add a free Google AI Studio (Gemini) key or a Pollinations key (Gemini in Settings > AI providers, Pollinations in Tools and behavior > Images)."
    }
}
