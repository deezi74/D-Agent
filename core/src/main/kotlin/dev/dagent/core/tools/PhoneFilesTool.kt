package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.clip
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The phone's shared storage (Download, DCIM, Pictures, Documents, Music, Movies...), shown to the
 * model as /sdcard. It can look around freely; anything that changes files asks first. Deleting
 * never really deletes: files move to /sdcard/.D-Agent-Trash and are removed after 30 days.
 * Android/data and Android/obb (other apps' private files) are off-limits.
 */
class PhoneFiles(val root: File, private val now: () -> Long = System::currentTimeMillis) {
    val trash = File(root, TRASH)

    fun resolve(path: String): File {
        val p = path.trim().removePrefix("file://").trimEnd('/')
        val rel = when {
            p.isEmpty() || p == MOUNT || p == "/storage/emulated/0" -> ""
            p.startsWith("$MOUNT/") -> p.removePrefix("$MOUNT/")
            p.startsWith("/storage/emulated/0/") -> p.removePrefix("/storage/emulated/0/")
            p.startsWith("/") -> throw IOException("Use paths on the phone's storage, like /sdcard/Download (got $p).")
            else -> p.removePrefix("./")
        }
        val f = File(root, rel).canonicalFile
        val base = root.canonicalFile
        if (f != base && !f.path.startsWith(base.path + File.separator)) throw IOException("That path leaves the phone's storage: $path")
        val inside = f.relativeTo(base).invariantSeparatorsPath
        if (BLOCKED.any { inside.equals(it, true) || inside.startsWith("$it/", true) }) throw IOException("$path holds other apps' private data, so it's off-limits.")
        return f
    }

    fun show(f: File): String {
        val rel = f.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
        return if (rel.isEmpty()) MOUNT else "$MOUNT/$rel"
    }

    /** Every file under [dir], skipping the trash, hidden app folders and off-limits places. */
    fun walk(dir: File, hidden: Boolean = false): Sequence<File> = dir.walkTopDown()
        .onEnter { d ->
            val rel = d.relativeTo(root).invariantSeparatorsPath
            d == dir || (BLOCKED.none { rel.equals(it, true) || rel.startsWith("$it/", true) } && d != trash && (hidden || !d.name.startsWith(".")))
        }
        .filter { it.isFile && (hidden || !it.name.startsWith(".")) }

    /** Moves [f] into today's trash folder, keeping its path so it can be put back. */
    fun toTrash(f: File): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now()))
        val dest = unique(File(File(trash, day), f.canonicalFile.relativeTo(root.canonicalFile).path))
        dest.parentFile?.mkdirs()
        moveFile(f, dest)
        return dest
    }

    fun restore(t: File): File {
        val rel = t.canonicalFile.relativeTo(trash.canonicalFile).invariantSeparatorsPath
        val original = rel.substringAfter('/', "")
        if (original.isEmpty()) throw IOException("That isn't a trashed file.")
        val dest = unique(File(root, original))
        dest.parentFile?.mkdirs()
        moveFile(t, dest)
        return dest
    }

    /** Removes trash folders older than 30 days. Returns how many files went. */
    fun purgeOldTrash(): Int {
        val cutoff = now() - TimeUnit.DAYS.toMillis(KEEP_DAYS)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var n = 0
        trash.listFiles()?.forEach { d ->
            val t = runCatching { fmt.parse(d.name)?.time }.getOrNull() ?: return@forEach
            if (t < cutoff) { n += d.walkTopDown().count { it.isFile }; d.deleteRecursively() }
        }
        return n
    }

    companion object {
        const val MOUNT = "/sdcard"
        const val TRASH = ".D-Agent-Trash"
        const val KEEP_DAYS = 30L
        val BLOCKED = listOf("Android/data", "Android/obb")

        fun unique(f: File): File {
            if (!f.exists()) return f
            val base = f.nameWithoutExtension
            val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
            var i = 2
            while (true) { val c = File(f.parentFile, "$base ($i)$ext"); if (!c.exists()) return c; i++ }
        }

        fun moveFile(from: File, to: File) {
            if (from.renameTo(to)) return
            if (from.isDirectory) { from.copyRecursively(to, overwrite = false); from.deleteRecursively() }
            else { from.copyTo(to, overwrite = false); if (!from.delete()) throw IOException("Copied but couldn't remove ${from.name}") }
        }

        fun size(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(Locale.US, b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.1f MB".format(Locale.US, b / (1L shl 20).toDouble())
            b >= 1L shl 10 -> "%.0f KB".format(Locale.US, b / 1024.0)
            else -> "$b B"
        }

        val KINDS = mapOf(
            "images" to setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng"),
            "videos" to setOf("mp4", "mkv", "mov", "webm", "3gp", "avi"),
            "audio" to setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr"),
            "documents" to setOf("pdf", "doc", "docx", "odt", "txt", "md", "rtf", "xls", "xlsx", "csv", "ppt", "pptx"),
            "apks" to setOf("apk", "apks", "xapk"),
            "archives" to setOf("zip", "rar", "7z", "tar", "gz", "tgz"),
        )

        fun kind(f: File): String = KINDS.entries.firstOrNull { f.extension.lowercase() in it.value }?.key ?: "other"
    }
}

class PhoneFilesTool(
    private val files: PhoneFiles,
    /** Null when the app may use all files; otherwise how to turn it on. */
    private val notAllowed: () -> String?,
    /** Where copy_to_workspace puts files (the agent's /workspace/uploads). */
    private val workspaceUploads: File? = null,
) : Tool {
    override val name = "phone_files"
    override val description =
        "The phone's own files at /sdcard (Download, DCIM/Camera, Pictures, Documents, Music, Movies, WhatsApp media...). " +
            "list (path), search (query and/or kind images|videos|audio|documents|apks|archives, min_mb, older_days), space (what takes room), " +
            "duplicates, mkdir, move (paths -> to folder), rename (path, name), delete (paths; goes to Trash for 30 days), trash, restore (paths from trash), " +
            "empty_trash, copy_to_workspace (paths, so file_read or the shell can open them). Look first, then show the person what you'll change; " +
            "changes and deletes ask the person first."
    override val parameters = schema {
        title()
        string("action", "What to do.", required = true, enum = ACTIONS)
        string("path", "A folder or file, e.g. /sdcard/Download.")
        stringArray("paths", "Files or folders for move, delete, restore, copy_to_workspace.")
        string("to", "move: the destination folder (made if missing).")
        string("name", "rename: the new name.")
        string("query", "search: part of the file name (case doesn't matter).")
        string("kind", "search: images, videos, audio, documents, apks or archives.")
        integer("min_mb", "search/duplicates: only files at least this big (MB).")
        integer("older_days", "search: only files not changed in this many days.")
        integer("limit", "Most results (default 100).")
    }
    override val readOnly get() = false

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        notAllowed()?.let { return@withContext ToolResult.error(it) }
        val action = args.requireString("action")
        runCatching { files.purgeOldTrash() }
        val limit = (args.int("limit") ?: 100).coerceIn(5, 500)
        try {
            when (action) {
                "list" -> ToolResult.ok(list(files.resolve(args.string("path") ?: PhoneFiles.MOUNT), limit))
                "search" -> ToolResult.ok(search(args, limit))
                "space" -> ToolResult.ok(space(files.resolve(args.string("path") ?: PhoneFiles.MOUNT), ctx))
                "duplicates" -> ToolResult.ok(duplicates(files.resolve(args.string("path") ?: PhoneFiles.MOUNT), args.int("min_mb") ?: 0, limit, ctx))
                "mkdir" -> {
                    val d = files.resolve(args.requireString("path"))
                    if (!ctx.confirm("Make a folder?", files.show(d))) return@withContext ToolResult.error("The person said no.")
                    d.mkdirs(); ToolResult.ok("Made ${files.show(d)}")
                }
                "rename" -> rename(args, ctx)
                "move" -> move(args, ctx)
                "delete" -> delete(args, ctx)
                "trash" -> ToolResult.ok(trashList(limit))
                "restore" -> restore(args, ctx)
                "empty_trash" -> emptyTrash(ctx)
                "copy_to_workspace" -> copyOut(args)
                else -> ToolResult.error("Unknown action $action.")
            }
        } catch (e: IOException) {
            ToolResult.error(e.message ?: "File error")
        } catch (e: SecurityException) {
            ToolResult.error("Android blocked that (${e.message}). Turn on All files access in Settings > Permissions.")
        }
    }

    private fun date(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    private fun line(f: File) = if (f.isDirectory) "${files.show(f)}/  (folder, ${f.list()?.size ?: 0} items)"
        else "${files.show(f)}  ${PhoneFiles.size(f.length())}  ${date(f.lastModified())}"

    private fun list(dir: File, limit: Int): String {
        if (!dir.exists()) return "${files.show(dir)} doesn't exist."
        if (dir.isFile) return line(dir)
        val kids = dir.listFiles()?.filter { it != files.trash && files.show(it).let { s -> PhoneFiles.BLOCKED.none { b -> s.equals("${PhoneFiles.MOUNT}/$b", true) } } }.orEmpty()
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        return clip("${files.show(dir)}: ${kids.size} items\n" + kids.take(limit).joinToString("\n") { line(it) } +
            if (kids.size > limit) "\n…and ${kids.size - limit} more" else "", 20_000)
    }

    private fun search(args: Args, limit: Int): String {
        val dir = files.resolve(args.string("path") ?: PhoneFiles.MOUNT)
        val q = args.string("query")?.trim().orEmpty()
        val kind = args.string("kind")?.lowercase()?.trimEnd('s')?.let { k -> PhoneFiles.KINDS.keys.firstOrNull { it.trimEnd('s') == k } }
        val minBytes = (args.int("min_mb") ?: 0) * (1L shl 20)
        val before = args.int("older_days")?.let { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it.toLong()) }
        val hits = files.walk(dir).filter { f ->
            (q.isEmpty() || f.name.contains(q, true)) && (kind == null || PhoneFiles.kind(f) == kind) &&
                f.length() >= minBytes && (before == null || f.lastModified() < before)
        }.take(5000).toList().sortedByDescending { it.length() }
        val total = hits.sumOf { it.length() }
        return clip("${hits.size} files, ${PhoneFiles.size(total)} in all (biggest first):\n" + hits.take(limit).joinToString("\n") { line(it) }, 20_000)
    }

    private fun space(dir: File, ctx: ToolContext): String {
        ctx.progress("Measuring ${files.show(dir)}…")
        val all = files.walk(dir, hidden = true).toList()
        val byTop = all.groupBy { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if ('/' in rel) rel.substringBefore('/') + "/" else "(files here)"
        }.mapValues { (_, v) -> v.sumOf { it.length() } }.entries.sortedByDescending { it.value }
        val byKind = all.groupBy { PhoneFiles.kind(it) }.mapValues { (_, v) -> v.size to v.sumOf { it.length() } }.entries.sortedByDescending { it.value.second }
        return buildString {
            append("${files.show(dir)}: ${all.size} files, ${PhoneFiles.size(all.sumOf { it.length() })}\n\nBiggest folders:\n")
            byTop.take(15).forEach { append("  ${it.key}  ${PhoneFiles.size(it.value)}\n") }
            append("\nBy kind:\n")
            byKind.forEach { append("  ${it.key}: ${it.value.first} files, ${PhoneFiles.size(it.value.second)}\n") }
            append("\nBiggest files:\n")
            all.sortedByDescending { it.length() }.take(15).forEach { append("  ").append(line(it)).append('\n') }
            val t = files.trash.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (t > 0) append("\nTrash: ${PhoneFiles.size(t)} (cleared after ${PhoneFiles.KEEP_DAYS} days)")
        }
    }

    private fun duplicates(dir: File, minMb: Int, limit: Int, ctx: ToolContext): String {
        ctx.progress("Looking for duplicates…")
        val min = maxOf(1L, minMb * (1L shl 20))
        val groups = files.walk(dir).filter { it.length() >= min }.groupBy { it.length() }.values.filter { it.size > 1 }
        val dupes = ArrayList<List<File>>()
        for (g in groups) {
            g.groupBy { hash(it) }.values.filter { it.size > 1 }.forEach { dupes += it.sortedBy { f -> f.lastModified() } }
            if (dupes.size >= limit) break
        }
        if (dupes.isEmpty()) return "No duplicate files found in ${files.show(dir)}."
        val waste = dupes.sumOf { it.drop(1).sumOf { f -> f.length() } }
        return clip("${dupes.size} sets of identical files; removing the extra copies would free ${PhoneFiles.size(waste)}. The oldest copy is listed first:\n" +
            dupes.joinToString("\n\n") { set -> set.joinToString("\n") { line(it) } }, 20_000)
    }

    private fun hash(f: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault(f.path)

    private fun targets(args: Args): List<File> {
        val raw = args.stringList("paths").ifEmpty { listOfNotNull(args.string("path")) }
        if (raw.isEmpty()) throw IOException("Say which files (paths).")
        return raw.map { files.resolve(it) }.distinct()
    }

    private fun summary(fs: List<File>): String {
        val shown = fs.take(25).joinToString("\n") { line(it) }
        val more = if (fs.size > 25) "\n…and ${fs.size - 25} more" else ""
        val bytes = fs.sumOf { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } else it.length() }
        return "$shown$more\n(${fs.size} items, ${PhoneFiles.size(bytes)})"
    }

    private suspend fun rename(args: Args, ctx: ToolContext): ToolResult {
        val f = files.resolve(args.requireString("path"))
        if (!f.exists()) return ToolResult.error("${files.show(f)} doesn't exist.")
        val name = args.requireString("name").trim()
        if (name.isEmpty() || '/' in name || name == "." || name == "..") return ToolResult.error("That isn't a valid file name.")
        val dest = File(f.parentFile, name)
        if (dest.exists()) return ToolResult.error("${files.show(dest)} already exists.")
        if (!ctx.confirm("Rename ${f.name}?", "${files.show(f)}\n→ $name")) return ToolResult.error("The person said no.")
        PhoneFiles.moveFile(f, dest)
        return ToolResult.ok("Renamed to ${files.show(dest)}")
    }

    private suspend fun move(args: Args, ctx: ToolContext): ToolResult {
        val fs = targets(args).filter { it.exists() }
        if (fs.isEmpty()) return ToolResult.error("None of those files exist.")
        val to = files.resolve(args.requireString("to"))
        if (to.isFile) return ToolResult.error("${files.show(to)} is a file, not a folder.")
        if (fs.any { to.path.startsWith(it.path + File.separator) || to == it }) return ToolResult.error("Can't move a folder into itself.")
        if (!ctx.confirm("Move ${fs.size} item(s) to ${files.show(to)}?", summary(fs))) return ToolResult.error("The person said no.")
        to.mkdirs()
        val done = fs.map { f -> PhoneFiles.unique(File(to, f.name)).also { PhoneFiles.moveFile(f, it) } }
        return ToolResult.ok("Moved ${done.size} item(s) to ${files.show(to)}:\n" + done.take(50).joinToString("\n") { files.show(it) })
    }

    private suspend fun delete(args: Args, ctx: ToolContext): ToolResult {
        val fs = targets(args).filter { it.exists() }
        if (fs.isEmpty()) return ToolResult.error("None of those files exist.")
        if (fs.any { it == files.root.canonicalFile || it.parentFile == null }) return ToolResult.error("I won't delete the whole storage.")
        if (fs.any { it.path.startsWith(files.trash.canonicalPath) }) return ToolResult.error("Those are already in the trash; use empty_trash.")
        if (!ctx.confirm("Delete ${fs.size} item(s)?", summary(fs) + "\n\nThey go to the D-Agent Trash and can be put back for ${PhoneFiles.KEEP_DAYS} days.")) {
            return ToolResult.error("The person said no.")
        }
        var n = 0
        val failed = ArrayList<String>()
        fs.forEach { f -> runCatching { files.toTrash(f); n++ }.onFailure { failed += "${files.show(f)} (${it.message})" } }
        return ToolResult.ok("Moved $n item(s) to the Trash (${PhoneFiles.MOUNT}/${PhoneFiles.TRASH}); they're removed for good after ${PhoneFiles.KEEP_DAYS} days." +
            if (failed.isNotEmpty()) "\nCouldn't delete:\n" + failed.joinToString("\n") else "")
    }

    private fun trashList(limit: Int): String {
        val all = files.trash.walkTopDown().filter { it.isFile }.toList().sortedByDescending { it.lastModified() }
        if (all.isEmpty()) return "The Trash is empty."
        return clip("Trash: ${all.size} files, ${PhoneFiles.size(all.sumOf { it.length() })} (folders are the day they were deleted; " +
            "restore with these paths):\n" + all.take(limit).joinToString("\n") { line(it) }, 20_000)
    }

    private suspend fun restore(args: Args, ctx: ToolContext): ToolResult {
        val fs = targets(args).filter { it.exists() && it.path.startsWith(files.trash.canonicalPath + File.separator) }
        if (fs.isEmpty()) return ToolResult.error("Give paths inside ${PhoneFiles.MOUNT}/${PhoneFiles.TRASH} (see action trash).")
        if (!ctx.confirm("Put back ${fs.size} item(s)?", summary(fs))) return ToolResult.error("The person said no.")
        val back = fs.map { files.restore(it) }
        return ToolResult.ok("Put back:\n" + back.joinToString("\n") { files.show(it) })
    }

    private suspend fun emptyTrash(ctx: ToolContext): ToolResult {
        val all = files.trash.walkTopDown().filter { it.isFile }.toList()
        if (all.isEmpty()) return ToolResult.ok("The Trash is already empty.")
        if (!ctx.confirm("Empty the Trash for good?", "${all.size} files, ${PhoneFiles.size(all.sumOf { it.length() })}. This can't be undone.")) {
            return ToolResult.error("The person said no.")
        }
        files.trash.deleteRecursively()
        return ToolResult.ok("Emptied the Trash (${all.size} files).")
    }

    private fun copyOut(args: Args): ToolResult {
        val dest = workspaceUploads ?: return ToolResult.error("No workspace here.")
        val fs = targets(args).filter { it.isFile }
        if (fs.isEmpty()) return ToolResult.error("Give file paths (not folders).")
        if (fs.sumOf { it.length() } > 200L shl 20) return ToolResult.error("That's over 200 MB; pick fewer files.")
        dest.mkdirs()
        val out = fs.map { f -> PhoneFiles.unique(File(dest, f.name)).also { f.copyTo(it) } }
        return ToolResult.ok("Copied to the workspace:\n" + out.joinToString("\n") { "/workspace/uploads/" + it.name })
    }

    companion object {
        val ACTIONS = listOf("list", "search", "space", "duplicates", "mkdir", "move", "rename", "delete", "trash", "restore", "empty_trash", "copy_to_workspace")
    }
}
