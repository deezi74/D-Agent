package dev.dagent.core

import dev.dagent.core.tools.ManusClient
import dev.dagent.core.tools.MonthlyCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManusToolTest {
    private fun ev(t: Long, type: String, text: String = "", status: String? = null) = ManusClient.Event("e$t", type, t, text, status, null)

    @Test fun readsOnlyWhatFollowsOurTaggedMessage() {
        val events = listOf(
            ev(1, "user_message", "old job"), ev(2, "assistant_message", "old answer"), ev(3, "status_update", status = "stopped"),
            ev(4, "user_message", "call Home Depot [D-Agent abc]"), ev(5, "status_update", status = "running"),
        ).reversed()
        val running = ManusClient.outcome(events, "[D-Agent abc]")!!
        assertEquals(false, running.done)
        assertEquals("", running.reply)
        val done = ManusClient.outcome(events + listOf(ev(6, "assistant_message", "They have 2x4s."), ev(7, "status_update", status = "stopped")), "[D-Agent abc]")!!
        assertTrue(done.done)
        assertEquals("They have 2x4s.", done.reply)
        assertNull(ManusClient.outcome(events, "[D-Agent zzz]"))
    }

    @Test fun waitingIsReported() {
        val o = ManusClient.outcome(listOf(ev(1, "user_message", "x [t]"), ev(2, "status_update", status = "waiting")), "[t]")!!
        assertEquals("waiting", o.status)
        assertTrue(o.question!!.isNotBlank())
    }

    @Test fun monthlyCountResets() {
        var s = ""; var m = "2026-11"
        val c = MonthlyCount({ s }, { s = it }, { 2 }, { m })
        c.add(); c.add()
        assertEquals(0, c.left())
        m = "2026-12"
        assertEquals(2, c.left())
    }
}
