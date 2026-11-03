package dev.dagent.core

import dev.dagent.core.agent.MemoryStore
import dev.dagent.core.agent.Persona
import dev.dagent.core.agent.PromptBuilder
import dev.dagent.core.agent.Situation
import dev.dagent.core.agent.SkillLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

class PromptCacheTest {
    @Test fun changingFactsOnlyAffectTheEnd() {
        val a = PromptBuilder.build(Persona(), Situation(now = ZonedDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneId.of("UTC")), place = "Austin", device = "Pixel"), "- likes tea", emptyList(), setOf("shell"), extra = "note A")
        val b = PromptBuilder.build(Persona(), Situation(now = ZonedDateTime.of(2026, 3, 5, 23, 0, 0, 0, ZoneId.of("America/Chicago")), place = "Dallas", device = "Galaxy"), "- likes tea", emptyList(), setOf("shell"), extra = "note B")
        val common = a.commonPrefixWith(b)
        val cut = a.indexOf("# Right now")
        assertTrue(cut > 0)
        assertTrue("prefix ${common.length} should reach $cut", common.length >= cut)
        assertTrue(a.trimEnd().endsWith("note A"))
    }

    @Test fun memoryAndSkillIndexAreCachedUntilFilesChange() {
        val dir = Files.createTempDirectory("pc").toFile()
        val mem = MemoryStore(java.io.File(dir, "m"))
        mem.add("one")
        val first = mem.forPrompt()
        assertSame(first, mem.forPrompt())
        mem.add("two")
        assertTrue(mem.forPrompt().contains("two"))

        val skills = SkillLibrary(java.io.File(dir, "s"))
        skills.save("alpha", "first", "body")
        val l1 = skills.list()
        assertSame(l1, skills.list())
        skills.save("beta", "second", "body")
        val l2 = skills.list()
        assertNotSame(l1, l2)
        assertEquals(listOf("alpha", "beta"), l2.map { it.name })
        skills.delete("alpha")
        assertEquals(listOf("beta"), skills.list().map { it.name })
        dir.deleteRecursively()
    }
}
