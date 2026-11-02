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
