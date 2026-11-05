package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Phone calls that talk: a cloud voice agent (Bland AI, with the person's own key) dials the number,
 * holds the conversation for the task, and hands back the transcript and a summary. An Android app
 * can't put its own voice into a regular SIM call, so the call runs from the provider's line.
 * It always says it's an AI assistant calling for the person, and every call needs their OK.
 */
class AiCallClient(
    private val key: () -> String,
    private val base: String = "https://api.bland.ai/v1",
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun ready() = key().isNotBlank()

    suspend fun start(to: String, task: String, firstSentence: String?, voice: String?, maxMinutes: Int): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("phone_number", to)
            put("task", task)
            if (!firstSentence.isNullOrBlank()) put("first_sentence", firstSentence)
            if (!voice.isNullOrBlank()) put("voice", voice)
            put("wait_for_greeting", true)
            put("max_duration", maxMinutes)
            put("record", false)
        }
        val o = call(Request.Builder().url("$base/calls").post(body.toString().toRequestBody(JSON)))
        o.s("call_id") ?: throw IllegalStateException(o.s("message") ?: o.toString().take(300))
    }

    data class CallState(val done: Boolean, val status: String, val transcript: String, val summary: String, val minutes: Double?, val price: Double? = null)

    suspend fun state(id: String): CallState = withContext(Dispatchers.IO) {
        val o = call(Request.Builder().url("$base/calls/$id").get())
        CallState(
            done = (o["completed"] as? JsonPrimitive)?.booleanOrNull == true,
            status = o.s("status") ?: o.s("queue_status") ?: "unknown",
            transcript = o.s("concatenated_transcript").orEmpty(),
            summary = o.s("summary").orEmpty(),
            minutes = o.s("call_length")?.toDoubleOrNull(),
            price = o.s("price")?.toDoubleOrNull(),
        )
    }

    private fun call(b: Request.Builder): JsonObject {
        val k = key().trim().ifEmpty { throw IllegalStateException(NO_KEY) }
        http.newCall(b.header("authorization", k).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("Call service said HTTP ${r.code}: ${text.take(200)}")
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        private val JSON = "application/json".toMediaType()
        const val NO_KEY = "No call key yet. Add a Bland AI key in Settings > Connectors > AI phone calls."

        /** Turns 10-digit US numbers and common punctuation into +1XXXXXXXXXX. */
        fun e164(raw: String): String? {
            val plus = raw.trim().startsWith("+")
            val d = raw.filter { it.isDigit() }
            return when {
                plus && d.length in 8..15 -> "+$d"
                d.length == 10 -> "+1$d"
                d.length == 11 && d.startsWith("1") -> "+$d"
                else -> null
            }
        }

        fun task(owner: String, task: String) =
            "You are a friendly AI assistant making a phone call on behalf of $owner. Say near the start that you are an AI assistant calling for $owner. " +
                "Your goal: $task. Be brief and polite, ask only what you need, don't agree to pay for anything or share card or account numbers, " +
                "and if they ask for a callback number or something you don't know, say $owner will follow up. When you have the answer, thank them and hang up."
    }
}

/**
 * A monthly spending cap for AI calls, so nobody can run up the bill. Before a call it reserves the
 * most that call could cost (its minute limit times the rate); when the call ends the reservation
 * becomes the real price. A call the app never hears back about keeps its full reservation.
 * State is "YYYY-MM;spent;id=reserved,id=reserved" and starts over each month.
 */
class CallBudget(
    private val load: () -> String,
    private val save: (String) -> Unit,
    private val limit: () -> Double,
    private val month: () -> String = { java.time.YearMonth.now().toString() },
) {
    private data class State(val month: String, val spent: Double, val held: Map<String, Double>) {
        val total get() = spent + held.values.sum()
    }

    @Synchronized private fun read(): State {
        val now = month()
        val parts = load().split(';')
        if (parts.size < 2 || parts[0] != now) return State(now, 0.0, emptyMap())
        val held = parts.getOrNull(2).orEmpty().split(',').mapNotNull {
            val (id, v) = it.split('=').takeIf { kv -> kv.size == 2 } ?: return@mapNotNull null
            v.toDoubleOrNull()?.let { d -> id to d }
        }.toMap()
        return State(now, parts[1].toDoubleOrNull() ?: 0.0, held)
    }

    private fun write(st: State) = save("${st.month};${"%.4f".format(java.util.Locale.US, st.spent)};" +
        st.held.entries.joinToString(",") { "${it.key}=${"%.4f".format(java.util.Locale.US, it.value)}" })

    /** 0 or less means no cap. */
    fun cap(): Double = limit()
    fun used(): Double = read().total
    fun left(): Double = if (cap() <= 0) Double.MAX_VALUE else (cap() - used()).coerceAtLeast(0.0)

    /** How many minutes the next call may last, at most [wanted]; 0 means the cap is reached. */
    fun minutesAllowed(wanted: Int): Int = if (cap() <= 0) wanted else minOf(wanted, (left() / RATE_PER_MINUTE).toInt())

    @Synchronized fun hold(id: String, minutes: Int) {
        val st = read()
        write(st.copy(held = st.held + (id to minutes * RATE_PER_MINUTE)))
    }

    /** Swaps the reservation for what the call really cost. */
    @Synchronized fun settle(id: String, price: Double?, minutes: Double?) {
        val st = read()
        val held = st.held[id] ?: return
        val cost = price?.takeIf { it >= 0 } ?: minutes?.let { it * RATE_PER_MINUTE } ?: held
        write(st.copy(spent = st.spent + cost, held = st.held - id))
    }

    companion object {
        /** Bland AI's per-minute price, rounded up so the cap is never passed. */
        const val RATE_PER_MINUTE = 0.09
        const val DEFAULT_CAP = 10.0
        fun money(d: Double) = "$" + "%.2f".format(java.util.Locale.US, d)
    }
}

class AiCallTool(private val client: AiCallClient, private val budget: CallBudget? = null, private val owner: () -> String) : Tool {
    override val name = "ai_call"
    override val description =
        "Place a real phone call where an AI voice talks for the person and reports back (e.g. call a restaurant and ask if they have eggs). " +
            "call: to (phone number; find it first with places or web_search), task (what to find out or arrange). It waits for the call to end and returns the transcript. " +
            "status: id of an earlier call. The caller always says it's an AI assistant. For a normal call the person makes themselves, use the phone tool."
    override val parameters = schema {
        title()
        string("action", "call or status.", required = true, enum = listOf("call", "status"))
        string("to", "call: phone number, e.g. +1 404 555 0100.")
        string("place", "call: who you're calling, e.g. Waffle House on Main St.")
        string("task", "call: what to ask or arrange, in plain words.")
        string("first_sentence", "call: optional opening line.")
        integer("max_minutes", "call: hang up after this many minutes (default 5).")
        string("id", "status: call id.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        if (!client.ready()) return ToolResult.error(AiCallClient.NO_KEY)
        return try {
            when (args.string("action")) {
                "status" -> {
                    val id = args.requireString("id")
                    val st = client.state(id)
                    if (st.done) budget?.settle(id, st.price, st.minutes)
                    ToolResult.ok(report(id, st))
                }
                else -> call(args, ctx)
            }
        } catch (e: Exception) {
            ToolResult.error("AI call failed: ${e.message}")
        }
    }

    private suspend fun call(args: Args, ctx: ToolContext): ToolResult {
        val to = AiCallClient.e164(args.requireString("to")) ?: return ToolResult.error("That doesn't look like a phone number. Use the full number with area code.")
        val task = args.requireString("task")
        val who = args.string("place")?.takeIf { it.isNotBlank() } ?: to
        var minutes = (args.int("max_minutes") ?: 5).coerceIn(1, 15)
        if (budget != null) {
            minutes = budget.minutesAllowed(minutes)
            if (minutes < 1) return ToolResult.error(
                "The monthly AI call limit of ${CallBudget.money(budget.cap())} is used up (${CallBudget.money(budget.used())} so far). " +
                    "It resets on the 1st, or the person can raise it in Settings > Connectors > AI phone calls.")
        }
        val spend = budget?.takeIf { it.cap() > 0 }?.let {
            "\nUp to $minutes min, at most ${CallBudget.money(minutes * CallBudget.RATE_PER_MINUTE)}. ${CallBudget.money(it.left())} left of ${CallBudget.money(it.cap())} this month."
        }.orEmpty()
        if (!ctx.confirm("Let ${owner()}'s AI call $who?", "Number: $to\nIt will say it's an AI assistant and ask: $task$spend")) {
            return ToolResult.error("The person said no to this call.")
        }
        val id = client.start(to, AiCallClient.task(owner(), task), args.string("first_sentence"), null, minutes)
        budget?.hold(id, minutes)
        ctx.progress("Calling $who...")
        val deadline = System.currentTimeMillis() + (minutes + 2) * 60_000L
        while (System.currentTimeMillis() < deadline) {
            delay(8_000)
            val st = runCatching { client.state(id) }.getOrNull() ?: continue
            ctx.progress("Call ${st.status}...")
            if (st.done) { budget?.settle(id, st.price, st.minutes); return ToolResult.ok(report(id, st)) }
        }
        return ToolResult.ok("Call $id is still going. Check it with action status and id \"$id\".")
    }

    private fun report(id: String, st: AiCallClient.CallState) = buildString {
        append("Call ").append(id).append(": ").append(if (st.done) "finished" else st.status)
        st.minutes?.let { append(" (%.1f min)".format(it)) }
        budget?.takeIf { it.cap() > 0 }?.let { append("\nThis month: ${CallBudget.money(it.used())} of ${CallBudget.money(it.cap())} used.") }
        if (st.summary.isNotBlank()) append("\nSummary: ").append(st.summary)
        if (st.transcript.isNotBlank()) append("\nTranscript:\n").append(st.transcript.take(6000))
        if (st.done && st.transcript.isBlank()) append("\nNo one talked (no answer or voicemail).")
    }
}
