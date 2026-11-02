package dev.dagent.core

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Guard
import dev.dagent.core.agent.Risk
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.tools.PcClient
import dev.dagent.core.tools.PcTool
import dev.dagent.core.tools.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PcToolTest {
    private val ctx = object : ToolContext { override val sessionId = "t" }
    private fun args(s: String) = Args(Json.parseToJsonElement(s).jsonObject)

    @Test fun normalizesAddresses() {
        assertEquals("http://192.168.1.20:8787", PcClient.normalize("192.168.1.20"))
        assertEquals("http://192.168.1.20:8787", PcClient.normalize(" http://192.168.1.20:8787/ "))
        assertEquals("http://100.70.1.2:9000", PcClient.normalize("100.70.1.2:9000"))
        assertEquals("https://pc.tail1234.ts.net:8787", PcClient.normalize("https://pc.tail1234.ts.net"))
    }

    @Test fun wakesTheWorkerOnThisPhone() = runBlocking {
        assertTrue(PcClient.isOnThisPhone("127.0.0.1"))
        assertTrue(PcClient.isOnThisPhone("http://localhost:8787"))
        assertTrue(!PcClient.isOnThisPhone("192.168.1.113"))
        val port = java.net.ServerSocket(0).use { it.localPort }
        val server = MockWebServer()
        var woke = 0
        val client = PcClient({ "127.0.0.1:$port" }, { "x" }, wake = {
            woke++
            server.enqueue(MockResponse().setBody("""{"name":"phone","os":"Android 15 (Termux)"}"""))
            server.start(port)
            true
        })
        assertEquals("phone", (client.health()["name"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(1, woke)
        server.shutdown()
    }

    @Test fun startsTasksAndWatchesThem() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"abc123","title":"Research","status":"queued"}""").setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody("""{"id":"abc123","title":"Research","status":"done","result":"Found 3 options.","steps":[{"title":"web_search: tvs"}],"step_count":1}""").setHeader("Content-Type", "application/json"))
        val ws = Workspace(Files.createTempDirectory("ws").toFile())
        val watched = mutableListOf<String>()
        val tool = PcTool(PcClient({ server.url("/").toString() }, { "ABCD-EFGH-JKLM" }), ws) { id, _ -> watched += id }
        val r = tool.run(args("""{"action":"start_task","title":"Research","prompt":"Compare TVs"}"""), ctx)
        assertTrue(r.text, r.text.contains("abc123"))
        assertEquals(listOf("abc123"), watched)
        val req = server.takeRequest()
        assertEquals("/tasks", req.path)
        assertEquals("Bearer ABCD-EFGH-JKLM", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("Compare TVs"))
        val w = tool.run(args("""{"action":"wait","id":"abc123","minutes":1}"""), ctx)
        assertTrue(w.text, w.text.contains("Found 3 options.") && w.text.contains("web_search: tvs"))
        server.shutdown()
    }

    @Test fun explainsWrongCodeAndCopiesFiles() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Wrong code"}"""))
        server.enqueue(MockResponse().setBody("# Report\nAll good").setHeader("Content-Type", "application/octet-stream"))
        val dir = Files.createTempDirectory("ws").toFile()
        val tool = PcTool(PcClient({ server.url("/").toString() }, { "x" }), Workspace(dir))
        val bad = runCatching { tool.run(args("""{"action":"status"}"""), ctx) }
        assertTrue(bad.exceptionOrNull()?.message.orEmpty().contains("pairing code"))
        val f = tool.run(args("""{"action":"get_file","path":"reports\\summary.md"}"""), ctx)
        assertEquals("# Report\nAll good", dir.resolve("from-pc/summary.md").readText())
        assertEquals(1, f.files.size)
        assertTrue(server.takeRequest().path!!.startsWith("/health"))
        assertTrue(server.takeRequest().path!!.contains("path=reports"))
        server.shutdown()
    }

    @Test fun notSetUpSaysHow() = runBlocking {
        val r = PcTool(PcClient({ "" }, { "" }), Workspace(Files.createTempDirectory("ws").toFile())).run(args("""{"action":"status"}"""), ctx)
        assertTrue(r.isError && r.text.contains("Settings > Connectors > Your PC"))
    }

    @Test fun guardSortsPcSteps() {
        assertEquals(Risk.READ, Guard.risk("pc", Json.parseToJsonElement("""{"action":"wait"}""").jsonObject))
        assertEquals(Risk.LOCAL, Guard.risk("pc", Json.parseToJsonElement("""{"action":"run"}""").jsonObject))
    }
}
