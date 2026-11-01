package dev.dagent.core

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.tools.AndroidAppTemplate
import dev.dagent.core.tools.AppBuilderTool
import dev.dagent.core.tools.GitHubClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBuilderTest {
    private val ctx = object : ToolContext { override val sessionId = "t" }

    @Test
    fun templateHasAWorkingSkeleton() {
        val files = AndroidAppTemplate.files("Tip \"Calc\"", "com.dom.tipcalc")
        assertTrue(files.containsKey("app/src/main/java/com/dom/tipcalc/MainActivity.kt"))
        assertTrue(files[".github/workflows/build.yml"]!!.contains("gradle --no-daemon :app:assembleRelease"))
        assertTrue(files["app/build.gradle.kts"]!!.contains("applicationId = \"com.dom.tipcalc\""))
        assertFalse(files.values.any { it.contains("TruAgent") })
    }

    @Test
    fun errorLinesArePulledFromLogs() {
        val log = "2026-10-31T10:00:00.000Z > Task :app:compileReleaseKotlin\n" +
            "2026-10-31T10:00:01.000Z e: file:///app/MainActivity.kt:12:5 Unresolved reference 'Foo'.\n" +
            "2026-10-31T10:00:02.000Z FAILURE: Build failed with an exception.\n"
        val out = AppBuilderTool.errorsFrom(log)
        assertTrue(out, out.contains("Unresolved reference 'Foo'."))
        assertFalse(out, out.contains("2026-10-31T"))
    }

    @Test
    fun phoneScriptDeletesItselfAndKeepsTokenOutOfTheRemote() {
        val s = AppBuilderTool.phoneScript("dom/tip-calc", "tok")
        assertTrue(s.startsWith("rm -f \"\$0\""))
        assertTrue(s.contains("clone https://github.com/dom/tip-calc.git tip-calc"))
        assertFalse(s.contains("tok@github.com"))
        assertTrue(s.contains("gradle-${AndroidAppTemplate.GRADLE}"))
    }

    @Test
    fun createsRepoCommitsTemplateAndRefusesOtherRepos() = runBlocking {
        val s = MockWebServer()
        val paths = mutableListOf<String>()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                synchronized(paths) { paths += "${request.method} $p" }
                return when {
                    p == "/user/repos" -> MockResponse().setBody("""{"full_name":"dom/tip-calc"}""")
                    p == "/repos/dom/tip-calc" -> MockResponse().setBody("""{"default_branch":"main"}""")
                    p == "/repos/dom/tip-calc/git/ref/heads/main" -> MockResponse().setBody("""{"object":{"sha":"h1"}}""")
                    p == "/repos/dom/tip-calc/git/commits/h1" -> MockResponse().setBody("""{"tree":{"sha":"t1"}}""")
                    p == "/repos/dom/tip-calc/git/trees" -> MockResponse().setBody("""{"sha":"t2"}""")
                    p == "/repos/dom/tip-calc/git/commits" -> MockResponse().setBody("""{"sha":"c2abcdef"}""")
                    p == "/repos/dom/tip-calc/git/refs/heads/main" -> MockResponse().setBody("""{"ref":"refs/heads/main"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        val owned = mutableSetOf<String>()
        val tool = AppBuilderTool(GitHubClient({ "t" }, base = s.url("/").toString()), { owned }, { owned += it })
        val r = tool.run(Args(buildJsonObject { put("action", JsonPrimitive("create")); put("app_name", JsonPrimitive("Tip Calc")) }), ctx)
        val other = tool.run(Args(buildJsonObject {
            put("action", JsonPrimitive("write")); put("repo", JsonPrimitive("dom/secret-work"))
            put("files", buildJsonArray { add(buildJsonObject { put("path", JsonPrimitive("a.txt")); put("content", JsonPrimitive("x")) }) })
        }), ctx)
        val escape = tool.run(Args(buildJsonObject {
            put("action", JsonPrimitive("write")); put("repo", JsonPrimitive("dom/tip-calc"))
            put("files", buildJsonArray { add(buildJsonObject { put("path", JsonPrimitive("../../x")); put("content", JsonPrimitive("x")) }) })
        }), ctx)
        val phone = tool.run(Args(buildJsonObject { put("action", JsonPrimitive("phone_build")) }), ctx)
        s.shutdown()
        assertFalse(r.text, r.isError)
        assertEquals(setOf("dom/tip-calc"), owned)
        assertTrue(r.text, r.text.contains("package com.dagent.tipcalc"))
        assertTrue(paths.contains("PATCH /repos/dom/tip-calc/git/refs/heads/main"))
        assertTrue(other.isError)
        assertTrue(escape.isError)
        assertTrue(phone.text, phone.isError && phone.text.contains("Termux"))
    }
}

class AiCallTest {
    @Test
    fun numbersBecomeE164() {
        assertEquals("+14045550100", dev.dagent.core.tools.AiCallClient.e164("(404) 555-0100"))
        assertEquals("+14045550100", dev.dagent.core.tools.AiCallClient.e164("1-404-555-0100"))
        assertEquals("+442071234567", dev.dagent.core.tools.AiCallClient.e164("+44 20 7123 4567"))
        assertEquals(null, dev.dagent.core.tools.AiCallClient.e164("555-0100"))
    }

    @Test
    fun callerSaysItsAnAi() {
        assertTrue(dev.dagent.core.tools.AiCallClient.task("Dominic", "ask if they have eggs").contains("AI assistant calling for Dominic"))
    }
}

class SlimToolTest {
    @Test
    fun slimDropsDescriptionsAndTitle() {
        val t = AppBuilderTool(GitHubClient({ "t" }), { emptySet() }, { })
        val slim = dev.dagent.core.agent.SlimTool(t)
        assertTrue(slim.description.length <= 180)
        assertFalse(slim.parameters.toString().contains("\"description\""))
        assertFalse(slim.parameters.toString().contains("\"title\""))
        assertTrue(slim.parameters.toString().length < t.parameters.toString().length / 2)
        assertEquals("app_builder", slim.name)
    }
}
