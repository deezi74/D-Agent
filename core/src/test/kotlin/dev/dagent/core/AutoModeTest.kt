package dev.dagent.core

import dev.dagent.core.agent.AutoMode
import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoModeTest {
    private fun agent(t: String, files: Int = 0, history: List<Msg> = emptyList()) = AutoMode.decide(t, files, history).agent

    @Test fun smallTalkAndQuestionsStayInChat() {
        assertFalse(agent("hey"))
        assertFalse(agent("Thanks!"))
        assertFalse(agent("Explain black holes like I'm twelve"))
        assertFalse(agent("What's the weather tomorrow?"))
        assertFalse(agent("Give me a hot take on pineapple pizza"))
    }

    @Test fun tasksGoToAgent() {
        assertTrue(agent("Call Waffle House and ask if they have eggs"))
        assertTrue(agent("Build me an app for my business"))
        assertTrue(agent("Set an alarm for 6:30"))
        assertTrue(agent("Order me a large pizza from Domino's"))
        assertTrue(agent("On my PC, research the best 65 inch TVs"))
        assertTrue(agent("Start freellmapi in termux"))
        assertTrue(agent("Remind me every morning to drink water"))
        assertTrue(agent("whats this", files = 1))
    }

    @Test fun followUpsToWorkStayInAgent() {
        val history = listOf(Msg.user("make a website"), Msg.assistant("", listOf(ToolCall("1", "file_write", "{}"))))
        assertTrue(agent("now make the header blue", history = history))
        assertFalse(agent("now make the header blue"))
    }
}

class NoThinkTest {
    private val client = dev.dagent.core.llm.OpenAiClient(okhttp3.OkHttpClient(), "http://x/v1", "", emptyMap(), dev.dagent.core.llm.ReasoningStyle.NONE)
    private fun sys(model: String, think: Boolean) = client.buildBody(dev.dagent.core.llm.ChatRequest(model, "Be nice.", listOf(Msg.user("hi")), think = think)).toString()

    @Test fun qwen3SkipsThinkingWhenOff() {
        assertTrue(sys("qwen/qwen3-14b", think = false).contains("/no_think"))
        assertFalse(sys("qwen/qwen3-14b", think = true).contains("/no_think"))
        assertFalse(sys("qwen3-30b-a3b-instruct-2507", think = false).contains("/no_think"))
        assertFalse(sys("llama-3.2-3b", think = false).contains("/no_think"))
    }
}
