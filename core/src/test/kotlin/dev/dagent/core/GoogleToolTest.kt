package dev.dagent.core

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Guard
import dev.dagent.core.agent.Risk
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.tools.GoogleTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleToolTest {
    private val ctx = object : ToolContext { override val sessionId = "s" }

    @Test fun gmailSearchUsesBearerAndListsMail() = runBlocking {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("""{"messages":[{"id":"m1"}]}"""))
        s.enqueue(MockResponse().setBody("""{"id":"m1","snippet":"Hi there","payload":{"headers":[{"name":"From","value":"Ann"},{"name":"Subject","value":"Lunch"}]}}"""))
        s.start()
        val base = s.url("/").toString().trimEnd('/')
        val tool = GoogleTool({ "tok" }, { false }, OkHttpClient(), gmailBase = base)
        val r = tool.run(Args(buildJsonObject { put("action", "gmail_search"); put("query", "from:ann") }), ctx)
        assertTrue(r.text, r.text.contains("Lunch") && r.text.contains("Ann"))
        assertEquals("Bearer tok", s.takeRequest().getHeader("Authorization"))
        s.shutdown()
    }

    @Test fun writesNeedOptInAndAreOutward() = runBlocking {
        val tool = GoogleTool({ "tok" }, { false }, OkHttpClient())
        val r = tool.run(Args(buildJsonObject { put("action", "gmail_draft"); put("to", "a@b.c") }), ctx)
        assertTrue(r.isError && r.text.contains("write access is off"))
        assertEquals(Risk.OUTWARD, Guard.risk("google", buildJsonObject { put("action", JsonPrimitive("calendar_create")) }))
        assertEquals(Risk.READ, Guard.risk("google", buildJsonObject { put("action", JsonPrimitive("gmail_read")) }))
    }
}
