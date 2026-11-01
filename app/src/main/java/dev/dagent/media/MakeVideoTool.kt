package dev.dagent.media

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import dev.dagent.core.tools.Workspace
import java.io.File

/** Short AI videos from a prompt, or a photo brought to life. Needs a Gemini or Pollinations key. */
class MakeVideoTool(private val maker: VideoMaker, private val ws: Workspace) : Tool {
    override val name = "make_video"
    override val description =
        "Make a short AI video (about 4 to 8 seconds, with sound) from a description, or animate a photo (photo: its /workspace path). " +
            "Write a cinematic prompt: subject, action, setting, camera move, lighting, mood, sound. It takes one to three minutes; " +
            "the video appears in the chat by itself, so reply with one short line."
    override val parameters = schema {
        title()
        string("prompt", "The video description, in English.", required = true)
        string("shape", "portrait (default, 9:16) or landscape (16:9).", enum = listOf("portrait", "landscape"))
        integer("seconds", "4, 6 or 8 (default 8).")
        string("photo", "Path of a photo to animate, e.g. /workspace/uploads/photo.jpg.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        if (!maker.ready()) return ToolResult.error(VideoMaker.NEEDS_KEY + " Tell the person exactly that.")
        val prompt = args.requireString("prompt")
        val photo = args.string("photo")?.takeIf { it.isNotBlank() }?.let { p ->
            runCatching { ws.resolve(p) }.getOrNull()?.takeIf { it.isFile } ?: return ToolResult.error("No photo at $p.")
        }
        val dir = File(ws.root, "videos").apply { mkdirs() }
        val out = File(dir, "video-${System.currentTimeMillis()}.mp4")
        ctx.progress("Filming... (1 to 3 minutes)")
        return try {
            maker.make(prompt, args.string("shape") != "landscape", args.int("seconds") ?: 8, photo?.readBytes(), out)
            ToolResult.ok("Made ${ws.display(out)}. It plays in the chat.", files = listOf(out))
        } catch (e: Exception) {
            ToolResult.error("Couldn't make the video: ${e.message}")
        }
    }
}
