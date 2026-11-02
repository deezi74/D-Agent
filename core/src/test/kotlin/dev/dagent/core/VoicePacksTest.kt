package dev.dagent.core

import dev.dagent.core.voice.VoicePackStore
import dev.dagent.core.voice.VoicePacks
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoicePacksTest {
    @Test fun keepsOnlyEnglishSpeechDictionary() {
        assertTrue(VoicePacks.wantEspeak("espeak-ng-data/en_dict"))
        assertTrue(VoicePacks.wantEspeak("espeak-ng-data/phontab"))
        assertTrue(VoicePacks.wantEspeak("espeak-ng-data/lang/gmw/en-US"))
        assertFalse(VoicePacks.wantEspeak("espeak-ng-data/ru_dict"))
        assertFalse(VoicePacks.wantEspeak("model.onnx"))
    }

    @Test fun voiceIdsAreUniqueAndEnglish() {
        val ids = VoicePacks.KOKORO.voices.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it in 0..27 })
        assertEquals((0..7).toSet(), VoicePacks.KITTEN.voices.map { it.id }.toSet())
    }

    @Test fun installsAPackWithItsDictionary() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                return when {
                    p.startsWith("/api/models/") -> MockResponse().setBody(
                        """[{"type":"directory","path":"espeak-ng-data/lang"},
                           {"type":"file","path":"espeak-ng-data/phontab","size":3},
                           {"type":"file","path":"espeak-ng-data/en_dict","size":3},
                           {"type":"file","path":"espeak-ng-data/de_dict","size":3}]"""
                    )
                    p.contains("/resolve/main/") -> MockResponse().setBody("ok:" + p.substringAfter("/resolve/main/"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val root = Files.createTempDirectory("packs").toFile()
        val store = VoicePackStore(root, OkHttpClient(), server.url("").toString().trimEnd('/'))
        val steps = mutableListOf<Int>()
        store.install(VoicePacks.KITTEN) { done, _, _ -> steps += done }
        assertTrue(store.installed(VoicePacks.KITTEN))
        val dir = File(root, "kitten")
        assertEquals("ok:tokens.txt", File(dir, "tokens.txt").readText())
        assertTrue(File(dir, "espeak-ng-data/en_dict").exists())
        assertFalse(File(dir, "espeak-ng-data/de_dict").exists())
        assertEquals(5, steps.last())
        server.shutdown()
    }
}
