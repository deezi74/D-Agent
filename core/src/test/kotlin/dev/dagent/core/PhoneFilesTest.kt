package dev.dagent.core

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.tools.PhoneFiles
import dev.dagent.core.tools.PhoneFilesTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhoneFilesTest {
    private val root = Files.createTempDirectory("sdcard").toFile()
    private var now = 1_800_000_000_000L
    private val files = PhoneFiles(root) { now }
    private val tool = PhoneFilesTool(files, { null }, File(root.parentFile, root.name + "-ws"))
    private var allow = true
    private val ctx = object : ToolContext {
        override val sessionId = "t"
        override suspend fun confirm(title: String, detail: String) = allow
    }
    private fun run(json: String) = runBlocking { tool.run(Args(Json.parseToJsonElement(json).jsonObject), ctx) }
    private fun put(path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun blocksPrivateAndOutsidePaths() {
        assertTrue(run("""{"action":"list","path":"/sdcard/Android/data"}""").isError)
        assertTrue(run("""{"action":"list","path":"/sdcard/../etc"}""").isError)
        assertTrue(run("""{"action":"list","path":"/etc"}""").isError)
    }

    @Test fun deleteGoesToTrashAndRestores() {
        put("Download/a.jpg", "photo")
        allow = false
        assertTrue(run("""{"action":"delete","paths":["/sdcard/Download/a.jpg"]}""").isError)
        assertTrue(File(root, "Download/a.jpg").exists())
        allow = true
        assertFalse(run("""{"action":"delete","paths":["/sdcard/Download/a.jpg"]}""").isError)
        assertFalse(File(root, "Download/a.jpg").exists())
        val trashed = files.trash.walkTopDown().first { it.isFile }
        assertEquals("photo", trashed.readText())
        assertTrue(run("""{"action":"search","query":"a.jpg"}""").text.startsWith("0 files"))
        run("""{"action":"restore","paths":["${files.show(trashed)}"]}""")
        assertTrue(File(root, "Download/a.jpg").exists())
    }

    @Test fun oldTrashIsPurged() {
        put("x.txt", "1")
        files.toTrash(File(root, "x.txt").canonicalFile)
        now += 31L * 24 * 3600 * 1000
        assertEquals(1, files.purgeOldTrash())
    }

    @Test fun findsDuplicatesAndMoves() {
        put("DCIM/one.jpg", "same-bytes-same-bytes")
        put("Download/copy.jpg", "same-bytes-same-bytes")
        put("Download/other.jpg", "different-bytes-here!")
        assertTrue(run("""{"action":"duplicates"}""").text.contains("1 sets"))
        run("""{"action":"move","paths":["/sdcard/Download/other.jpg"],"to":"/sdcard/Pictures/Sorted"}""")
        assertTrue(File(root, "Pictures/Sorted/other.jpg").exists())
        assertTrue(run("""{"action":"space"}""").text.contains("images"))
    }
}
