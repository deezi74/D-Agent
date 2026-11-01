package dev.dagent.core.linux

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal tar(.gz) extractor for Linux root filesystems: regular files, folders,
 * symlinks, hard links (copied), GNU long names and pax path records. Symlinks and
 * permissions go through callbacks so Android can use Os.symlink / Os.chmod.
 * Paths that would escape [dest] are refused.
 */
class TarExtractor(
    private val symlink: (target: String, link: File) -> Unit,
    private val chmod: (file: File, mode: Int) -> Unit = { f, m -> if (m and 0b001_001_001 != 0) f.setExecutable(true, false) }
) {
    data class Result(val files: Int, val links: Int, val dirs: Int)

    fun extractGz(input: InputStream, dest: File): Result = GZIPInputStream(input.buffered(), 64 * 1024).use { extract(it, dest) }

    fun extract(input: InputStream, dest: File): Result {
        dest.mkdirs()
        val root = dest.canonicalFile
        val header = ByteArray(512)
        var files = 0
        var links = 0
        var dirs = 0
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        val pendingHardLinks = ArrayList<Pair<File, File>>()
        // Symlinks are created last, so no file is ever written through one (an
        // absolute link like /lib -> /usr/lib would otherwise point outside dest).
        val pendingSymlinks = ArrayList<Pair<String, File>>()
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it.toInt() == 0 }) break
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val prefix = string(header, 345, 155)
            val rawName = string(header, 0, 100)
            val name = paxPath ?: longName ?: if (prefix.isNotEmpty() && string(header, 257, 6).startsWith("ustar")) "$prefix/$rawName" else rawName
            val linkName = paxLink ?: longLink ?: string(header, 157, 100)
            val mode = octal(header, 100, 8).toInt()
            when (type) {
                'L' -> { longName = readString(input, size); continue }
                'K' -> { longLink = readString(input, size); continue }
                'x' -> {
                    val pax = parsePax(readString(input, size))
                    paxPath = pax["path"]
                    paxLink = pax["linkpath"]
                    continue
                }
                'g' -> { skip(input, size); continue }
            }
            longName = null; longLink = null; paxPath = null; paxLink = null
            val clean = name.trimStart('/').removePrefix("./")
            if (clean.isEmpty() || clean == ".") {
                skip(input, size)
                continue
            }
            val out = File(root, clean)
            if (!out.toPath().normalize().startsWith(root.toPath())) throw IOException("unsafe path in archive: $name")
            when (type) {
                '5' -> {
                    out.mkdirs()
                    chmod(out, mode)
                    dirs++
                    skip(input, size)
                }
                '2' -> {
                    pendingSymlinks += linkName to out
                    skip(input, size)
                }
                '1' -> {
                    pendingHardLinks += File(root, linkName.trimStart('/').removePrefix("./")) to out
                    skip(input, size)
                }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    if (isLink(out)) out.delete()
                    out.outputStream().use { copy(input, it, size) }
                    skipPadding(input, size)
                    chmod(out, mode)
                    files++
                }
                else -> skip(input, size) // devices, fifos: not needed in a rootfs under proot
            }
        }
        for ((source, target) in pendingHardLinks) {
            if (source.isFile) {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                if (source.canExecute()) target.setExecutable(true, false)
                files++
            }
        }
        for ((target, link) in pendingSymlinks) {
            if (link.isDirectory && !isLink(link)) continue
            link.parentFile?.mkdirs()
            if (link.exists() || isLink(link)) link.delete()
            symlink(target, link)
            links++
        }
        return Result(files, links, dirs)
    }

    private fun isLink(f: File): Boolean = java.nio.file.Files.isSymbolicLink(f.toPath())

    private fun parsePax(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val space = rest.indexOf(' ')
            if (space <= 0) break
            val len = rest.substring(0, space).toIntOrNull() ?: break
            if (len > rest.length) break
            val record = rest.substring(space + 1, len).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0) map[record.substring(0, eq)] = record.substring(eq + 1)
            rest = rest.substring(len)
        }
        return map
    }

    private fun readString(input: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        if (!readFully(input, bytes)) throw IOException("truncated archive")
        skipPadding(input, size)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("truncated archive")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        copy(input, java.io.OutputStream.nullOutputStream(), size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - size % 512) % 512).toInt()
        if (pad > 0) copy(input, java.io.OutputStream.nullOutputStream(), pad.toLong())
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return if (off == 0) false else throw IOException("truncated archive")
            off += n
        }
        return true
    }

    private fun string(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) { // GNU base-256
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        val s = string(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }
}
