package dev.dagent.core

import dev.dagent.core.pals.PalFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The live Hub list (docs/hub/pals.json) must always decode, or the app shows no Dabs. */
class HubFileTest {
    @Test fun hubListDecodes() {
        val f = listOf(File("../docs/hub/pals.json"), File("docs/hub/pals.json")).first { it.isFile }
        val hub = PalFormat.decodeHub(f.readText())
        println("HUB_COUNT=" + hub.pals.size + " " + hub.pals.joinToString(",") { it.id })
        assertTrue(hub.pals.size >= 30)
        assertTrue(hub.pals.any { it.id == "ctf" } && hub.pals.any { it.id == "forge" })
    }
}
