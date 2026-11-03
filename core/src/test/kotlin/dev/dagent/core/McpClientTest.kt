package dev.dagent.core

import dev.dagent.core.agent.AgentEvent
import dev.dagent.core.agent.AgentLoop
import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Guard
import dev.dagent.core.agent.Risk
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.connectors.AuthType
import dev.dagent.core.connectors.Connector
import dev.dagent.core.connectors.ConnectorRegistry
import dev.dagent.core.connectors.ConnectorState
import dev.dagent.core.connectors.ConnectorStatus
import dev.dagent.core.llm.ChatRequest
import dev.dagent.core.llm.LlmClient
import dev.dagent.core.llm.LlmEvent
import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.Role
import dev.dagent.core.llm.ToolCall
import dev.dagent.core.mcp.McpAuthException
import dev.dagent.core.mcp.McpClient
import dev.dagent.core.mcp.McpException
import dev.dagent.core.mcp.McpNames
import dev.dagent.core.mcp.McpServerConfig
import dev.dagent.core.mcp.McpToolDef
import dev.dagent.core.mcp.McpToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/** A small fake MCP server on MockWebServer: answers in JSON or SSE, checks session and token. */
class McpClientTest {
    private lateinit var server: MockWebServer
    private val seen = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    var sse = false
    var requireToken: String? = null
    var session = "s-1"
    var expireSessionOnce = false
    var hang = false

    private val tools = """
        [{"name":"search_notes","description":"Find notes","inputSchema":{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}},
         {"name":"create_note","description":"Make a note","inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}},
         {"name":"wipe","annotations":{"readOnlyHint":true},"inputSchema":{"type":"object"}}]
    """.trimIndent()

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                if (hang) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                requireToken?.let { if (request.getHeader("Authorization") != "Bearer $it") return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer realm=\"x\"") }
                if (request.method == "DELETE") return MockResponse().setResponseCode(200)
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val method = body["method"]!!.jsonPrimitive.content
                val id = body["id"]?.jsonPrimitive?.content
                if (method != "initialize" && request.getHeader("Mcp-Session-Id") != session) return MockResponse().setResponseCode(400).setBody("no session")
                if (method != "initialize" && expireSessionOnce && !method.startsWith("notifications/")) {
                    expireSessionOnce = false
                    session = "s-2"
                    return MockResponse().setResponseCode(404)
                }
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"notes","version":"0.1"}}"""
                    "notifications/initialized" -> return MockResponse().setResponseCode(202)
                    "tools/list" -> """{"tools":${tools.replace("\n", " ")}}"""
                    "tools/call" -> {
                        val p = body["params"]!!.jsonObject
                        val name = p["name"]!!.jsonPrimitive.content
                        if (name == "boom") return rpcError(id!!)
                        """{"content":[{"type":"text","text":"called $name with ${p["arguments"].toString().replace("\"", "'")}"}],"isError":false}"""
                    }
                    else -> return rpcError(id!!)
                }
                val msg = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
                val r = MockResponse()
                if (method == "initialize") r.setHeader("Mcp-Session-Id", session)
                return if (sse) r.setHeader("Content-Type", "text/event-stream").setBody(
                    // A notification and a server request first, then the answer split over two data lines.
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"ping\"}\n\n" +
                        ": keep-alive\n\n" +
                        "event: message\ndata: " + msg.replaceFirst(",\"result\"", "\ndata: ,\"result\"") + "\n\n"
                ) else r.setHeader("Content-Type", "application/json").setBody(msg)
            }
        }
        server.start()
    }

    private fun rpcError(id: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no such thing"}}""")

    @After fun stop() = server.shutdown()

    private fun client(token: String? = null, timeout: Long = 5_000) =
        McpClient(server.url("/mcp").toString(), OkHttpClient(), { token }, timeoutMs = timeout)

    @Test fun jsonHandshakeListAndCall() = runBlocking {
        val c = client()
        val info = c.initialize()
        assertEquals("notes", info.name)
        assertEquals("s-1", c.sessionId)
        val defs = c.listTools()
        assertEquals(listOf("search_notes", "create_note", "wipe"), defs.map { it.name })
        val r = c.callTool("search_notes", buildJsonObject { put("q", "milk") })
        assertEquals("called search_notes with {'q':'milk'}", r.text)
        assertFalse(r.isError)
        // initialize, initialized, tools/list, tools/call
        assertEquals(4, seen.size)
        assertNull(seen[0].getHeader("Mcp-Session-Id"))
        assertEquals("s-1", seen[1].getHeader("Mcp-Session-Id"))
        assertEquals("2025-06-18", seen[2].getHeader("MCP-Protocol-Version"))
        assertTrue(seen[0].getHeader("Accept")!!.contains("text/event-stream"))
    }

    @Test fun sseAnswersAreRead() = runBlocking {
        sse = true
        val c = client()
        assertEquals(3, c.listTools().size) // initializes by itself
        assertEquals("called wipe with {}", c.callTool("wipe", JsonObject(emptyMap())).text)
    }

    @Test fun bearerTokenIsSentAnd401IsAnAuthError() = runBlocking {
        requireToken = "abc"
        assertEquals("notes", client("abc").initialize().name)
        try { client("wrong").initialize(); fail() } catch (e: McpAuthException) { assertEquals(401, e.status) }
        assertTrue(seen.all { it.getHeader("Authorization") != null })
    }

    @Test fun expiredSessionStartsOverOnce() = runBlocking {
        val c = client()
        c.initialize()
        expireSessionOnce = true
        assertEquals(3, c.listTools().size)
        assertEquals("s-2", c.sessionId)
    }

    @Test fun rpcErrorsAndTimeouts() = runBlocking {
        val c = client(timeout = 700)
        c.initialize()
        try { c.callTool("boom", JsonObject(emptyMap())); fail() } catch (e: McpException) { assertEquals(-32601, e.code); assertTrue(e.message!!.contains("no such thing")) }
        hang = true
        val t = System.nanoTime()
        try { c.listTools(); fail() } catch (e: McpException) { assertTrue(e.message!!, e.message!!.contains("Network")) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t) < 4_000)
    }

    @Test fun namesAndRisk() {
        assertEquals("mcp_my_notes_search_notes", McpNames.toolName("My Notes!", "search-notes"))
        val long = McpNames.toolName("server", "x".repeat(100))
        assertTrue(long.length <= 64 && Regex("^[a-z0-9_]+$").matches(long))
        fun def(n: String, ro: Boolean? = null) = McpToolDef(n, "", JsonObject(emptyMap()), readOnlyHint = ro)
        assertEquals(Risk.READ, McpNames.risk(def("list_files")))
        assertEquals(Risk.READ, McpNames.risk(def("getUser")))
        assertEquals(Risk.OUTWARD, McpNames.risk(def("send_email")))
        assertEquals(Risk.OUTWARD, McpNames.risk(def("get_and_delete", ro = false)))
        assertEquals(Risk.READ, McpNames.risk(def("purge", ro = true)))
        assertEquals("Give the server a name.", McpServerConfig("1", "", "https://x").validate())
        assertTrue(McpServerConfig("1", "a", "http://example.com").validate()!!.contains("https"))
        assertNull(McpServerConfig("1", "a", "https://example.com/mcp").validate())
    }

    private class Scripted(val calls: List<ToolCall>) : LlmClient {
        var n = 0
        override fun stream(request: ChatRequest): Flow<LlmEvent> = flow { if (n++ == 0) emit(LlmEvent.ToolCalls(calls)) else emit(LlmEvent.Text("ok")) }
        override suspend fun listModels() = emptyList<String>()
    }

    /** The whole path: registry -> provider -> tools -> AgentLoop -> Guard -> confirm -> server. */
    @Test fun registryToolsRunThroughTheGuard() = runBlocking {
        var cfg = McpServerConfig("n1", "Notes", server.url("/mcp").toString(), disabledTools = setOf("wipe"))
        val provider = McpToolProvider({ cfg }, client())
        val reg = ConnectorRegistry()
        reg.register(Connector("mcp.n1", "Notes", AuthType.NONE, provider = provider))
        assertTrue(reg.tools(true).isEmpty()) // nothing discovered yet
        reg.refresh()
        val tools = reg.tools(true)
        assertEquals(listOf("mcp_notes_search_notes", "mcp_notes_create_note"), tools.map { it.name })
        assertTrue(tools[0].readOnly)
        assertEquals(Risk.OUTWARD, tools[1].declaredRisk)
        assertTrue((tools[0].parameters["properties"] as JsonObject).containsKey("title"))

        val asked = mutableListOf<String>()
        var answer = false
        val ctx = object : ToolContext {
            override val sessionId = "s"
            override suspend fun confirm(title: String, detail: String): Boolean { asked += title; return answer }
        }
        val calls = listOf(
            ToolCall("1", "mcp_notes_search_notes", """{"q":"milk","title":"Find milk"}"""),
            ToolCall("2", "mcp_notes_create_note", """{"text":"buy milk"}"""),
        )
        val out = AgentLoop(Scripted(calls), "m", tools, guard = Guard()).run("s", listOf(Msg.user("x")), ctx).toList()
        val results = out.filterIsInstance<AgentEvent.Message>().map { it.msg }.filter { it.role == Role.TOOL }.map { it.text }
        assertEquals("called search_notes with {'q':'milk'}", results[0]) // the title isn't sent on
        assertTrue(results[1], results[1].contains("said no"))
        assertEquals(1, asked.size)

        // Nobody watching: the guard holds write-like MCP tools before they even ask.
        asked.clear()
        val bg = AgentLoop(Scripted(listOf(calls[1])), "m", tools, guard = Guard(background = true)).run("s", listOf(Msg.user("x")), ctx).toList()
        val held = bg.filterIsInstance<AgentEvent.Message>().map { it.msg }.first { it.role == Role.TOOL }.text
        assertTrue(held, held.contains("nobody is watching"))
        assertTrue(asked.isEmpty())

        // Connector off: no tools.
        reg.register(Connector("mcp.n1", "Notes", AuthType.NONE, provider = provider, status = { ConnectorStatus.OFF }))
        assertTrue(reg.tools(true).isEmpty())
        assertEquals(ConnectorState.OFF, reg.status("mcp.n1").state)
    }

    @Test fun refreshFailureIsReportedAsError() = runBlocking {
        requireToken = "abc"
        val cfg = McpServerConfig("n1", "Notes", server.url("/mcp").toString())
        val reg = ConnectorRegistry()
        reg.register(Connector("mcp.n1", "Notes", AuthType.API_KEY, provider = McpToolProvider({ cfg }, client("nope"))))
        reg.refresh()
        assertEquals(ConnectorState.ERROR, reg.status("mcp.n1").state)
        assertTrue(reg.tools(true).isEmpty())
    }

    @Test fun argsPassThrough() {
        // Args helper used by tools must keep nested JSON as-is.
        val a = Args(buildJsonObject { put("q", JsonPrimitive("x")) })
        assertEquals("x", a.string("q"))
    }
}
