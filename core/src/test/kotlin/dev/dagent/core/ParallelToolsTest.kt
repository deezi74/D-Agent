package dev.dagent.core

import dev.dagent.core.agent.AgentEvent
import dev.dagent.core.agent.AgentLoop
import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.llm.ChatRequest
import dev.dagent.core.llm.LlmClient
import dev.dagent.core.llm.LlmEvent
import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.Role
import dev.dagent.core.llm.ToolCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ParallelToolsTest {
    /** Records how many of its kind run at once. */
    private class Probe(override val name: String, override val readOnly: Boolean, val wait: Long, val live: AtomicInteger, val peak: AtomicInteger, val order: MutableList<String>) : Tool {
        override val description = name
        override val parameters = schema { string("q", "q") }
        override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
            val now = live.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            delay(wait)
            live.decrementAndGet()
            synchronized(order) { order += "$name:${args.string("q")}" }
            return ToolResult.ok("$name ${args.string("q")}")
        }
    }

    /** First reply: the given calls; second reply: plain text. */
    private class Scripted(val calls: List<ToolCall>) : LlmClient {
        var n = 0
        override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
            if (n++ == 0) emit(LlmEvent.ToolCalls(calls)) else emit(LlmEvent.Text("done"))
        }
        override suspend fun listModels() = emptyList<String>()
    }

    private val ctx = object : ToolContext { override val sessionId = "t" }

    private fun run(calls: List<ToolCall>, tools: List<Tool>): List<AgentEvent> = runBlocking {
        AgentLoop(Scripted(calls), "m", tools).run("sys", listOf(Msg.user("go")), ctx).toList()
    }

    @Test fun readOnlyCallsRunTogetherAndKeepOrder() {
        val live = AtomicInteger(); val peak = AtomicInteger(); val order = mutableListOf<String>()
        val slow = Probe("look", true, 300, live, peak, order)
        val calls = listOf(ToolCall("1", "look", "{\"q\":\"a\"}"), ToolCall("2", "look", "{\"q\":\"b\"}"), ToolCall("3", "look", "{\"q\":\"c\"}"))
        val start = System.currentTimeMillis()
        val events = run(calls, listOf(slow))
        val took = System.currentTimeMillis() - start
        assertEquals(3, peak.get())
        assertTrue("took $took ms", took < 800)
        val results = events.filterIsInstance<AgentEvent.Message>().map { it.msg }.filter { it.role == Role.TOOL }.map { it.text }
        assertEquals(listOf("look a", "look b", "look c"), results)
        val ends = events.filterIsInstance<AgentEvent.ToolEnd>().map { it.call.id }
        assertEquals(listOf("1", "2", "3"), ends)
    }

    @Test fun writeToolsRunAloneAndSplitBatches() {
        val live = AtomicInteger(); val peak = AtomicInteger(); val order = mutableListOf<String>()
        val read = Probe("look", true, 100, live, peak, order)
        val write = Probe("act", false, 100, live, peak, order)
        val calls = listOf(
            ToolCall("1", "look", "{\"q\":\"a\"}"), ToolCall("2", "act", "{\"q\":\"x\"}"),
            ToolCall("3", "look", "{\"q\":\"b\"}"), ToolCall("4", "act", "{\"q\":\"y\"}"), ToolCall("5", "act", "{\"q\":\"z\"}"),
        )
        val loop = AgentLoop(Scripted(calls), "m", listOf(read, write))
        assertEquals(listOf(listOf("1"), listOf("2"), listOf("3"), listOf("4"), listOf("5")), loop.batches(calls).map { b -> b.map { it.id } })
        run(calls, listOf(read, write))
        assertEquals(1, peak.get())
        assertEquals(listOf("look:a", "act:x", "look:b", "act:y", "act:z"), order)
    }

    @Test fun batchesGroupOnlyNeighbours() {
        val live = AtomicInteger(); val peak = AtomicInteger(); val order = mutableListOf<String>()
        val read = Probe("look", true, 1, live, peak, order)
        val write = Probe("act", false, 1, live, peak, order)
        val calls = listOf("look", "look", "act", "look", "nope", "look").mapIndexed { i, n -> ToolCall("$i", n, "{}") }
        val loop = AgentLoop(Scripted(calls), "m", listOf(read, write))
        assertEquals(listOf(listOf("0", "1"), listOf("2"), listOf("3"), listOf("4"), listOf("5")), loop.batches(calls).map { b -> b.map { it.id } })
    }
}
