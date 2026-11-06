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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The person's own Manus agent, over the Manus API (v2) with their key. D-Agent hands it a job
 * (for example a phone call from the agent's own Manus number) and reads back its answer.
 * Manus doesn't let other apps dial out with that number, so the agent places the call itself.
 */
class ManusClient(
    private val key: () -> String,
    private val base: String = "https://api.manus.ai/v2",
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun ready() = key().isNotBlank()

    data class Agent(val id: String, val taskId: String, val nickname: String, val about: String)

    /** What came back after a message D-Agent sent. */
    data class Outcome(val done: Boolean, val status: String, val reply: String, val question: String?)

    data class Event(val id: String, val type: String, val time: Long, val text: String, val status: String?, val waiting: String?)

    suspend fun agents(): List<Agent> = withContext(Dispatchers.IO) {
        val o = get("agent.list", emptyMap())
        val list = (o["agents"] ?: o["data"] ?: o["items"]) as? JsonArray ?: JsonArray(emptyList())
        list.mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            Agent(a.s("id").orEmpty(), a.s("task_id").orEmpty(), a.s("nickname").orEmpty(), a.s("about").orEmpty())
                .takeIf { it.taskId.isNotBlank() }
        }
    }

    suspend fun send(taskId: String, text: String): Unit = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("task_id", taskId)
            putJsonObject("message") { put("content", text) }
        }
        post("task.sendMessage", body)
    }

    /** Newest events first. */
    suspend fun events(taskId: String, limit: Int = 40): List<Event> = withContext(Dispatchers.IO) {
        val o = get("task.listMessages", mapOf("task_id" to taskId, "limit" to limit.toString(), "order" to "desc"))
        ((o["messages"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { parse(it as? JsonObject) }
    }

    private fun parse(e: JsonObject?): Event? {
        e ?: return null
        val type = e.s("type") ?: return null
        val p = e[type] as? JsonObject
        val time = (e["timestamp"] as? JsonPrimitive)?.longOrNull ?: 0L
        val text = when (type) {
            "user_message", "assistant_message", "error_message", "explanation" -> p?.s("content").orEmpty()
            "status_update" -> listOfNotNull(p?.s("brief"), p?.s("description")).joinToString(": ")
            else -> ""
        }
        val status = if (type == "status_update") p?.s("agent_status") else null
        val waiting = (p?.get("status_detail") as? JsonObject)?.let { it.s("waiting_description") ?: it.s("waiting_for_event_type") }
        return Event(e.s("id").orEmpty(), type, time, text, status, waiting)
    }

    private fun get(method: String, q: Map<String, String>): JsonObject {
        val url = "$base/$method".toHttpUrl().newBuilder().apply { q.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return call(Request.Builder().url(url).get())
    }

    private fun post(method: String, body: JsonObject): JsonObject =
        call(Request.Builder().url("$base/$method").post(body.toString().toRequestBody(JSON)))

    private fun call(b: Request.Builder): JsonObject {
        val k = key().trim().ifEmpty { throw IllegalStateException(NO_KEY) }
        http.newCall(b.header("x-manus-api-key", k).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val err = (o?.get("error") as? JsonObject)?.s("message")
            if (!r.isSuccessful || o == null || (o["ok"] as? JsonPrimitive)?.contentOrNull == "false") {
                throw IllegalStateException("Manus said ${if (r.code == 401) "the key is wrong (HTTP 401)" else "HTTP ${r.code}"}: ${err ?: text.take(200)}")
            }
            return o
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        private val JSON = "application/json".toMediaType()
        const val MAIN = "agent-default-main_task"
        const val NO_KEY = "No Manus key yet. Add one in Settings > Connectors > Manus agent."

        fun callJob(owner: String, place: String, number: String, task: String, maxMinutes: Int) =
            "Phone call request from D-Agent, sent by $owner. Using your own phone number, call $place at $number. " +
                "Say near the start that you are an AI assistant calling for $owner. Goal: $task. " +
                "Keep it under $maxMinutes minutes, be brief and polite, don't agree to pay for anything and don't share card or account numbers. " +
                "If they ask for something you don't know, say $owner will follow up. " +
                "When the call ends, reply here with a short summary of what they said (or that no one answered)."

        /** Finds our message by its [tag] (events may come newest first) and reads what followed. */
        fun outcome(events: List<Event>, tag: String): Outcome? {
            val asc = events.sortedBy { it.time }
            val start = asc.indexOfLast { it.type == "user_message" && tag in it.text }
            if (start < 0) return null
            val after = asc.drop(start + 1)
            val reply = after.filter { it.type == "assistant_message" || it.type == "error_message" }
                .joinToString("\n\n") { it.text.trim() }.trim()
            val last = after.lastOrNull { it.type == "status_update" }
            return when (last?.status) {
                "stopped" -> Outcome(true, "finished", reply, null)
                "error" -> Outcome(true, "error", reply.ifBlank { last.text }, null)
                "waiting" -> Outcome(true, "waiting", reply, last.waiting ?: "It needs an answer or a confirmation.")
                else -> Outcome(false, last?.status ?: "queued", reply, null)
            }
        }
    }
}

/** Counts jobs per month: "YYYY-MM;count". */
class MonthlyCount(
    private val load: () -> String,
    private val save: (String) -> Unit,
    private val limit: () -> Int,
    private val month: () -> String = { java.time.YearMonth.now().toString() },
) {
    @Synchronized fun used(): Int = load().split(';').let { if (it.size == 2 && it[0] == month()) it[1].toIntOrNull() ?: 0 else 0 }
    fun cap() = limit()
    fun left(): Int = if (cap() <= 0) Int.MAX_VALUE else (cap() - used()).coerceAtLeast(0)
    @Synchronized fun add() = save("${month()};${used() + 1}")
}

private val SMS = Regex("""\b(send|shoot)\b.{0,30}\b(text|sms|message)\b|\btext (him|her|them|my|mom|dad)\b""", RegexOption.IGNORE_CASE)

class ManusTool(
    private val client: ManusClient,
    private val agentTask: () -> String,
    private val calls: MonthlyCount? = null,
    /** Turns a contact name ("my wife", "Mom") into a number, or null. */
    private val lookup: suspend (String) -> String? = { null },
    private val owner: () -> String,
) : Tool {
    override val name = "manus"
    override val description =
        "The person's own Manus agent (it has its own US phone number). call: have it phone a place from its number and report back " +
            "(to = number or contact name, place, task: what to say or find out). ask: hand it any other job in plain words (text). reply: answer a question it asked (text). " +
            "status: read its latest answer. Never use it for texts/SMS: those go from the person's own number with phone sms_send. Every call and job needs the person's OK. Use this for calls when the person asks for their Manus agent or number."
    override val parameters = schema {
        title()
        string("action", "call, ask, reply or status.", required = true, enum = listOf("call", "ask", "reply", "status"))
        string("to", "call: phone number (e.g. +1 404 555 0100) or a contact's name.")
        string("place", "call: who you're calling (a place or a person's name).")
        string("task", "call: what to ask or arrange.")
        string("text", "ask/reply: the message for the Manus agent.")
        integer("max_minutes", "call: keep the call under this many minutes (default 5).")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        if (!client.ready()) return ToolResult.error(ManusClient.NO_KEY)
        return try {
            when (args.string("action")) {
                "status" -> status()
                "reply" -> job(args.requireString("text"), "Send your Manus agent this answer?", args.requireString("text"), ctx, counts = false)
                "ask" -> if (SMS.containsMatchIn(args.requireString("text"))) ToolResult.ok(
                    "Not sent to Manus. Texts go from the person's own number: use phone sms_send (to = name or number, text = the message).")
                else job(args.requireString("text"), "Give your Manus agent this job?", args.requireString("text") + "\nIt uses your Manus credits.", ctx, counts = false)
                else -> call(args, ctx)
            }
        } catch (e: Exception) {
            ToolResult.error("Manus failed: ${e.message}")
        }
    }

    private suspend fun call(args: Args, ctx: ToolContext): ToolResult {
        val raw = args.requireString("to")
        val to = AiCallClient.e164(raw) ?: lookup(raw)?.let { AiCallClient.e164(it) }
            ?: return ToolResult.error("I couldn't find a number for \"$raw\". Look it up (phone contacts_search with their name) or ask the person for it.")
        val task = args.requireString("task")
        val who = args.string("place")?.takeIf { it.isNotBlank() } ?: if (AiCallClient.e164(raw) == null) raw else to
        calls?.let {
            if (it.left() < 1) return ToolResult.error(
                "The monthly limit of ${it.cap()} Manus calls is used up. It resets on the 1st, or the person can raise it in Settings > Connectors > Manus agent.")
        }
        val minutes = (args.int("max_minutes") ?: 5).coerceIn(1, 15)
        val left = calls?.takeIf { it.cap() > 0 }?.let { "\n${it.left()} of ${it.cap()} Manus calls left this month." }.orEmpty()
        return job(ManusClient.callJob(owner(), who, to, task, minutes), "Let your Manus agent call $who?",
            "Number: $to\nFrom your Manus number. It will say it's an AI assistant and ask: $task$left", ctx, counts = true, waitMinutes = minutes + 8)
    }

    private suspend fun job(text: String, question: String, detail: String, ctx: ToolContext, counts: Boolean, waitMinutes: Int = 10): ToolResult {
        if (!ctx.confirm(question, detail)) return ToolResult.error("The person said no.")
        val tag = "[D-Agent ${java.lang.Long.toString(System.currentTimeMillis(), 36)}]"
        val task = agentTask().ifBlank { ManusClient.MAIN }
        client.send(task, "$text\n$tag")
        if (counts) calls?.add()
        ctx.progress("Sent to your Manus agent…")
        val deadline = System.currentTimeMillis() + waitMinutes * 60_000L
        var last: ManusClient.Outcome? = null
        while (System.currentTimeMillis() < deadline) {
            delay(10_000)
            val o = runCatching { ManusClient.outcome(client.events(task), tag) }.getOrNull() ?: continue
            last = o
            ctx.progress("Manus agent: ${o.status}…")
            if (o.done) return ToolResult(report(o), isError = o.status == "error")
        }
        return ToolResult.ok("Your Manus agent is still working on it${last?.reply?.takeIf { it.isNotBlank() }?.let { ". So far:\n$it" }.orEmpty()}\nCheck later with action status.")
    }

    private suspend fun status(): ToolResult {
        val ev = client.events(agentTask().ifBlank { ManusClient.MAIN })
        val asc = ev.sortedBy { it.time }
        val lastUser = asc.indexOfLast { it.type == "user_message" }
        val tail = if (lastUser >= 0) asc.drop(lastUser) else asc.takeLast(6)
        val o = tail.firstOrNull()?.takeIf { it.type == "user_message" }?.let { ManusClient.outcome(ev, it.text) }
        if (o != null) return ToolResult.ok(report(o))
        return ToolResult.ok(tail.filter { it.text.isNotBlank() }.joinToString("\n") { "${it.type}: ${it.text.take(1500)}" }.ifBlank { "No messages yet." })
    }

    private fun report(o: ManusClient.Outcome) = buildString {
        append("Manus agent: ").append(o.status)
        if (o.reply.isNotBlank()) append("\n").append(o.reply.take(6000))
        o.question?.let { append("\nIt's waiting: ").append(it).append(" (answer with action reply, after asking the person)") }
        calls?.takeIf { it.cap() > 0 }?.let { append("\nManus calls this month: ${it.used()} of ${it.cap()}.") }
    }
}
