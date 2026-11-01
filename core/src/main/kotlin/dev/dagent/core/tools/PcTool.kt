package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.clip
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to D-Agent PC, the always-on computer program on the person's own computer. It runs
 * long tasks with the computer's AI model while the phone sleeps, runs commands and keeps files.
 */
class PcClient(
    private val url: () -> String,
    private val code: () -> String,
    http: OkHttpClient = OkHttpClient(),
) {
    private val http = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun ready() = url().isNotBlank() && code().isNotBlank()

    /** "192.168.1.20" or "http://192.168.1.20:8787/" become "http://192.168.1.20:8787". */
    fun base(): String = normalize(url())

    suspend fun health(): JsonObject = get("/health").jsonObject
    suspend fun run(command: String, timeout: Int): JsonObject = post("/run", buildJsonObject { put("command", command); put("timeout", timeout) }).jsonObject
    suspend fun startTask(title: String, prompt: String): JsonObject = post("/tasks", buildJsonObject { put("title", title); put("prompt", prompt) }).jsonObject
    suspend fun task(id: String): JsonObject = get("/tasks/${enc(id)}").jsonObject
    suspend fun tasks(): JsonArray = get("/tasks").jsonObject["tasks"]?.jsonArray ?: JsonArray(emptyList())
    suspend fun cancel(id: String): JsonObject = post("/tasks/${enc(id)}/cancel", JsonObject(emptyMap())).jsonObject
    suspend fun setModel(baseUrl: String?, model: String?, apiKey: String?): JsonObject = post("/config", buildJsonObject {
        putJsonObject("llm") {
            baseUrl?.let { put("base_url", it) }
            model?.let { put("model", it) }
            apiKey?.let { put("api_key", it) }
        }
    }).jsonObject

    /** A file's bytes, or null with [listing] filled when the path is a folder. */
    suspend fun file(path: String): Pair<ByteArray?, String> = withContext(Dispatchers.IO) {
        val u = (base() + "/files").toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("path", path)?.build() ?: throw IllegalStateException(BAD_URL)
        http.newCall(auth(Request.Builder().url(u)).build()).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) throw IllegalStateException(error(r.code, String(bytes)))
            if (r.header("Content-Type").orEmpty().startsWith("application/json")) {
                val o = json.parseToJsonElement(String(bytes)).jsonObject
                null to ((o["listing"] as? JsonPrimitive)?.contentOrNull ?: o.toString())
            } else bytes to ""
        }
    }

    private suspend fun get(path: String): JsonElement = send(Request.Builder().url(base() + path).get())
    private suspend fun post(path: String, body: JsonObject): JsonElement =
        send(Request.Builder().url(base() + path).post(body.toString().toRequestBody(JSON)))

    private suspend fun send(b: Request.Builder): JsonElement = withContext(Dispatchers.IO) {
        if (url().isBlank()) throw IllegalStateException(NOT_SET_UP)
        try {
            http.newCall(auth(b).build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IllegalStateException(error(r.code, text))
                json.parseToJsonElement(text)
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Can't reach your PC at ${base()} (${e.message}). Check it's on and awake, D-Agent PC is running, and the phone is on the same Wi-Fi or Tailscale.")
        }
    }

    private fun auth(b: Request.Builder) = b.header("Authorization", "Bearer " + code().trim())

    private fun error(status: Int, body: String): String {
        val msg = runCatching { (json.parseToJsonElement(body).jsonObject["error"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
        return when (status) {
            401 -> "Your PC refused the pairing code. Check it in Settings > Connectors > Your PC (on the PC run: python %USERPROFILE%\\.dagent-pc\\dagent_pc.py --code)."
            else -> "Your PC said ${msg ?: "HTTP $status: ${body.take(200)}"}"
        }
    }

    private fun enc(s: String) = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    companion object {
        private val JSON = "application/json".toMediaType()
        const val NOT_SET_UP = "Your PC isn't connected yet. Set it up in Settings > Connectors > Your PC."
        const val BAD_URL = "The PC address doesn't look right. Use something like 192.168.1.20 or http://192.168.1.20:8787."
        const val DEFAULT_PORT = 8787

        fun normalize(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            val hostPart = s.substringAfter("://").substringBefore('/')
            if (!hostPart.contains(':')) s = s.replaceFirst(hostPart, "$hostPart:$DEFAULT_PORT")
            return s
        }
    }
}

class PcTool(
    private val pc: PcClient,
    private val ws: Workspace,
    /** Called with (id, title) when a task starts, so the app can tell the person when it ends. */
    private val watch: (String, String) -> Unit = { _, _ -> },
) : Tool {
    override val name = "pc"
    override val description =
        "The person's own computer (D-Agent PC), which stays on. Use it for long or heavy work that should keep going while the phone " +
            "sleeps: research, coding, big downloads, file conversion, anything taking more than a few minutes. start_task hands over a " +
            "whole job (write a complete, standalone prompt; the PC can't see this chat); the person gets a notification when it's done. " +
            "Actions: status (is it on, which model), start_task (title, prompt), task (id: progress and result), tasks (recent), " +
            "wait (id, minutes: watch a task finish), cancel (id), run (command: one quick PowerShell/bash command on the PC), " +
            "get_file (path in the PC workspace: copies it to the phone and shows it; a folder lists its files), " +
            "set_model (model and/or base_url for the PC's AI, e.g. LM Studio http://localhost:1234/v1)."
    override val parameters = schema {
        title()
        string("action", "What to do.", required = true, enum = listOf("status", "start_task", "task", "tasks", "wait", "cancel", "run", "get_file", "set_model"))
        string("prompt", "For start_task: the full job, with every detail the PC needs.")
        string("id", "Task id for task, wait and cancel.")
        string("command", "For run.")
        string("path", "For get_file: path inside the PC workspace (e.g. reports/summary.md).")
        integer("minutes", "For wait: how long to watch, 1 to 15 (default 5).")
        string("model", "For set_model.")
        string("base_url", "For set_model.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        if (!pc.ready()) return ToolResult.error(PcClient.NOT_SET_UP)
        return when (args.requireString("action")) {
            "status" -> {
                val h = pc.health()
                val llm = h["llm"] as? JsonObject
                val problem = llm?.s("problem").orEmpty()
                ToolResult.ok(buildString {
                    append("${h.s("name")} (${h.s("os")}) is on. Workspace: ${h.s("workspace")}. Tasks running: ${h.s("running")}.\n")
                    append("AI: ${llm?.s("base_url")} model ${llm?.s("model")?.ifBlank { "(none loaded)" }}")
                    if (problem.isNotBlank()) append("\nProblem with the PC's AI: $problem")
                })
            }
            "start_task" -> {
                val prompt = args.requireString("prompt")
                val title = args.string("title")?.takeIf { it.isNotBlank() } ?: prompt.take(60)
                val t = pc.startTask(title, prompt)
                val id = t.s("id") ?: return ToolResult.error("The PC didn't return a task id: $t")
                watch(id, title)
                ToolResult.ok("Started on the PC as task $id (\"$title\"). It keeps going even if the phone sleeps, and the person gets a notification when it's done. Use wait to watch it now, or task to check later.")
            }
            "task" -> ToolResult.ok(describe(pc.task(args.requireString("id")), steps = true))
            "tasks" -> {
                val list = pc.tasks()
                if (list.isEmpty()) ToolResult.ok("No tasks on the PC yet.")
                else ToolResult.ok(list.joinToString("\n") { e -> val o = e.jsonObject; "${o.s("id")} [${o.s("status")}] ${o.s("title")}" })
            }
            "wait" -> {
                val id = args.requireString("id")
                val until = System.currentTimeMillis() + (args.int("minutes") ?: 5).coerceIn(1, 15) * 60_000L
                var t = pc.task(id)
                while (t.s("status") in RUNNING && System.currentTimeMillis() < until) {
                    val last = (t["steps"] as? JsonArray)?.lastOrNull()?.jsonObject?.s("title")
                    ctx.progress("PC: ${last ?: t.s("status")} (${t.s("step_count")} steps)")
                    delay(5_000)
                    t = pc.task(id)
                }
                ToolResult.ok(describe(t, steps = true) + if (t.s("status") in RUNNING) "\n\nStill running; the person will be notified when it's done." else "")
            }
            "cancel" -> ToolResult.ok("Cancelled: " + describe(pc.cancel(args.requireString("id")), steps = false))
            "run" -> {
                val r = pc.run(args.requireString("command"), 120)
                val code = r.s("exit") ?: "?"
                val out = r.s("output").orEmpty()
                if (code == "0") ToolResult.ok(clip(out)) else ToolResult.error("Exit code $code\n${clip(out)}")
            }
            "get_file" -> {
                val path = args.requireString("path")
                val (bytes, listing) = pc.file(path)
                if (bytes == null) return ToolResult.ok("Folder on the PC:\n$listing")
                val name = path.replace('\\', '/').substringAfterLast('/').ifBlank { "file" }
                val f = ws.resolve("from-pc/$name")
                withContext(Dispatchers.IO) { f.parentFile?.mkdirs(); f.writeBytes(bytes) }
                val preview = if (isText(name) && bytes.size < 200_000) "\n\n" + clip(String(bytes), 6_000) else ""
                ToolResult.ok("Copied $name (${bytes.size} bytes) to the phone at ${Workspace.MOUNT}/from-pc/$name.$preview", files = listOf(f))
            }
            "set_model" -> {
                val c = pc.setModel(args.string("base_url")?.takeIf { it.isNotBlank() }, args.string("model"), null)
                ToolResult.ok("The PC's AI is now: ${c["llm"]}")
            }
            else -> ToolResult.error("Unknown action.")
        }
    }

    companion object {
        val RUNNING = setOf("queued", "running")

        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

        fun isText(name: String) = name.substringAfterLast('.', "").lowercase() in setOf(
            "txt", "md", "csv", "json", "html", "htm", "py", "js", "ts", "kt", "java", "xml", "yml", "yaml", "log", "ps1", "sh", "ini", "toml", "css",
        )

        fun describe(t: JsonObject, steps: Boolean): String = buildString {
            append("Task ${t.s("id")} \"${t.s("title")}\": ${t.s("status")}")
            t.s("model")?.takeIf { it.isNotBlank() }?.let { append(" (model $it)") }
            if (steps) (t["steps"] as? JsonArray)?.takeLast(12)?.forEach { e ->
                val o = e.jsonObject
                append("\n- ${o.s("title")}")
            }
            t.s("result")?.takeIf { it.isNotBlank() }?.let { append("\n\nResult:\n").append(clip(it, 8_000)) }
        }

        /** For notifications: one line per finished task. */
        fun finishedLine(t: JsonObject): String = "${t.s("title")}: ${t.s("result").orEmpty().ifBlank { t.s("status").orEmpty() }}"

        fun status(t: JsonObject) = t.s("status").orEmpty()
        fun id(t: JsonObject) = t.s("id").orEmpty()
        fun title(t: JsonObject) = t.s("title").orEmpty()
        fun result(t: JsonObject) = t.s("result").orEmpty()
    }
}
