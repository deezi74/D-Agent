package dev.dagent.core.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A tool as an MCP server describes it in tools/list. */
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val title: String? = null,
    /** From the tool's annotations; null when the server didn't say. */
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

data class McpServerInfo(val name: String, val version: String, val protocolVersion: String, val instructions: String = "")

/** A tools/call result: text parts joined, images as base64 with their type. */
data class McpCallResult(val text: String, val isError: Boolean, val images: List<Pair<String, String>> = emptyList())

open class McpException(message: String, val status: Int = 0, val code: Int? = null) : IOException(message)

/** The server wants (new) credentials: 401, or 403 with an insufficient-scope challenge. */
class McpAuthException(message: String, status: Int, val wwwAuthenticate: String?) : McpException(message, status)

/**
 * A client for one MCP server over the Streamable HTTP transport (MCP 2025-06-18, also accepted
 * by 2025-03-26 servers). Every JSON-RPC message is a POST to the one endpoint; the server
 * answers with either one JSON body or an SSE stream that carries the response (possibly after
 * notifications or requests of its own, which are ignored). The session id from `initialize`
 * (Mcp-Session-Id) rides on every later request; if the server forgets it (404) the client
 * starts a new session once and retries.
 *
 * [token] is asked for a bearer token before every request (so refreshed OAuth tokens are used
 * as soon as they exist); null or blank sends no Authorization header.
 */
class McpClient(
    val url: String,
    private val http: OkHttpClient,
    private val token: suspend () -> String? = { null },
    private val timeoutMs: Long = 60_000,
    private val clientName: String = "D-Agent",
    private val clientVersion: String = "1.0",
    private val extraHeaders: Map<String, String> = emptyMap(),
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val ids = AtomicLong(0)
    private val lock = Mutex()
    @Volatile var sessionId: String? = null
        private set
    @Volatile var protocolVersion: String = PROTOCOL_VERSION
        private set
    @Volatile var serverInfo: McpServerInfo? = null
        private set

    /** Starts a session: initialize, then the initialized notification. Safe to call again. */
    suspend fun initialize(): McpServerInfo = lock.withLock { initializeLocked() }

    private suspend fun initializeLocked(): McpServerInfo {
        sessionId = null
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", clientName); put("version", clientVersion) })
        }
        val (result, headers) = exchange("initialize", params, withSession = false)
        headers["mcp-session-id"]?.takeIf { it.isNotBlank() }?.let { sessionId = it }
        val obj = result as? JsonObject ?: throw McpException("The server's initialize answer wasn't an object.")
        val info = obj["serverInfo"] as? JsonObject
        protocolVersion = obj.str("protocolVersion") ?: PROTOCOL_VERSION
        val si = McpServerInfo(info?.str("name").orEmpty(), info?.str("version").orEmpty(), protocolVersion, obj.str("instructions").orEmpty())
        serverInfo = si
        notify("notifications/initialized")
        return si
    }

    private suspend fun ensureSession() {
        if (serverInfo == null) lock.withLock { if (serverInfo == null) initializeLocked() }
    }

    /** Every tool the server offers (follows nextCursor pages, at most 20). */
    suspend fun listTools(): List<McpToolDef> {
        val out = ArrayList<McpToolDef>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val res = request("tools/list", params) as? JsonObject ?: throw McpException("tools/list answer wasn't an object.")
            (res["tools"] as? JsonArray)?.forEach { el -> parseTool(el as? JsonObject ?: return@forEach)?.let(out::add) }
            cursor = res.str("nextCursor")?.takeIf { it.isNotBlank() }
        } while (cursor != null && ++pages < 20)
        return out
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val res = request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) }) as? JsonObject
            ?: throw McpException("tools/call answer wasn't an object.")
        return parseCallResult(res)
    }

    /** Sends one request, starting (or restarting once, after a 404) the session as needed. */
    suspend fun request(method: String, params: JsonObject? = null): JsonElement {
        ensureSession()
        return try {
            exchange(method, params, withSession = true).first
        } catch (e: McpException) {
            if (e.status != 404 || sessionId == null) throw e
            lock.withLock { initializeLocked() }
            exchange(method, params, withSession = true).first
        }
    }

    /** A notification: no id, no answer expected (servers reply 202 Accepted). */
    suspend fun notify(method: String, params: JsonObject? = null) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            params?.let { put("params", it) }
        }
        send(body).use { r ->
            if (!r.isSuccessful) throw httpError(r)
        }
    }

    /** Ends the session on the server (DELETE); errors are ignored. */
    suspend fun close() {
        val sid = sessionId ?: return
        runCatching {
            val req = baseRequest().header("Mcp-Session-Id", sid).delete().build()
            await(http.newCall(req)).close()
        }
        sessionId = null
        serverInfo = null
    }

    private suspend fun exchange(method: String, params: JsonObject?, withSession: Boolean): Pair<JsonElement, Map<String, String>> {
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        }
        send(body, withSession).use { r ->
            if (!r.isSuccessful) throw httpError(r)
            val headers = r.headers.associate { (k, v) -> k.lowercase() to v }
            val type = r.header("Content-Type").orEmpty().lowercase()
            val message = try {
                withContext(Dispatchers.IO) {
                    if (type.startsWith("text/event-stream")) readSse(r, id)
                    else {
                        val text = r.body?.string().orEmpty()
                        if (text.isBlank()) throw McpException("The server sent an empty answer to $method.", r.code)
                        pickResponse(parse(text), id)
                    }
                }
            } catch (e: McpException) {
                throw e
            } catch (e: IOException) {
                throw McpException("Network: ${e.message ?: e.javaClass.simpleName} (while reading the answer to $method)")
            } ?: throw McpException("The server closed the stream without answering $method.")
            return resultOf(message, method) to headers
        }
    }

    private suspend fun send(body: JsonObject, withSession: Boolean = true): Response {
        val b = baseRequest()
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
        if (withSession) {
            sessionId?.let { b.header("Mcp-Session-Id", it) }
            if (serverInfo != null) b.header("MCP-Protocol-Version", protocolVersion)
        }
        return await(http.newCall(b.build()))
    }

    private suspend fun baseRequest(): Request.Builder {
        val b = Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "$clientName/$clientVersion")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        token()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    /** Runs the call with an overall timeout; cancelling the coroutine cancels the HTTP call. */
    private suspend fun await(call: Call): Response {
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val msg = if (call.isCanceled() && !cont.isCancelled) "The MCP server took longer than ${timeoutMs / 1000}s." else e.message ?: e.javaClass.simpleName
                    cont.resumeWithException(McpException("Network: $msg"))
                }
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }
    }

    private fun httpError(r: Response): McpException {
        val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("").take(400)
        val challenge = r.header("WWW-Authenticate")
        return when {
            r.code == 401 -> McpAuthException("The server wants you to sign in again (401).", 401, challenge)
            r.code == 403 && challenge?.contains("insufficient_scope", true) == true -> McpAuthException("The sign-in doesn't allow this (403, insufficient scope).", 403, challenge)
            r.code == 404 -> McpException("Not found (404). ${if (sessionId != null) "The session expired." else "Check the server URL."}", 404)
            else -> McpException("HTTP ${r.code}${if (text.isNotBlank()) ": $text" else ""}", r.code)
        }
    }

    /** Reads SSE events until the response with [id] arrives. Other messages are skipped. */
    private fun readSse(r: Response, id: Long): JsonObject? {
        val source = r.body?.source() ?: return null
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        val text = data.toString()
                        data.setLength(0)
                        val found = runCatching { pickResponse(parse(text), id) }.getOrNull()
                        if (found != null) return found
                    }
                }
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                else -> Unit // event:, id:, retry:
            }
        }
        if (data.isNotEmpty()) return runCatching { pickResponse(parse(data.toString()), id) }.getOrNull()
        return null
    }

    private fun parse(text: String): JsonElement = try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw McpException("The server's answer wasn't JSON: ${text.take(200)}")
    }

    /** The response with [id] from one message or a batch; null when it isn't there. */
    private fun pickResponse(el: JsonElement, id: Long): JsonObject? = when (el) {
        is JsonObject -> el.takeIf { idMatches(it, id) }
        is JsonArray -> el.firstOrNull { it is JsonObject && idMatches(it, id) } as? JsonObject
        else -> null
    }

    private fun idMatches(o: JsonObject, id: Long): Boolean {
        if (o["method"] != null) return false // a request or notification from the server
        val v = o["id"] as? JsonPrimitive ?: return false
        return v.longOrNull == id || v.contentOrNull == id.toString()
    }

    private fun resultOf(msg: JsonObject, method: String): JsonElement {
        (msg["error"] as? JsonObject)?.let { e ->
            val code = (e["code"] as? JsonPrimitive)?.intOrNull
            throw McpException("$method failed: ${e.str("message") ?: e.toString().take(300)}${code?.let { " (code $it)" } ?: ""}", code = code)
        }
        return msg["result"] ?: JsonNull
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private val JSON = "application/json".toMediaType()

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

        fun parseTool(o: JsonObject): McpToolDef? {
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
            val ann = o["annotations"] as? JsonObject
            fun hint(k: String) = (ann?.get(k) as? JsonPrimitive)?.booleanOrNull
            val schema = (o["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) }
            return McpToolDef(
                name = name,
                description = o.str("description").orEmpty(),
                inputSchema = schema,
                title = o.str("title") ?: ann?.str("title"),
                readOnlyHint = hint("readOnlyHint"),
                destructiveHint = hint("destructiveHint"),
                openWorldHint = hint("openWorldHint"),
            )
        }

        fun parseCallResult(res: JsonObject): McpCallResult {
            val text = StringBuilder()
            val images = ArrayList<Pair<String, String>>()
            (res["content"] as? JsonArray)?.forEach { part ->
                val p = part as? JsonObject ?: return@forEach
                when (p.str("type")) {
                    "text" -> text.appendLine(p.str("text").orEmpty())
                    "image" -> {
                        val data = p.str("data"); val mime = p.str("mimeType") ?: "image/png"
                        if (data != null) images += mime to data
                    }
                    "resource" -> {
                        val r = p["resource"] as? JsonObject
                        text.appendLine(r?.str("text") ?: "[resource ${r?.str("uri").orEmpty()}]")
                    }
                    "resource_link" -> text.appendLine("[link: ${p.str("name").orEmpty()} ${p.str("uri").orEmpty()}]")
                    "audio" -> text.appendLine("[audio, ${p.str("mimeType").orEmpty()}]")
                    else -> text.appendLine(p.toString().take(2_000))
                }
            }
            if (text.isBlank()) (res["structuredContent"] as? JsonObject)?.let { text.append(it.toString()) }
            val isError = (res["isError"] as? JsonPrimitive)?.booleanOrNull == true
            return McpCallResult(text.toString().trim(), isError, images)
        }
    }
}
