package dev.dagent.core

import dev.dagent.core.tools.ReverseTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseToolTest {
    @Test fun pathsStayInWorkspace() {
        assertEquals("/workspace/uploads/app.apk", ReverseTool.filePath("uploads/app.apk"))
        assertEquals("/workspace/a/b.so", ReverseTool.filePath("/workspace/a/b.so"))
        assertNull(ReverseTool.filePath("/workspace/../etc/passwd"))
        assertNull(ReverseTool.filePath("/etc/passwd"))
        assertNull(ReverseTool.filePath("x'; rm -rf /; '"))
    }

    @Test fun buildsCommands() {
        val p = "/workspace/uploads/crackme"
        assertTrue(ReverseTool.command("decompile", p, "main", 100)!!.contains("s main;af;pdc"))
        assertNull(ReverseTool.command("disasm", p, "", 100))
        assertTrue(ReverseTool.command("apk", "/workspace/uploads/app.apk", "", 100)!!.contains("/workspace/re/app_apk"))
        assertTrue(ReverseTool.command("strings", p, "password", 50)!!.contains("grep -i -F -- 'password'"))
    }
}

class ApiPathTest {
    @Test fun addsV1WhenThereIsNoPath() {
        assertEquals("http://192.168.1.113:1234/v1", dev.dagent.core.llm.withApiPath("http://192.168.1.113:1234"))
        assertEquals("http://localhost:3001/v1", dev.dagent.core.llm.withApiPath("http://localhost:3001/v1"))
        assertEquals("https://api.groq.com/openai/v1", dev.dagent.core.llm.withApiPath("https://api.groq.com/openai/v1"))
    }
}
