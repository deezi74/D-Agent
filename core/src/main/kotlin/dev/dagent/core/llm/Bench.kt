package dev.dagent.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Measures a llama.cpp server (the phone's own, or one on your PC): how fast it reads a prompt
 * (PP, tokens per second) and how fast it writes (TG, tokens per second).
 */
class Bench(http: OkHttpClient, private val baseUrl: String) {
    private val http = http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()

    data class Run(val promptTokens: Int, val promptPerSec: Double, val genTokens: Int, val genPerSec: Double)

    data class Result(val runs: List<Run>) {
        val pp get() = runs.map { it.promptPerSec }.average()
        val tg get() = runs.map { it.genPerSec }.average()
        val ppSpread get() = spread(runs.map { it.promptPerSec })
        val tgSpread get() = spread(runs.map { it.genPerSec })
        private fun spread(v: List<Double>) = if (v.size < 2) 0.0 else kotlin.math.sqrt(v.map { (it - v.average()).let { d -> d * d } }.average())
    }

    /** [pp] prompt tokens (about), [tg] tokens to write, [reps] repetitions. */
    fun run(pp: Int, tg: Int, reps: Int, progress: (Int) -> Unit = {}): Result {
        val runs = (1..reps).map { i ->
            progress(i)
            // A different first word each time so the server can't reuse the last run's cached prompt.
            once(prompt(pp, salt = "${System.nanoTime()}-$i"), tg)
        }
        return Result(runs)
    }

    private fun once(prompt: String, tg: Int): Run {
        val body = buildJsonObject {
            put("prompt", prompt)
            put("n_predict", tg)
            put("cache_prompt", false)
            put("ignore_eos", true)
            put("temperature", 0.0)
        }.toString()
        val text = http.newCall(
            Request.Builder().url(baseUrl.trimEnd('/').removeSuffix("/v1") + "/completion").post(body.toRequestBody("application/json".toMediaType())).build()
        ).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The model server answered ${r.code}")
            r.body?.string().orEmpty()
        }
        return parse(text) ?: throw IOException("The server didn't report its speed (needs llama.cpp's /completion).")
    }

    companion object {
        fun parse(json: String): Run? {
            val t = runCatching { Json.parseToJsonElement(json).jsonObject["timings"] as? JsonObject }.getOrNull() ?: return null
            fun d(k: String) = (t[k] as? JsonPrimitive)?.doubleOrNull
            fun i(k: String) = (t[k] as? JsonPrimitive)?.intOrNull
            return Run(i("prompt_n") ?: 0, d("prompt_per_second") ?: 0.0, i("predicted_n") ?: 0, d("predicted_per_second") ?: 0.0)
        }

        /** Plain English filler of roughly [tokens] tokens (about 0.75 words per token). */
        fun prompt(tokens: Int, salt: String): String {
            val words = "the quick brown fox jumps over a lazy dog while seven bright stars watch quietly from far above the hills".split(' ')
            val n = (tokens * 0.75).toInt().coerceAtLeast(8)
            return salt + " " + (0 until n).joinToString(" ") { words[it % words.size] }
        }
    }
}
