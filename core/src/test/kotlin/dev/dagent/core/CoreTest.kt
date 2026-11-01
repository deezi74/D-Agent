package dev.dagent.core

import dev.dagent.core.agent.AgentEvent
import dev.dagent.core.agent.AgentLoop
import dev.dagent.core.agent.AgentOptions
import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Context
import dev.dagent.core.agent.MemoryStore
import dev.dagent.core.agent.SkillLibrary
import dev.dagent.core.agent.TextToolProtocol
import dev.dagent.core.agent.ThinkSplitter
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.llm.AnthropicClient
import dev.dagent.core.llm.ChatRequest
import dev.dagent.core.llm.LlmClient
import dev.dagent.core.llm.LlmEvent
import dev.dagent.core.llm.LlmException
import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.OpenAiClient
import dev.dagent.core.llm.Role
import dev.dagent.core.llm.ToolCall
import dev.dagent.core.tools.FileEditTool
import dev.dagent.core.tools.FileReadTool
import dev.dagent.core.tools.FileWriteTool
import dev.dagent.core.tools.WebFetchTool
import dev.dagent.core.tools.WebSearch
import dev.dagent.core.tools.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CoreTest {
    private val ctx = object : ToolContext { override val sessionId = "test" }

    @Test fun openAiStreamsTextAndToolCalls() = runBlocking {
        val server = MockWebServer()
        val sse = listOf(
            """{"choices":[{"delta":{"content":"Hel"}}]}""",
            """{"choices":[{"delta":{"content":"lo"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"web_search","arguments":"{\"que"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ry\":\"x\"}"}}]},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        server.start()
        val client = OpenAiClient(OkHttpClient(), server.url("/v1").toString(), "k")
        val events = client.stream(ChatRequest("m", "sys", listOf(Msg.user("hi")))).toList()
        val text = events.filterIsInstance<LlmEvent.Text>().joinToString("") { it.delta }
        assertEquals("Hello", text)
        val calls = events.filterIsInstance<LlmEvent.ToolCalls>().single().calls
        assertEquals("web_search", calls.single().name)
        assertEquals("""{"query":"x"}""", calls.single().arguments)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        server.shutdown()
    }

    @Test fun openAiErrorIsReadable() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.start()
        val client = OpenAiClient(OkHttpClient(), server.url("/v1").toString(), "k")
        val e = runCatching { client.stream(ChatRequest("m", "", listOf(Msg.user("hi")))).toList() }.exceptionOrNull()
        assertTrue(e is LlmException)
        assertTrue(e!!.message!!.contains("bad key"))
        server.shutdown()
    }

    @Test fun anthropicAlternatesRolesAndStreamsTools() = runBlocking {
        val msgs = listOf(
            Msg.user("hi"),
            Msg.assistant("", listOf(ToolCall("t1", "a", "{\"x\":1}"), ToolCall("t2", "b", "{}"))),
            Msg.tool(ToolCall("t1", "a", "{}"), "one"),
            Msg.tool(ToolCall("t2", "b", "{}"), "two"),
        )
        val enc = AnthropicClient.encodeMessages(msgs)
        assertEquals(3, enc.size)
        assertEquals(2, enc[2].jsonObject["content"]!!.jsonArray.size)

        val server = MockWebServer()
        val events = listOf(
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":5}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu","name":"shell"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"command\":"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"ls\"}"}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""",
        ).joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" }
        server.enqueue(MockResponse().setBody(events))
        server.start()
        val out = AnthropicClient(OkHttpClient(), server.url("/").toString(), "k").stream(ChatRequest("c", "s", listOf(Msg.user("x")))).toList()
        assertEquals("Hi", out.filterIsInstance<LlmEvent.Text>().joinToString("") { it.delta })
        assertEquals("""{"command":"ls"}""", out.filterIsInstance<LlmEvent.ToolCalls>().single().calls.single().arguments)
        assertEquals("/v1/messages", server.takeRequest().path)
        server.shutdown()
    }

    /** Scripted model: each call to stream() plays the next reply. */
    private class FakeModel(vararg replies: List<LlmEvent>) : LlmClient {
        val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<ChatRequest>()
        override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
            requests += request
            queue.removeFirst().forEach { emit(it) }
        }
        override suspend fun listModels() = emptyList<String>()
    }

    private val echo = object : Tool {
        override val name = "echo"
        override val description = "echo"
        override val parameters = schema { string("text", "t", required = true) }
        override suspend fun run(args: Args, ctx: ToolContext) = ToolResult.ok("echo:" + args.requireString("text"))
    }

    @Test fun loopRunsToolsThenAnswers() = runBlocking {
        val model = FakeModel(
            listOf(LlmEvent.Text("Let me check."), LlmEvent.ToolCalls(listOf(ToolCall("1", "echo", """{"title":"Echo it","text":"hi"}""")))),
            listOf(LlmEvent.Text("Done: hi")),
        )
        val events = AgentLoop(model, "m", listOf(echo)).run("sys", listOf(Msg.user("go")), ctx).toList()
        val msgs = events.filterIsInstance<AgentEvent.Message>().map { it.msg }
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), msgs.map { it.role })
        assertEquals("echo:hi", msgs[1].text)
        assertEquals("Echo it", events.filterIsInstance<AgentEvent.ToolStart>().single().title)
        assertEquals(4, model.requests[1].messages.size)
    }

    @Test fun loopStopsRepeatedCalls() = runBlocking {
        val call = { id: String -> listOf<LlmEvent>(LlmEvent.ToolCalls(listOf(ToolCall(id, "echo", """{"text":"same"}""")))) }
        val model = FakeModel(call("1"), call("2"), call("3"), call("4"), listOf(LlmEvent.Text("ok")))
        val events = AgentLoop(model, "m", listOf(echo), AgentOptions(repeatLimit = 3)).run("", listOf(Msg.user("x")), ctx).toList()
        val results = events.filterIsInstance<AgentEvent.ToolEnd>().map { it.result }
        assertTrue(results.last().isError)
    }

    @Test fun loopFallsBackToTextTools() = runBlocking {
        val model = object : LlmClient {
            var n = 0
            override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
                n++
                if (request.tools.isNotEmpty()) throw LlmException("no", 400, "tools are not supported")
                if (n == 2) emit(LlmEvent.Text("Sure <tool_call>{\"name\":\"echo\",\"arguments\":{\"text\":\"a\"}}</tool_call>"))
                else emit(LlmEvent.Text("finished"))
            }
            override suspend fun listModels() = emptyList<String>()
        }
        val events = AgentLoop(model, "m", listOf(echo)).run("", listOf(Msg.user("x")), ctx).toList()
        val shown = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(!shown.contains("tool_call"))
        assertEquals("echo:a", events.filterIsInstance<AgentEvent.ToolEnd>().single().result.text)
    }

    @Test fun textProtocolParsesAndFilters() {
        val p = TextToolProtocol.parse("Hi <tool_call>{\"name\":\"x\",\"arguments\":{\"a\":1}}</tool_call> bye")
        assertEquals("x", p.calls.single().name)
        assertEquals("Hi  bye", p.text)
        val f = TextToolProtocol.StreamFilter()
        val out = listOf("ab <tool", "_call>{secret}</tool", "_call> cd").joinToString("") { f.feed(it) } + f.flush()
        assertEquals("ab  cd", out)
    }

    @Test fun thinkSplitter() {
        val s = ThinkSplitter()
        val parts = listOf("<thi", "nk>hmm</th", "ink>Answer").map { s.feed(it) }
        assertEquals("Answer", parts.joinToString("") { it.first })
        assertEquals("hmm", parts.joinToString("") { it.second })
    }

    @Test fun contextTrimsWholeTurns() {
        val big = "x".repeat(5000)
        val msgs = (1..10).flatMap { listOf(Msg.user("q$it $big"), Msg.assistant("a$it")) }
        val fit = Context.fit(msgs, 20_000)
        assertTrue(fit.trimmed)
        assertEquals(Role.USER, fit.messages.first().role)
        assertTrue(fit.messages.last().text == "a10")
    }

    @Test fun fileToolsStayInWorkspace() = runBlocking {
        val dir = Files.createTempDirectory("ws").toFile()
        val ws = Workspace(dir)
        FileWriteTool(ws).run(Args(obj("""{"path":"/workspace/a/b.txt","content":"one two"}""")), ctx)
        FileEditTool(ws).run(Args(obj("""{"path":"a/b.txt","old_text":"two","new_text":"three"}""")), ctx)
        val read = FileReadTool(ws) { true }.run(Args(obj("""{"path":"a/b.txt"}""")), ctx)
        assertTrue(read.text.contains("one three"))
        assertTrue(runCatching { ws.resolve("../../etc/passwd") }.isFailure)
    }

    @Test fun memoryAndSkills() {
        val dir = Files.createTempDirectory("mem").toFile()
        val m = MemoryStore(dir)
        m.add("Likes black coffee")
        m.add("likes black coffee")
        assertEquals(1, m.facts().size)
        assertEquals(1, m.search("coffee").size)
        val lib = SkillLibrary(java.io.File(dir, "skills"))
        lib.save("Make Website", "Build a static site", "Steps...")
        assertEquals("make-website", lib.list().single().name)
        assertTrue(lib.read("make-website")!!.contains("Steps"))
    }

    @Test fun readableAndDuckUrls() {
        val html = "<html><head><title>T</title></head><body><nav>menu</nav><article><h1>Big</h1><p>Para <b>one</b></p><ul><li>item</li></ul></article></body></html>"
        val text = WebFetchTool.readable(html, "https://x.test", false)
        assertTrue(text.contains("# Big"))
        assertTrue(text.contains("Para one"))
        assertTrue(!text.contains("menu"))
        assertEquals("https://example.com/a", WebSearch.cleanDuckUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&rut=1"))
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
}
