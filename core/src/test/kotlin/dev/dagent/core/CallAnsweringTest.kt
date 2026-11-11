package dev.dagent.core

import dev.dagent.core.calls.CallAnswering
import dev.dagent.core.calls.CallRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallAnsweringTest {
    private val rules = CallRules(on = true, allow = listOf("(251) 555-0142", "+1 251 555 0199"), ownerName = "Dominic")

    @Test fun numbersMatchAcrossFormats() {
        assertTrue(CallAnswering.sameNumber("+12515550142", "251-555-0142"))
        assertTrue(CallAnswering.sameNumber("12515550142", "(251) 555-0142"))
        assertFalse(CallAnswering.sameNumber("2515550142", "2515550143"))
        assertFalse(CallAnswering.sameNumber("55501", "2515555501"))
        assertTrue(CallAnswering.sameNumber("55501", "55501"))
        assertEquals(listOf("251 555 0100", "+1 (850) 555-0101"), CallAnswering.parseList("251 555 0100,\n+1 (850) 555-0101; x"))
    }

    @Test fun answersOnlyWhoTheRulesAllow() {
        assertTrue(CallAnswering.decide(rules, "+1 251-555-0142", inContacts = false).answer)
        assertFalse(CallAnswering.decide(rules, "2515550000", inContacts = true).answer)
        assertFalse(CallAnswering.decide(rules, null, inContacts = false).answer)
        assertFalse(CallAnswering.decide(rules.copy(on = false), "2515550142", inContacts = false).answer)
        val contacts = rules.copy(who = CallRules.WHO_CONTACTS)
        assertTrue(CallAnswering.decide(contacts, "2515550000", inContacts = true).answer)
        assertTrue(CallAnswering.decide(contacts, "2515550199", inContacts = false).answer)
        assertFalse(CallAnswering.decide(contacts, "2515550000", inContacts = false).answer)
        val all = rules.copy(who = CallRules.WHO_EVERYONE)
        assertTrue(CallAnswering.decide(all, "9995550000", inContacts = false).answer)
        assertTrue(CallAnswering.decide(all, "", inContacts = false).answer)
    }

    @Test fun greetingAlwaysSaysItIsAnAi() {
        assertTrue(CallAnswering.greeting(rules).contains("AI assistant answering for Dominic"))
        val custom = CallAnswering.greeting(rules.copy(greeting = "Dominic is driving, leave a message."))
        assertTrue(custom.startsWith("Hi, you've reached D-Agent, an AI assistant."))
        val own = "Hey, this is Dominic's AI. What's up?"
        assertEquals(own, CallAnswering.greeting(rules.copy(greeting = own)))
    }

    @Test fun repliesAreCleanedAndHangUpIsFound() {
        val r = CallAnswering.parseReply("<think>they said bye</think>**Got it**, I'll tell him. Bye! [HANGUP]")
        assertEquals("Got it, I'll tell him. Bye!", r.say)
        assertTrue(r.hangUp)
        assertFalse(CallAnswering.parseReply("Who's calling, please?").hangUp)
        assertTrue(CallAnswering.parseReply("Bye [hang up]").hangUp)
    }

    @Test fun spotsAPlainGoodbye() {
        assertTrue(CallAnswering.callerSaysBye("Okay, thanks, bye!"))
        assertTrue(CallAnswering.callerSaysBye("goodbye"))
        assertTrue(CallAnswering.callerSaysBye("Talk to you later."))
        assertFalse(CallAnswering.callerSaysBye("Tell him bye from Sarah and call me back"))
    }

    @Test fun promptKeepsTheGuardrails() {
        val p = CallAnswering.systemPrompt(rules.copy(notes = "At work until 5"), "Mom", "Tue 3:10 PM")
        assertTrue(p.contains("Never pretend to be Dominic"))
        assertTrue(p.contains(CallAnswering.HANGUP))
        assertTrue(p.contains("At work until 5"))
        assertTrue(p.contains("911"))
    }
}
