package dev.dagent.core

import dev.dagent.core.tools.FileDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileDigestTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun readsTextAndCode() {
        assertEquals("Joe's Pizza\nOpen 11-9", FileDigest.extract("menu.txt", "Joe's Pizza\nOpen 11-9".toByteArray()))
        assertTrue(FileDigest.extract("site.html", "<h1>Hi</h1>".toByteArray())!!.contains("<h1>Hi</h1>"))
    }

    @Test fun readsWordDocs() {
        val docx = zip("word/document.xml" to """<w:document><w:body><w:p><w:r><w:t>Deezi Detailing</w:t></w:r></w:p><w:p><w:r><w:t>Prices &amp; hours</w:t></w:r></w:p></w:body></w:document>""")
        assertEquals("Deezi Detailing\nPrices & hours", FileDigest.extract("Business.docx", docx))
    }

    @Test fun readsSlidesAndSheets() {
        val pptx = zip("ppt/slides/slide1.xml" to "<p:sld><a:t>Welcome</a:t><a:t>Our services</a:t></p:sld>")
        assertTrue(FileDigest.extract("deck.pptx", pptx)!!.contains("Our services"))
        val xlsx = zip("xl/sharedStrings.xml" to "<sst><si><t>Service</t></si><si><t>Price</t></si></sst>")
        assertTrue(FileDigest.extract("prices.xlsx", xlsx)!!.contains("Price"))
    }

    @Test fun readsSimplePdf() {
        val content = "BT /F1 12 Tf 72 712 Td (Deezi Detailing LLC) Tj T* [(Full ) -300 (detail $199)] TJ ET"
        val packed = ByteArrayOutputStream().also { DeflaterOutputStream(it, Deflater()).use { d -> d.write(content.toByteArray()) } }.toByteArray()
        val pdf = ByteArrayOutputStream().apply {
            write("%PDF-1.4\n1 0 obj << /Length ${packed.size} /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
            write(packed)
            write("\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
        val text = FileDigest.extract("info.pdf", pdf)!!
        assertTrue(text, text.contains("Deezi Detailing LLC") && text.contains("detail $199"))
    }

    @Test fun listsZipsAndSkipsBinaries() {
        assertTrue(FileDigest.extract("site.zip", zip("index.html" to "x", "logo.png" to "y"))!!.contains("index.html"))
        assertNull(FileDigest.extract("photo.bin", ByteArray(500) { (it % 7).toByte() }))
    }
}
