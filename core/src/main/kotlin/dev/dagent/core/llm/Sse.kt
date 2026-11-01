package dev.dagent.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** One server-sent event: the `event:` name (may be empty) and its joined `data:` lines. */
data class SseEvent(val event: String, val data: String)

/**
 * Runs [request] and emits each server-sent event. Cancelling the collector cancels the call,
 * which is how the Stop button ends a reply mid-stream.
 */
fun OkHttpClient.sse(request: Request): Flow<SseEvent> = callbackFlow {
    val call: Call = newCall(request)
    val job = launch(Dispatchers.IO) {
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw LlmException(errorMessage(response.code, body), response.code, body)
                }
                val source = response.body?.source() ?: throw LlmException("Empty response")
                var event = ""
                val data = StringBuilder()
                while (isActive) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                send(SseEvent(event, data.toString()))
                                data.setLength(0)
                            }
                            event = ""
                        }
                        line.startsWith(":") -> Unit
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).removePrefix(" "))
                        }
                        // Some servers send bare JSON lines without the SSE framing.
                        line.startsWith("{") -> send(SseEvent(event, line))
                    }
                }
                if (data.isNotEmpty()) send(SseEvent(event, data.toString()))
            }
            close()
        } catch (e: IOException) {
            close(if (call.isCanceled()) null else LlmException("Network error: ${e.message}"))
        } catch (e: Throwable) {
            close(e)
        }
    }
    awaitClose {
        call.cancel()
        job.cancel()
    }
}.flowOn(Dispatchers.IO)

/** Turns an HTTP error into a short sentence a person can act on. */
fun errorMessage(code: Int, body: String): String {
    val detail = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
        ?.replace("\\n", " ")?.replace("\\\"", "\"")
        ?: body.take(300)
    val hint = when (code) {
        401, 403 -> "The API key was refused. Check it in Settings > AI providers."
        404 -> "The endpoint or model was not found. Check the base URL and model name."
        429 -> "Rate limited or out of credit. Wait a moment or switch model."
        in 500..599 -> "The provider had a server error. Try again."
        else -> "The provider rejected the request."
    }
    return "$hint (HTTP $code${if (detail.isNotBlank()) ": $detail" else ""})"
}
