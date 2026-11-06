package dev.dagent.core

import dev.dagent.core.agent.AgentEvent
import dev.dagent.core.agent.AgentLoop
import dev.dagent.core.agent.AgentOptions
import dev.dagent.core.agent.ContextLimits
import dev.dagent.core.agent.PythonicCalls
import dev.dagent.core.agent.TextToolProtocol
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.llm.ChatRequest
import dev.dagent.core.llm.LlmClient
import dev.dagent.core.llm.LlmEvent
import dev.dagent.core.llm.LlmException
import dev.dagent.core.llm.Msg
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    @Test fun readsLiquidToolCalls() {
        val p = TextToolProtocol.parse("Let me look.<|tool_call_start|>[web_fetch(url='https://academy.networkchuck.com/courses/network-design'), weather(place=\"Bay St. Louis, MS\", days=2, hourly=False)]<|tool_call_end|>")
        assertEquals("Let me look.", p.text)
        assertEquals(listOf("web_fetch", "weather"), p.calls.map { it.name })
        assertEquals("""{"url":"https://academy.networkchuck.com/courses/network-design"}""", p.calls[0].arguments)
        assertEquals("""{"place":"Bay St. Louis, MS","days":2,"hourly":false}""", p.calls[1].arguments)
        assertEquals("""{"q":"it's","tags":["a","b"]}""", PythonicCalls.parseList("[web_search(q='it\\'s', tags=['a', 'b'])]")[0].arguments)
    }

    @Test fun shrinksTheChatWhenTheModelRunsOutOfContext() = runBlocking {
        ContextLimits.clear()
        val sizes = mutableListOf<Int>()
        val model = object : LlmClient {
            override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
                val size = request.messages.sumOf { it.text.length }
                sizes += size
                if (size > 5_000) throw LlmException("HTTP 400", 400, """{"error":"The number of tokens to keep from the initial prompt is greater than the context length"}""")
                emit(LlmEvent.Text("ok"))
                emit(LlmEvent.Finish("stop"))
            }
            override suspend fun listModels() = emptyList<String>()
        }
        val history = (1..20).flatMap { listOf(Msg.user("question $it " + "x".repeat(900)), Msg.assistant("answer $it")) } + Msg.user("latest")
        val events = AgentLoop(model, "m", emptyList(), AgentOptions(contextChars = 240_000)).run("sys", history, object : ToolContext { override val sessionId = "t" }).toList()
        assertTrue(sizes.first() > 5_000)
        assertTrue(sizes.last() <= 5_000)
        assertTrue(events.any { it is AgentEvent.Message && it.msg.text == "ok" })
        assertTrue((ContextLimits.get("m") ?: Int.MAX_VALUE) < 240_000)
    }
}

class BrokenToolCallTest {
    @org.junit.Test fun spotsGarbledCalls() {
        val msg = "⚠ Could not execute tool(s): \"action\": value \"writeEND_ARG\\n@@ARG\" not in enum [\"run\",\"write\"]"
        org.junit.Assert.assertNotNull(dev.dagent.core.agent.brokenToolCall(msg))
        org.junit.Assert.assertNull(dev.dagent.core.agent.brokenToolCall("Here is your app. It's done."))
    }
}
