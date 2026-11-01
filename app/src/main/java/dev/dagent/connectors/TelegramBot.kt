package dev.dagent.connectors

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.dagent.DAgentApp
import dev.dagent.Graph
import dev.dagent.R
import dev.dagent.core.llm.ImagePart
import dev.dagent.phone.PhoneActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Talk to D-Agent from Telegram through your own bot (token from @BotFather). It long-polls
 * Telegram while switched on, answers only the one chat that paired with the code shown in the
 * app, and keeps the talk as a "Telegram" chat in D-Agent.
 */
class TelegramBot(private val g: Graph) {
    private val prefs = g.context.getSharedPreferences("telegram", Context.MODE_PRIVATE)
    private val http = g.http.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status.asStateFlow()
    private var job: Job? = null

    var token: String
        get() = g.settings.secret(TOKEN)
        set(v) = g.settings.setSecret(TOKEN, v)
    var enabled: Boolean
        get() = prefs.getBoolean("on", false)
        set(v) { prefs.edit().putBoolean("on", v).apply() }
    private var chatId: Long?
        get() = prefs.getLong("chat", 0L).takeIf { it != 0L }
        set(v) { prefs.edit().putLong("chat", v ?: 0L).apply() }
    private var offset: Long
        get() = prefs.getLong("offset", 0L)
        set(v) { prefs.edit().putLong("offset", v).apply() }
    private var sessionId: String?
        get() = prefs.getString("session", null)
        set(v) { prefs.edit().putString("session", v).apply() }

    val paired: Boolean get() = chatId != null

    /** The code the person sends as "/start CODE" to pair their Telegram chat. */
    val pairingCode: String
        get() = prefs.getString("code", null) ?: (100000 + Random.nextInt(900000)).toString().also { prefs.edit().putString("code", it).apply() }

    fun unpair() { chatId = null; prefs.edit().remove("code").apply() }

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        enabled = true
        ContextCompat.startForegroundService(g.context, Intent(g.context, BotService::class.java))
        job = scope.launch { loop() }
    }

    fun stop() {
        enabled = false
        job?.cancel()
        job = null
        _status.value = "Off"
        g.context.stopService(Intent(g.context, BotService::class.java))
    }

    /** Checks the token; returns the bot's @username. */
    suspend fun test(t: String): String = withContext(Dispatchers.IO) {
        val o = call(t, "getMe", buildJsonObject { })
        "@" + ((o["result"] as JsonObject)["username"] as JsonPrimitive).content
    }

    private suspend fun loop() {
        var backoff = 2_000L
        while (currentCoroutineContext().isActive) {
            val t = token
            if (t.isBlank()) { _status.value = "No bot token"; return }
            try {
                _status.value = if (chatId == null) "Waiting to pair: send /start $pairingCode to your bot" else "Running"
                val res = call(t, "getUpdates", buildJsonObject { put("timeout", 50); put("offset", offset) })
                val updates = res["result"] as? JsonArray ?: continue
                for (u in updates) {
                    val o = u.jsonObject
                    offset = ((o["update_id"] as JsonPrimitive).longOrNull ?: 0L) + 1
                    (o["message"] as? JsonObject)?.let { handle(t, it) }
                }
                backoff = 2_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = "Connection problem, retrying: ${e.message}"
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private suspend fun handle(t: String, msg: JsonObject) {
        val chat = ((msg["chat"] as JsonObject)["id"] as JsonPrimitive).longOrNull ?: return
        fun str(k: String) = (msg[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val text = str("text").ifBlank { str("caption") }.trim()
        val name = g.settings.current.persona.name
        val pairedId = chatId
        if (pairedId == null) {
            if (text == "/start $pairingCode" || text == pairingCode) {
                chatId = chat
                prefs.edit().remove("code").apply()
                send(t, chat, "Paired. I'm $name. Talk to me here like in the app.")
            } else {
                send(t, chat, "This is a private assistant. To pair, send the code shown in D-Agent > Settings > Connectors > Telegram.")
            }
            return
        }
        if (chat != pairedId) { send(t, chat, "This is a private assistant."); return }
        if (text == "/start") { send(t, chat, "I'm here. Ask me anything."); return }
        if (text == "/new") { sessionId = null; send(t, chat, "Fresh start. What's up?"); return }
        val image = (msg["photo"] as? JsonArray)?.lastOrNull()?.jsonObject?.get("file_id")?.let { (it as JsonPrimitive).content }
            ?.let { id -> runCatching { photo(t, id) }.getOrNull() }
        val prompt = text.ifBlank { if (image != null) "What's in this picture?" else return }
        val typing = scope.launch {
            while (isActive) {
                runCatching { call(t, "sendChatAction", buildJsonObject { put("chat_id", chat); put("action", "typing") }) }
                delay(4_500)
            }
        }
        try {
            val r = g.runner.runRemote(sessionId, "Telegram", prompt, listOfNotNull(image),
                "The person is writing from Telegram on another device; keep replies short and plain (no tables). Actions that need the phone screen may be refused.")
            sessionId = r.sessionId
            r.files.forEach { f -> runCatching { sendFile(t, chat, f) } }
            send(t, chat, r.text.ifBlank { "Done." })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(t, chat, "Something went wrong: ${e.message}")
        } finally {
            typing.cancel()
        }
    }

    private fun photo(t: String, fileId: String): ImagePart {
        val path = (((call(t, "getFile", buildJsonObject { put("file_id", fileId) })["result"] as JsonObject)["file_path"]) as JsonPrimitive).content
        val bytes = http.newCall(Request.Builder().url("https://api.telegram.org/file/bot$t/$path").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("photo download failed")
            r.body!!.bytes()
        }
        return ImagePart("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun send(t: String, chat: Long, text: String) {
        text.chunked(4000).forEach { part -> call(t, "sendMessage", buildJsonObject { put("chat_id", chat); put("text", part) }) }
    }

    private fun sendFile(t: String, chat: Long, file: File) {
        val ext = file.extension.lowercase()
        val (method, field, mime) = when (ext) {
            "jpg", "jpeg", "png", "webp" -> Triple("sendPhoto", "photo", "image/$ext")
            "mp4" -> Triple("sendVideo", "video", "video/mp4")
            else -> Triple("sendDocument", "document", "application/octet-stream")
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chat.toString())
            .addFormDataPart(field, file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        http.newBuilder().writeTimeout(3, TimeUnit.MINUTES).build()
            .newCall(Request.Builder().url("https://api.telegram.org/bot$t/$method").post(body).build()).execute().close()
    }

    private fun call(t: String, method: String, body: JsonObject): JsonObject {
        val req = Request.Builder().url("https://api.telegram.org/bot$t/$method")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw IllegalStateException("Telegram error ${r.code}")
            if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
                throw IllegalStateException(if (r.code == 401) "Telegram rejected the bot token" else (o["description"] as? JsonPrimitive)?.contentOrNull ?: "Telegram error ${r.code}")
            }
            return o
        }
    }

    companion object {
        const val TOKEN = "telegram"
    }
}

/** Keeps the app alive while the Telegram bot is on. */
class BotService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PhoneActions.ensureChannel(this)
        val n = NotificationCompat.Builder(this, PhoneActions.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Telegram bot is on")
            .setContentText("Message your bot to talk to D-Agent")
            .setOngoing(true)
            .setContentIntent(PhoneActions.launchIntent(this))
            .build()
        runCatching {
            ServiceCompat.startForeground(this, 8, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }
        // If the system restarted the service, start polling again.
        (application as DAgentApp).graph.telegram.let { if (it.enabled && !it.running) it.start() }
        return START_STICKY
    }
}
