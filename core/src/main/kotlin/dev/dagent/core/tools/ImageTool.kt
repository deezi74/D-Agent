package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Makes pictures with Pollinations (free; a key from enter.pollinations.ai lifts the limits and
 * unlocks more models). Saved under /workspace/images so the person sees them in the chat.
 */
class ImageTool(
    http: OkHttpClient,
    private val ws: Workspace,
    private val key: () -> String,
) : Tool {
    private val slow = http.newBuilder().readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build()

    override val name = "make_image"
    override val description = "Create an image from a description: photos, art, logos, stickers, wallpapers, thumbnails. " +
        "Write a rich prompt (subject, style, lighting, composition). The picture appears in the chat automatically; do not paste its path as a link."
    override val parameters = schema {
        title()
        string("prompt", "A detailed description of the picture, in English.", required = true)
        string("shape", "Aspect: square, portrait (9:16), landscape (16:9), or wide.", enum = listOf("square", "portrait", "landscape", "wide"))
        string("model", "flux (default, best all-round), turbo (fast), or another Pollinations model name.")
        integer("seed", "Same seed and prompt give the same image; change it for a variation.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val prompt = args.requireString("prompt")
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
        slow.newCall(req.build()).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            if (!r.isSuccessful || !type.startsWith("image")) {
                val body = r.body?.string().orEmpty().take(300)
                return@withContext ToolResult.error("Image service said HTTP ${r.code}${if (body.isNotBlank()) ": $body" else ""}. " +
                    if (r.code == 401 || r.code == 402 || r.code == 429) "Add a free Pollinations key in Settings > Images for higher limits." else "Try again or change the prompt.")
            }
            val ext = if (type.contains("png")) "png" else if (type.contains("webp")) "webp" else "jpg"
            val dir = File(ws.root, "images").apply { mkdirs() }
            val slug = prompt.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "image" }
            val f = File(dir, "$slug-$seed.$ext")
            r.body!!.byteStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            ToolResult.ok("Made ${ws.display(f)} (${w}x$h, model $model, seed $seed). It is already shown to the person.", files = listOf(f))
        }
    }
}
