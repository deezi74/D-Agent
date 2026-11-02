package dev.dagent.core

import dev.dagent.core.agent.Persona
import dev.dagent.core.agent.PromptBuilder
import dev.dagent.core.agent.Situation
import dev.dagent.core.llm.ChatRequest
import dev.dagent.core.llm.OpenAiClient
import dev.dagent.core.llm.Sampling
import dev.dagent.core.pals.BuiltinPals
import dev.dagent.core.pals.Pal
import dev.dagent.core.pals.PalFormat
import dev.dagent.core.pals.PalStore
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PalsTest {
    @Test fun sharedPalRoundTripsAndIsCleaned() {
        val text = PalFormat.encode(BuiltinPals.VOLT)
        val back = PalFormat.decode(text)
        assertEquals("Volt", back.name)
        assertFalse(back.builtin)
        assertEquals(BuiltinPals.VOLT.sampling, back.sampling)
        val weird = PalFormat.decode("""{"id":"../../evil","name":"  X  ","starters":["a","","b"],"extra":1}""")
        assertEquals("evil", weird.id)
        assertEquals("X", weird.name)
        assertEquals(listOf("a", "b"), weird.starters)
    }

    @Test fun storeKeepsBuiltinsAndMine() {
        val store = PalStore(Files.createTempDirectory("pals").toFile())
        val mine = store.save(Pal("fixer", "Fixer", instructions = "Fix things"))
        assertEquals(BuiltinPals.all.size + 1, store.all().size)
        assertEquals("Fixer", store.get("fixer")!!.name)
        val clash = store.save(BuiltinPals.BYTE.copy(name = "My Byte"))
        assertEquals("byte-mine", clash.id)
        store.delete(mine.id)
        assertNull(store.get("fixer"))
    }

    @Test fun builtinIdsAreUnique() {
        val ids = BuiltinPals.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun hubListParses() {
        val hub = PalFormat.decodeHub("""{"updated":"2026-11-02","pals":[{"id":"a","name":"A"},{"id":"b","name":"B","sampling":{"temperature":0.2}}]}""")
        assertEquals(2, hub.pals.size)
        assertEquals(0.2, hub.pals[1].sampling!!.temperature!!, 0.0)
    }

    @Test fun shippedHubListIsValid() {
        val f = listOf(java.io.File("../docs/hub/pals.json"), java.io.File("docs/hub/pals.json")).first { it.isFile }
        val hub = PalFormat.decodeHub(f.readText())
        assertTrue(hub.pals.size >= 5)
        val ids = hub.pals.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { id -> BuiltinPals.all.any { it.id == id } })
        assertTrue(hub.pals.all { it.instructions.isNotBlank() && it.starters.isNotEmpty() })
    }

    @Test fun roleGoesIntoThePrompt() {
        val p = Persona(name = "Volt", role = "You help with ESP32.")
        val prompt = PromptBuilder.build(p, Situation(), "", emptyList(), emptySet())
        assertTrue(prompt.contains("# Your role as Volt"))
        assertTrue(prompt.contains("ESP32"))
    }

    @Test fun extraKnobsOnlyGoToOwnServers() {
        val s = Sampling(temperature = 0.5, topK = 40, minP = 0.05, mirostat = 2, mirostatTau = 5.0, seed = 7, presencePenalty = 0.1)
        val req = ChatRequest("m", "sys", emptyList(), temperature = 0.5, sampling = s)
        val cloud = OpenAiClient(OkHttpClient(), "https://api.openai.com/v1", "k").buildBody(req)
        assertNull(cloud["top_k"])
        assertNull(cloud["mirostat"])
        assertEquals(JsonPrimitive(7L), cloud["seed"])
        val lan = OpenAiClient(OkHttpClient(), "http://192.168.1.113:1234/v1", "").buildBody(req)
        assertEquals(JsonPrimitive(40), lan["top_k"])
        assertEquals(JsonPrimitive(2), lan["mirostat"])
        assertEquals(JsonPrimitive(0.05), lan["min_p"])
    }

    @Test fun samplingOverrides() {
        val base = Sampling(temperature = 0.7, topK = 40)
        val merged = base.with(Sampling(temperature = 0.2))
        assertEquals(0.2, merged.temperature!!, 0.0)
        assertEquals(40, merged.topK)
    }
}
