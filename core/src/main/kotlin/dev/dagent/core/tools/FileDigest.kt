package dev.dagent.core.tools

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Pulls readable text out of a file someone attached in chat, so even a model without file tools
 * can use it: plain text and code, Word/OpenOffice documents, PowerPoint, Excel, simple PDFs, and
 * the file list of a zip. Anything else is just saved for the agent's tools.
 */
object FileDigest {
    private val TEXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "html", "htm", "xml", "css", "js", "jsx", "ts", "tsx", "kt", "kts", "java",
        "py", "rb", "php", "go", "rs", "c", "h", "cpp", "cs", "swift", "dart", "sh", "ps1", "bat", "yaml", "yml", "toml", "ini",
        "cfg", "conf", "env", "log", "sql", "svg", "vcf", "ics", "srt", "gradle", "properties", "rtf",
    )

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    /** Text to show the model, cut to [max] characters; null when there's nothing readable. */
    fun extract(name: String, bytes: ByteArray, max: Int = 15_000): String? = runCatching {
        val text = when (val e = ext(name)) {
            in TEXT -> String(bytes, Charsets.UTF_8).let { if (e == "rtf") rtf(it) else it }
            "docx" -> zipText(bytes) { it == "word/document.xml" }?.let(::wordXml)
            "odt", "odp", "ods" -> zipText(bytes) { it == "content.xml" }?.let(::wordXml)
            "pptx" -> zipText(bytes, all = true) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }?.let(::drawingXml)
            "xlsx" -> zipText(bytes) { it == "xl/sharedStrings.xml" }?.let(::drawingXml)
            "pdf" -> pdf(bytes)
            "zip" -> zipList(bytes)
            else -> if (looksLikeText(bytes)) String(bytes, Charsets.UTF_8) else null
        }
        text?.replace(Regex("[ \\t]+\\n"), "\n")?.replace(Regex("\\n{3,}"), "\n\n")?.trim()?.takeIf { it.isNotBlank() }?.let { clipText(it, max) }
    }.getOrNull()

    private fun clipText(s: String, max: Int) = if (s.length <= max) s else s.take(max) + "\n[... ${s.length - max} more characters; the whole file is saved in the workspace ...]"

    private fun looksLikeText(b: ByteArray): Boolean {
        val n = minOf(b.size, 4096)
        if (n == 0) return false
        val bad = (0 until n).count { val c = b[it].toInt() and 0xff; c == 0 || (c < 9) || (c in 14..31) }
        return bad * 100 / n < 2
    }

    private fun zipText(bytes: ByteArray, all: Boolean = false, want: (String) -> Boolean): String? {
        val parts = sortedMapOf<String, String>(compareBy<String>({ it.length }, { it }))
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (want(e.name)) {
                    parts[e.name] = String(z.readBytes(), Charsets.UTF_8)
                    if (!all) break
                }
            }
        }
        return parts.values.joinToString("\n\n").ifBlank { null }
    }

    private fun zipList(bytes: ByteArray): String {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (names.size < 300) names += e.name + if (e.isDirectory) "" else " (${e.size.takeIf { it >= 0 } ?: "?"} bytes)"
            }
        }
        return "Zip contents:\n" + names.joinToString("\n")
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: "" }
        .replace("&amp;", "&")

    private fun wordXml(xml: String): String = unescape(
        xml.replace(Regex("<w:tab\\s*/>|<text:tab\\s*/>"), "\t")
            .replace(Regex("</w:p>|<w:br\\s*/>|</text:p>|</text:h>|<text:line-break\\s*/>"), "\n")
            .replace(Regex("<[^>]+>"), "")
    )

    private fun drawingXml(xml: String): String {
        val out = StringBuilder()
        Regex("<(a:t|t)(\\s[^>]*)?>([^<]*)</(a:t|t)>").findAll(xml).forEach { out.append(unescape(it.groupValues[3])).append(' ') }
        return out.toString().replace(Regex("(</a:p>)"), "\n")
    }

    private fun rtf(s: String) = s.replace(Regex("\\\\par[d]?"), "\n").replace(Regex("\\\\[a-z]+-?\\d* ?"), "").replace(Regex("[\\{\\}]"), "")

    /**
     * Text from simple PDFs: inflates each content stream and reads the strings shown with Tj/TJ.
     * PDFs that use embedded font encodings (many scanned or designed ones) come out empty, and
     * then the agent is told to use the Linux computer's pdftotext instead.
     */
    fun pdf(bytes: ByteArray): String? {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder()
        var i = 0
        while (out.length < 200_000) {
            val s = raw.indexOf("stream", i).takeIf { it >= 0 } ?: break
            var start = s + 6
            if (raw.startsWith("\r\n", start)) start += 2 else if (raw.startsWith("\n", start)) start += 1
            val end = raw.indexOf("endstream", start).takeIf { it >= 0 } ?: break
            val dict = raw.substring(maxOf(0, s - 400), s)
            val data = bytes.copyOfRange(start, end)
            val content = if (dict.contains("FlateDecode")) inflate(data) else if (dict.contains("/Filter")) null else data
            content?.let { showText(String(it, Charsets.ISO_8859_1), out) }
            i = end + 9
        }
        return out.toString().trim().takeIf { it.count { c -> c.isLetter() } > 20 }
    }

    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inf = Inflater()
        inf.setInput(data)
        val buf = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 5_000_000) break
        }
        inf.end()
        out.toByteArray()
    }.getOrNull()

    private val TEXT_OP = Regex("\\[(.*?)\\]\\s*TJ|\\((.*?)(?<!\\\\)\\)\\s*Tj|\\((.*?)(?<!\\\\)\\)\\s*['\"]|(T\\*|Td|TD|ET)", RegexOption.DOT_MATCHES_ALL)
    private val PIECE = Regex("\\((.*?)(?<!\\\\)\\)|(-?\\d+\\.?\\d*)", RegexOption.DOT_MATCHES_ALL)

    private fun showText(stream: String, out: StringBuilder) {
        if (!stream.contains("Tj") && !stream.contains("TJ")) return
        for (m in TEXT_OP.findAll(stream)) {
            when {
                m.groupValues[1].isNotEmpty() -> PIECE.findAll(m.groupValues[1]).forEach { p ->
                    if (p.groupValues[1].isNotEmpty() || p.value.startsWith("(")) out.append(pdfString(p.groupValues[1]))
                    else if ((p.groupValues[2].toDoubleOrNull() ?: 0.0) < -200) out.append(' ')
                }
                m.groupValues[2].isNotEmpty() -> out.append(pdfString(m.groupValues[2]))
                m.groupValues[3].isNotEmpty() -> out.append('\n').append(pdfString(m.groupValues[3]))
                m.groupValues[4] == "ET" -> out.append('\n')
                m.groupValues[4].isNotEmpty() -> if (out.isNotEmpty() && out.last() != '\n' && out.last() != ' ') out.append(' ')
            }
        }
    }

    private fun pdfString(s: String): String = s
        .replace("\\n", "\n").replace("\\r", "").replace("\\t", " ")
        .replace(Regex("\\\\([0-7]{1,3})")) { it.groupValues[1].toInt(8).toChar().toString() }
        .replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")
}
