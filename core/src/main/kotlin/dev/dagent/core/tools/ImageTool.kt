package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Makes pictures with Pollinations (free; a key from enter.pollinations.ai lifts the limits and
 * unlocks more models). Saved under /workspace/images so the person sees them in the chat.
 */
private val WEBSITE = Regex("\\b(website|web site|web page|webpage|landing page|homepage|home page)\\b", RegexOption.IGNORE_CASE)

class ImageTool(
    http: OkHttpClient,
    private val ws: Workspace,
    private val key: () -> String,
    /** The person's OpenAI and OpenRouter keys (blank when not set), for editing photos. */
    private val editKeys: () -> Pair<String, String> = { "" to "" },
) : Tool {
    private val slow = http.newBuilder().readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build()

    override val name = "make_image"
    override val description = "Create an image from a description: photos, art, logos, stickers, wallpapers, thumbnails (not websites or app screens; use make_website). " +
        "Write a rich prompt (subject, style, lighting, composition). To edit a photo (the person's attachment or an earlier image), " +
        "pass its /workspace path as edit and say the change in prompt (\"make it night\", \"put me in a tux\", \"remove the background\"). " +
        "The picture appears in the chat automatically; do not paste its path as a link."
    override val parameters = schema {
        title()
        string("prompt", "A detailed description of the picture, in English.", required = true)
        string("shape", "Aspect: square, portrait (9:16), landscape (16:9), or wide.", enum = listOf("square", "portrait", "landscape", "wide"))
        string("model", "flux (default, best all-round), turbo (fast), or another Pollinations model name.")
        integer("seed", "Same seed and prompt give the same image; change it for a variation.")
        string("edit", "Path of an image to edit, e.g. /workspace/uploads/photo.jpg. Leave out to make a new picture.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val prompt = args.requireString("prompt")
        if (args.string("edit").isNullOrBlank() && WEBSITE.containsMatchIn(prompt)) {
            return@withContext ToolResult.error("Don't draw a website. Call make_website with a name and the full HTML page instead; it shows a live preview.")
        }
        args.string("edit")?.takeIf { it.isNotBlank() }?.let { path ->
            val input = runCatching { ws.resolve(path) }.getOrNull()?.takeIf { it.isFile }
                ?: return@withContext ToolResult.error("No image at $path.")
            ctx.progress("Editing the photo...")
            return@withContext runCatching { edit(prompt, input) }.fold(
                { bytes ->
                    val dir = File(ws.root, "images").apply { mkdirs() }
                    val f = File(dir, input.nameWithoutExtension.take(30) + "-edit-${System.currentTimeMillis() % 100000}.png")
                    f.writeBytes(bytes)
                    ToolResult.ok("Edited into ${ws.display(f)}. It is already shown to the person.", files = listOf(f))
                },
                { ToolResult.error(it.message ?: "Couldn't edit the photo.") },
            )
        }
        val (w, h) = when (args.string("shape")) {
            "portrait" -> 768 to 1344
            "landscape" -> 1344 to 768
            "wide" -> 1536 to 640
            else -> 1024 to 1024
        }
        val model = args.string("model")?.takeIf { it.isNotBlank() } ?: "flux"
        val seed = args.int("seed") ?: (Math.random() * 1_000_000).toInt()
        val token = key().trim()
        val url = "https://image.pollinations.ai/prompt/${URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")}" +
            "?width=$w&height=$h&model=${URLEncoder.encode(model, "UTF-8")}&seed=$seed&nologo=true&enhance=true&private=true"
        ctx.progress("Painting...")
        val req = Request.Builder().url(url).header("User-Agent", "D-Agent")
        if (token.isNotEmpty()) req.header("Authorization", "Bearer $token")
        // The free tier answers "busy" (402/429/5xx) when it's crowded; wait and try again a few times.
        var wait = 6_000L
        var attempt = 0
        while (true) {
        slow.newCall(req.build()).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            if (!r.isSuccessful || !type.startsWith("image")) {
                val busy = r.code == 402 || r.code == 429 || r.code >= 500
                if (busy && attempt < 3) {
                    attempt++
                    ctx.progress("Image service is busy, trying again...")
                    Thread.sleep(wait)
                    wait *= 2
                    return@use null
                }
                val body = r.body?.string().orEmpty().take(200)
                return@withContext ToolResult.error(
                    if (busy) "The free picture service (Pollinations) is busy right now (HTTP ${r.code}). Tell the person to try again in a minute, or add a free Pollinations key (enter.pollinations.ai) in Settings > Tools and behavior > Images for steady access. Image making itself works."
                    else "Image service said HTTP ${r.code}${if (body.isNotBlank()) ": $body" else ""}. Try again or change the prompt."
                )
            }
            val ext = if (type.contains("png")) "png" else if (type.contains("webp")) "webp" else "jpg"
            val dir = File(ws.root, "images").apply { mkdirs() }
            val slug = prompt.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "image" }
            val f = File(dir, "$slug-$seed.$ext")
            r.body!!.byteStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            ToolResult.ok("Made ${ws.display(f)} (${w}x$h, model $model, seed $seed). It is already shown to the person.", files = listOf(f))
        }?.let { return@withContext it }
        }
        @Suppress("UNREACHABLE_CODE")
        ToolResult.error("Image service didn't answer.")
    }

    private fun edit(prompt: String, input: File): ByteArray {
        val (openAi, openRouter) = editKeys()
        val full = prompt.trim() + ". Keep everything else the same. No watermark."
        return when {
            openRouter.isNotBlank() -> openRouterEdit(openRouter, full, input)
            openAi.isNotBlank() -> openAiEdit(openAi, full, input)
            else -> throw java.io.IOException(
                "Editing a photo needs an OpenAI or OpenRouter key (Settings > AI providers). The free image service can only make new pictures. Tell the person that.",
            )
        }
    }

    private fun mime(f: File) = when (f.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }

    private fun openAiEdit(key: String, prompt: String, input: File): ByteArray {
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", "gpt-image-1")
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("image", input.name, input.asRequestBody(mime(input).toMediaType()))
            .build()
        val req = Request.Builder().url("https://api.openai.com/v1/images/edits").header("Authorization", "Bearer $key").post(body).build()
        slow.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw java.io.IOException("OpenAI said HTTP ${r.code}: ${text.take(200)}")
            val item = (Json.parseToJsonElement(text).jsonObject["data"] as? JsonArray)?.firstOrNull()?.jsonObject
                ?: throw java.io.IOException("OpenAI sent no image")
            item["b64_json"]?.jsonPrimitive?.contentOrNull?.let { return Base64.getDecoder().decode(it) }
            return download(item["url"]?.jsonPrimitive?.contentOrNull ?: throw java.io.IOException("OpenAI sent no image"))
        }
    }

    private fun openRouterEdit(key: String, prompt: String, input: File): ByteArray {
        val dataUrl = "data:${mime(input)};base64," + Base64.getEncoder().encodeToString(input.readBytes())
        val body = buildJsonObject {
            put("model", "google/gemini-2.5-flash-image")
            putJsonArray("modalities") { add("image"); add("text") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", prompt) }
                        addJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", dataUrl) } }
                    }
                }
            }
        }
        val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $key").header("X-Title", "D-Agent")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        slow.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw java.io.IOException("OpenRouter said HTTP ${r.code}: ${text.take(200)}")
            val message = (Json.parseToJsonElement(text).jsonObject["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?: throw java.io.IOException("OpenRouter sent no image")
            val url = (message["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("image_url")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?: throw java.io.IOException("The model answered with text instead of an image")
            return if (url.startsWith("data:")) Base64.getMimeDecoder().decode(url.substringAfter(",")) else download(url)
        }
    }

    private fun download(url: String): ByteArray = slow.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw java.io.IOException("Couldn't download the image (HTTP ${r.code})")
        r.body!!.bytes()
    }
}
