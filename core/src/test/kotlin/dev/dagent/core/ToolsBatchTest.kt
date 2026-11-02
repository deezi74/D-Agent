package dev.dagent.core

import dev.dagent.core.llm.Bench
import dev.dagent.core.tools.Calc
import dev.dagent.core.tools.CalculateTool
import dev.dagent.core.tools.WebSearch
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsBatchTest {
    @Test fun calculatorDoesMath() {
        assertEquals(64.1679, Calc.eval("19.99*3*1.07"), 1e-9)
        assertEquals(12.6, Calc.eval("15% of 84"), 1e-9)
        assertEquals(1.0, Calc.eval("10 % 3"), 1e-9)
        assertEquals(2.15, Calc.eval("15% + 2"), 1e-9)
        assertEquals(1024.0, Calc.eval("2^10"), 1e-9)
        assertEquals(512.0, Calc.eval("2^3^2"), 1e-9)
        assertEquals(-4.0, Calc.eval("-(2+2)"), 1e-9)
        assertEquals(1.0, Calc.eval("sin(90)"), 1e-9)
        assertEquals(1234.5, Calc.eval("1,234.5"), 1e-9)
        assertEquals("1.41421356237", CalculateTool.format(Calc.eval("sqrt(2)")))
        assertEquals("64", CalculateTool.format(Calc.eval("8*8")))
        assertThrows(IllegalArgumentException::class.java) { Calc.eval("2/0") }
        assertThrows(IllegalArgumentException::class.java) { Calc.eval("hack(1)") }
        assertThrows(IllegalArgumentException::class.java) { Calc.eval("1+") }
    }

    @Test fun readsServerTimings() {
        val run = Bench.parse("""{"content":"x","timings":{"prompt_n":512,"prompt_per_second":88.5,"predicted_n":128,"predicted_per_second":12.25}}""")!!
        assertEquals(512, run.promptTokens)
        assertEquals(12.25, run.genPerSec, 0.0)
        assertEquals(null, Bench.parse("""{"content":"x"}"""))
        val r = Bench.Result(listOf(run, run.copy(genPerSec = 14.25)))
        assertEquals(13.25, r.tg, 1e-9)
        assertEquals(1.0, r.tgSpread, 1e-9)
    }

    @Test fun keyedSearchServices() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"results":[{"title":"A","url":"https://a.com","content":"aaa"}]}"""))
        server.enqueue(MockResponse().setBody("""{"web":{"results":[{"title":"<b>B</b>","url":"https://b.com","description":"bb <strong>b</strong>","age":"1 day"}]}}"""))
        server.start()
        val base = server.url("").toString().trimEnd('/')
        val ws = WebSearch(OkHttpClient())
        val t = ws.tavily("q", "k", 5, base)
        assertEquals("https://a.com", t.single().url)
        assertEquals("Bearer k", server.takeRequest().getHeader("Authorization"))
        val b = ws.brave("q", "k2", 5, base)
        assertEquals("B", b.single().title)
        assertEquals("bb b", b.single().snippet)
        assertTrue(server.takeRequest().path!!.startsWith("/res/v1/web/search?q=q"))
        server.shutdown()
    }
}
