package dev.dagent.core

import dev.dagent.core.llm.Providers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextMessageTest {
    @Test fun explainsBlockedCleartext() {
        val msg = Providers.explainNetwork(
            "CLEARTEXT communication to 192.168.1.20 not permitted by network security policy",
            "http://192.168.1.20:1234/v1",
        )
        assertTrue(msg, msg.contains("https://"))
        assertTrue(msg, msg.contains("http://192.168.1.20:1234/v1"))
    }

    @Test fun otherMessagesUnchanged() {
        assertEquals("boom", Providers.explainNetwork("boom", "https://api.example.com/v1"))
    }
}
