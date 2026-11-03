package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.clip
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import dev.dagent.core.llm.ImagePart
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * The agent's working folder. Inside the Linux computer it is mounted at /workspace, so a file
 * written by file_write is the same file a shell command sees.
 */
class Workspace(val root: File) {
    init { root.mkdirs() }

    fun resolve(path: String): File {
        val p = path.trim().removePrefix("file://")
        val rel = when {
            p == MOUNT || p.isEmpty() || p == "." -> ""
            p.startsWith("$MOUNT/") -> p.removePrefix("$MOUNT/")
            p.startsWith("/") -> throw IOException("Only files under $MOUNT can be used here (got $p). Use the shell for other Linux paths.")
            else -> p.removePrefix("./")
        }
        val f = File(root, rel).canonicalFile
        if (!f.path.startsWith(root.canonicalPath)) throw IOException("Path leaves the workspace: $path")
        return f
    }

    fun display(f: File): String {
        val rel = f.canonicalFile.relativeTo(root.canonicalFile).path
        return if (rel.isEmpty()) MOUNT else "$MOUNT/$rel"
    }

    companion object { const val MOUNT = "/workspace" }
}

private val textLike = Regex("\\.(txt|md|markdown|json|csv|tsv|xml|html?|css|js|mjs|ts|tsx|jsx|kt|kts|java|py|sh|bash|rb|go|rs|c|h|cpp|hpp|yaml|yml|toml|ini|conf|log|sql|svg|gradle|properties|env)$", RegexOption.IGNORE_CASE)
private val imageExt = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif")

class FileReadTool(private val ws: Workspace, private val canSeeImages: () -> Boolean) : Tool {
    override val name = "file_read"
    /** Only reads; safe to run alongside other read-only lookups. */
    override val readOnly = true
    override val description = "Read a file from the workspace (${Workspace.MOUNT}). Text comes back with line numbers; images come back as pictures you can see. Use offset/limit for big files."
    override val parameters = schema {
        title()
        string("path", "File path, relative to ${Workspace.MOUNT} or starting with it.", required = true)
        integer("offset", "First line to read, starting at 1.")
        integer("limit", "How many lines to read (default 400).")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val f = ws.resolve(args.requireString("path"))
        if (!f.exists()) return ToolResult.error("No such file: ${ws.display(f)}")
        if (f.isDirectory) return ToolResult.ok(listing(ws, f, 2))
        val ext = f.extension.lowercase()
        imageExt[ext]?.let { mime ->
            if (!canSeeImages()) return ToolResult.ok("${ws.display(f)} is an image (${f.length() / 1024} KB). The current model cannot see images.")
            if (f.length() > 8_000_000) return ToolResult.error("Image is too large to view (${f.length() / 1_000_000} MB).")
            val b64 = Base64.getEncoder().encodeToString(f.readBytes())
            return ToolResult("Image ${ws.display(f)}", images = listOf(ImagePart(mime, b64)))
        }
        if (!textLike.containsMatchIn(f.name) && looksBinary(f)) {
            return ToolResult.ok("${ws.display(f)} is a binary file (${f.length()} bytes). Use the shell to inspect it.")
        }
        val lines = f.readLines()
        val from = (args.int("offset") ?: 1).coerceAtLeast(1)
        val count = (args.int("limit") ?: 400).coerceIn(1, 4000)
        val slice = lines.drop(from - 1).take(count)
        val body = slice.mapIndexed { i, l -> "${(from + i).toString().padStart(5)}  $l" }.joinToString("\n")
        val more = if (from - 1 + slice.size < lines.size) "\n[${lines.size - (from - 1 + slice.size)} more lines; read on with offset=${from + slice.size}]" else ""
        return ToolResult.ok(clip(body, 30_000) + more)
    }
}

class FileWriteTool(private val ws: Workspace) : Tool {
    override val name = "file_write"
    override val description = "Create or overwrite a file in the workspace. Use it for code, notes, web pages, CSV and any other text. Parent folders are created."
    override val parameters = schema {
        title()
        string("path", "File path under ${Workspace.MOUNT}.", required = true)
        string("content", "The full file content.", required = true)
        boolean("append", "Add to the end instead of replacing the file.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val f = ws.resolve(args.requireString("path"))
        val content = args.string("content") ?: ""
        f.parentFile?.mkdirs()
        if (args.bool("append") == true) f.appendText(content) else f.writeText(content)
        return ToolResult.ok("Wrote ${content.length} characters to ${ws.display(f)}", files = listOf(f))
    }
}

class FileEditTool(private val ws: Workspace) : Tool {
    override val name = "file_edit"
    override val description = "Replace an exact piece of text in a workspace file. old_text must appear exactly once unless replace_all is true. Read the file first."
    override val parameters = schema {
        title()
        string("path", "File path under ${Workspace.MOUNT}.", required = true)
        string("old_text", "The exact text to find, including spaces and line breaks.", required = true)
        string("new_text", "What to put in its place.", required = true)
        boolean("replace_all", "Replace every occurrence.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val f = ws.resolve(args.requireString("path"))
        if (!f.isFile) return ToolResult.error("No such file: ${ws.display(f)}")
        val old = args.requireString("old_text")
        val new = args.string("new_text") ?: ""
        val text = f.readText()
        val count = text.windowedCount(old)
        if (count == 0) return ToolResult.error("old_text was not found in ${ws.display(f)}. Read the file and copy the text exactly.")
        if (count > 1 && args.bool("replace_all") != true) return ToolResult.error("old_text appears $count times. Add more surrounding text or set replace_all.")
        f.writeText(if (args.bool("replace_all") == true) text.replace(old, new) else text.replaceFirst(old, new))
        return ToolResult.ok("Edited ${ws.display(f)} ($count change${if (count > 1) "s" else ""})", files = listOf(f))
    }

    private fun String.windowedCount(s: String): Int {
        var n = 0
        var i = indexOf(s)
        while (i >= 0) { n++; i = indexOf(s, i + s.length) }
        return n
    }
}

class FileListTool(private val ws: Workspace) : Tool {
    override val name = "file_list"
    /** Only reads; safe to run alongside other read-only lookups. */
    override val readOnly = true
    override val description = "List files and folders in the workspace."
    override val parameters = schema {
        title()
        string("path", "Folder under ${Workspace.MOUNT} (default: the workspace itself).")
        integer("depth", "How many levels deep (default 2).")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val dir = ws.resolve(args.string("path") ?: Workspace.MOUNT)
        if (!dir.isDirectory) return ToolResult.error("Not a folder: ${ws.display(dir)}")
        return ToolResult.ok(listing(ws, dir, (args.int("depth") ?: 2).coerceIn(1, 6)))
    }
}

internal fun listing(ws: Workspace, dir: File, depth: Int): String {
    val sb = StringBuilder(ws.display(dir)).append('\n')
    var count = 0
    fun walk(d: File, level: Int) {
        val kids = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        for (k in kids) {
            if (count++ > 400) return
            sb.append("  ".repeat(level)).append(k.name)
            if (k.isDirectory) sb.append('/') else sb.append("  (").append(human(k.length())).append(')')
            sb.append('\n')
            if (k.isDirectory && level + 1 < depth) walk(k, level + 1)
        }
    }
    walk(dir, 1)
    if (count == 0) sb.append("  (empty)\n")
    if (count > 400) sb.append("  [more entries not shown]\n")
    return sb.toString()
}

internal fun human(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1048576.0)
}

private fun looksBinary(f: File): Boolean = f.inputStream().use { s ->
    val buf = ByteArray(2048)
    val n = s.read(buf)
    (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() }
}
