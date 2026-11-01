package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import java.io.File
import java.util.Locale

/**
 * Builds a real web page: the model writes the HTML (CSS and JavaScript inline), it's saved as
 * sites/<name>/index.html in the workspace, and the chat shows it as a live, scrollable preview.
 */
class WebsiteTool(private val ws: Workspace) : Tool {
    override val name = "make_website"
    override val description =
        "Build a real website or web page and show the person a live preview in the chat. Write one complete HTML file " +
            "(<!doctype html>, <meta name=viewport>, CSS in <style>, any JavaScript in <script>), mobile-friendly and polished, with real text for the person's " +
            "business (ask or make sensible placeholders) and images from https://picsum.photos or emoji. Call again with the same name to update it. " +
            "Use this, never make_image, for websites, landing pages, menus, portfolios or app screen mockups."
    override val parameters = schema {
        title()
        string("name", "Short site name, e.g. joes-bakery.", required = true)
        string("html", "The complete HTML document.", required = true)
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val slug = slug(args.requireString("name"))
        val html = wrap(args.requireString("html"))
        val dir = File(ws.root, "sites/$slug").apply { mkdirs() }
        val file = File(dir, "index.html")
        file.writeText(html)
        return ToolResult.ok(
            "Saved ${ws.display(file)} (${html.length} characters). The live preview is showing in the chat; the person can open it full screen or share it. " +
                "To change it, call make_website again with name \"$slug\" and the full updated HTML.",
            files = listOf(file),
        )
    }

    companion object {
        fun slug(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "site" }

        /** Makes a fragment into a full page so the preview always renders. */
        fun wrap(html: String): String {
            val h = html.trim().removePrefix("```html").removePrefix("```").removeSuffix("```").trim()
            if (h.contains("<html", ignoreCase = true)) return h
            return "<!doctype html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>\n<body>\n$h\n</body></html>\n"
        }
    }
}
