package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Guard
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.clip
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Gmail, Google Calendar and Google Drive through the person's own Google sign-in (OAuth).
 * Reading is always on; creating drafts and events needs the write scopes the person opted into
 * ([write]) and asks them every time.
 */
class GoogleTool(
    private val token: suspend () -> String?,
    private val write: () -> Boolean,
    private val http: OkHttpClient,
    private val gmailBase: String = "https://gmail.googleapis.com",
    private val calendarBase: String = "https://www.googleapis.com/calendar/v3",
    private val driveBase: String = "https://www.googleapis.com/drive/v3",
) : Tool {
    override val name = "google"
    override val description = "The person's Google account: search and read Gmail, list Google Calendar events, search and read Google Drive files. " +
        "Actions: gmail_search (query in Gmail syntax), gmail_read (id), calendar_events (days ahead, optional query), drive_search (query), drive_read (id). " +
        "If the person turned on Google write access: gmail_draft (to, subject, body; makes a draft, never sends) and calendar_create (summary, start, end in ISO 8601)."
    override val parameters = schema {
        title()
        string("action", "What to do.", required = true, enum = listOf("gmail_search", "gmail_read", "calendar_events", "drive_search", "drive_read") + Guard.GOOGLE_WRITES)
        string("query", "Search words (Gmail search syntax for gmail_search).")
        string("id", "Message or file id from a search.")
        integer("days", "calendar_events: how many days ahead (default 7).")
        integer("max", "How many results (default 10).")
        string("to", "gmail_draft: recipient address.")
        string("subject", "gmail_draft: subject.")
        string("body", "gmail_draft: text, or calendar_create: description.")
        string("summary", "calendar_create: event title.")
        string("start", "calendar_create: start, ISO 8601 with offset (2026-05-01T15:00:00-05:00).")
        string("end", "calendar_create: end, ISO 8601 with offset.")
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val action = args.requireString("action")
        val max = (args.int("max") ?: 10).coerceIn(1, 50)
        return when (action) {
            "gmail_search" -> {
                val list = get("$gmailBase/gmail/v1/users/me/messages", mapOf("q" to (args.string("query") ?: ""), "maxResults" to "$max")) as JsonObject
                val ids = (list["messages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.s("id") }
                if (ids.isEmpty()) return ToolResult.ok("No mail matched.")
                val lines = ids.map { id ->
                    val m = get("$gmailBase/gmail/v1/users/me/messages/$id", mapOf("format" to "metadata", "metadataHeaders" to "From,Subject,Date")) as JsonObject
                    val h = headers(m)
                    "- id $id | ${h["Date"].orEmpty()} | ${h["From"].orEmpty()} | ${h["Subject"].orEmpty()}\n  ${m.s("snippet").orEmpty()}"
                }
                ToolResult.ok(lines.joinToString("\n"))
            }
            "gmail_read" -> {
                val m = get("$gmailBase/gmail/v1/users/me/messages/${args.requireString("id")}", mapOf("format" to "full")) as JsonObject
                val h = headers(m)
                ToolResult.ok(clip("From: ${h["From"]}\nTo: ${h["To"]}\nDate: ${h["Date"]}\nSubject: ${h["Subject"]}\n\n" + bodyText(m["payload"] as? JsonObject)))
            }
            "calendar_events" -> {
                val now = Instant.now()
                val q = mutableMapOf("timeMin" to now.toString(), "timeMax" to now.plus((args.int("days") ?: 7).toLong().coerceIn(1, 365), ChronoUnit.DAYS).toString(),
                    "singleEvents" to "true", "orderBy" to "startTime", "maxResults" to "$max")
                args.string("query")?.takeIf { it.isNotBlank() }?.let { q["q"] = it }
                val r = get("$calendarBase/calendars/primary/events", q) as JsonObject
                val items = (r["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                if (items.isEmpty()) return ToolResult.ok("No events in that time.")
                ToolResult.ok(items.joinToString("\n") { e ->
                    val start = (e["start"] as? JsonObject)?.let { it.s("dateTime") ?: it.s("date") }.orEmpty()
                    "- $start | ${e.s("summary") ?: "(no title)"}${e.s("location")?.let { " @ $it" }.orEmpty()}"
                })
            }
            "drive_search" -> {
                val words = args.string("query").orEmpty().replace("\\", "").replace("'", "\\'")
                val q = if (words.isBlank()) "trashed = false" else "fullText contains '$words' and trashed = false"
                val r = get("$driveBase/files", mapOf("q" to q, "pageSize" to "$max", "fields" to "files(id,name,mimeType,modifiedTime,webViewLink)")) as JsonObject
                val files = (r["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                if (files.isEmpty()) return ToolResult.ok("No files matched.")
                ToolResult.ok(files.joinToString("\n") { "- id ${it.s("id")} | ${it.s("name")} | ${it.s("mimeType")} | ${it.s("modifiedTime")}" })
            }
            "drive_read" -> {
                val id = args.requireString("id")
                val meta = get("$driveBase/files/$id", mapOf("fields" to "name,mimeType")) as JsonObject
                val mime = meta.s("mimeType").orEmpty()
                val text = when {
                    mime == "application/vnd.google-apps.spreadsheet" -> raw("$driveBase/files/$id/export", mapOf("mimeType" to "text/csv"))
                    mime.startsWith("application/vnd.google-apps.") -> raw("$driveBase/files/$id/export", mapOf("mimeType" to "text/plain"))
                    mime.startsWith("text/") || mime == "application/json" -> raw("$driveBase/files/$id", mapOf("alt" to "media"))
                    else -> return ToolResult.ok("${meta.s("name")} is $mime; only Google Docs/Sheets/Slides and text files can be read here.")
                }
                ToolResult.ok(clip("${meta.s("name")}\n\n$text"))
            }
            "gmail_draft", "calendar_create" -> {
                if (!write()) return ToolResult.error("Google write access is off. The person can turn it on in Settings > Connectors > Google (it asks Google for more permissions).")
                if (action == "gmail_draft") {
                    val to = args.requireString("to"); val subject = args.string("subject").orEmpty(); val body = args.string("body").orEmpty()
                    if (!ctx.confirm("Save a Gmail draft to $to?", "Subject: $subject\n\n${body.take(600)}")) return ToolResult.error("The person said no.")
                    val mime = "To: $to\r\nSubject: $subject\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$body"
                    val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toByteArray())
                    val r = post("$gmailBase/gmail/v1/users/me/drafts", buildJsonObject { putJsonObject("message") { put("raw", raw) } }) as JsonObject
                    ToolResult.ok("Draft saved (id ${r.s("id")}). It was not sent; the person can send it from Gmail.")
                } else {
                    val summary = args.requireString("summary"); val start = args.requireString("start"); val end = args.requireString("end")
                    if (!ctx.confirm("Add \"$summary\" to Google Calendar?", "$start to $end")) return ToolResult.error("The person said no.")
                    val r = post("$calendarBase/calendars/primary/events", buildJsonObject {
                        put("summary", summary)
                        args.string("body")?.let { put("description", it) }
                        putJsonObject("start") { put("dateTime", start) }
                        putJsonObject("end") { put("dateTime", end) }
                    }) as JsonObject
                    ToolResult.ok("Event created: ${r.s("htmlLink").orEmpty()}")
                }
            }
            else -> ToolResult.error("Unknown action $action.")
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun headers(m: JsonObject): Map<String, String> =
        ((m["payload"] as? JsonObject)?.get("headers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .associate { (it.s("name") ?: "") to (it.s("value") ?: "") }

    /** The text/plain part (or tags stripped from text/html) of a Gmail payload. */
    private fun bodyText(p: JsonObject?): String {
        if (p == null) return ""
        val mime = p.s("mimeType").orEmpty()
        val data = (p["body"] as? JsonObject)?.s("data")
        if (data != null && mime == "text/plain") return String(Base64.getUrlDecoder().decode(data.trimEnd('=')))
        val parts = (p["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        parts.firstOrNull { it.s("mimeType") == "text/plain" }?.let { return bodyText(it) }
        parts.forEach { bodyText(it).takeIf { t -> t.isNotBlank() }?.let { return it } }
        if (data != null && mime == "text/html") return org.jsoup.Jsoup.parse(String(Base64.getUrlDecoder().decode(data.trimEnd('=')))).text()
        return ""
    }

    private suspend fun auth(): String = token()?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Google isn't signed in. The person can sign in at Settings > Connectors > Google.")

    private suspend fun get(url: String, q: Map<String, String>): JsonElement = json.parseToJsonElement(raw(url, q))

    private suspend fun raw(url: String, q: Map<String, String>): String {
        val u = url.toHttpUrl().newBuilder().apply { q.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return send(Request.Builder().url(u).header("Authorization", "Bearer ${auth()}").build())
    }

    private suspend fun post(url: String, body: JsonObject): JsonElement = json.parseToJsonElement(send(
        Request.Builder().url(url).header("Authorization", "Bearer ${auth()}").post(body.toString().toRequestBody("application/json".toMediaType())).build()))

    private suspend fun send(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("Google sign-in expired or was removed. Sign in again in Settings > Connectors > Google.")
            if (r.code == 403) throw IllegalStateException("Google refused (403): ${text.take(300)}. The needed permission may be off; sign in again in Settings > Connectors > Google.")
            if (!r.isSuccessful) throw IllegalStateException("Google API error ${r.code}: ${text.take(300)}")
            text
        }
    }
}
