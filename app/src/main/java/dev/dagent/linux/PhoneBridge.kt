package dev.dagent.linux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * A tiny HTTP endpoint on 127.0.0.1 that the Linux `phone` command posts to. A random token,
 * written into the Linux filesystem, keeps other apps on the phone out.
 */
class PhoneBridge(private val handler: suspend (String, Map<String, String>) -> String) {
    val token: String = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        Thread({
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: continue
                scope.launch { serve(socket) }
            }
        }, "phone-bridge").apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        socket.use { s -> serveOn(s) }
    }

    private fun serveOn(s: Socket) {
        s.soTimeout = 15_000
        val input = BufferedInputStream(s.getInputStream())
        val headers = mutableMapOf<String, String>()
        val requestLine = readLine(input) ?: return
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length.coerceIn(0, 1_000_000))
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        val (status, reply) = when {
            !requestLine.startsWith("POST /phone") -> 404 to "not found\n"
            headers["x-token"] != token -> 403 to "forbidden\n"
            else -> {
                val form = String(body, 0, read).split('&').filter { it.isNotEmpty() }.associate { pair ->
                    val i = pair.indexOf('=')
                    if (i < 0) dec(pair) to "" else dec(pair.substring(0, i)) to dec(pair.substring(i + 1))
                }
                val action = form["action"].orEmpty()
                200 to runCatching { runBlocking { handler(action, form - "action") } }.getOrElse { "error: ${it.message}" }.trimEnd() + "\n"
            }
        }
        val bytes = reply.toByteArray()
        val out = s.getOutputStream()
        out.write("HTTP/1.0 $status OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (c == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(c)
            if (buf.size() > 16_000) return buf.toString("UTF-8")
        }
    }
}
