package dev.dagent.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.dagent.Graph
import dev.dagent.R
import dev.dagent.browser.BrowserTool
import dev.dagent.character.CharacterTool
import dev.dagent.connectors.EmailTool
import dev.dagent.connectors.TermuxTool
import dev.dagent.core.places.GeoPoint
import dev.dagent.core.tools.GitHubClient
import dev.dagent.core.tools.GitHubTool
import dev.dagent.core.tools.PlacesTool
import dev.dagent.core.tools.WeatherTool
import dev.dagent.core.agent.AgentEvent
import dev.dagent.core.agent.AgentLoop
import dev.dagent.core.agent.AgentOptions
import dev.dagent.core.agent.ForgetTool
import dev.dagent.core.agent.PlanBoard
import dev.dagent.core.agent.PlanTool
import dev.dagent.core.agent.PromptBuilder
import dev.dagent.core.agent.RecallTool
import dev.dagent.core.agent.RememberTool
import dev.dagent.core.agent.Situation
import dev.dagent.core.agent.SkillTool
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.llm.ImagePart
import dev.dagent.core.llm.LlmClient
import dev.dagent.core.llm.Msg
import dev.dagent.core.llm.Providers
import dev.dagent.core.llm.Role
import dev.dagent.core.tools.FileEditTool
import dev.dagent.core.tools.FileListTool
import dev.dagent.core.tools.FileReadTool
import dev.dagent.core.tools.FileWriteTool
import dev.dagent.core.tools.ImageTool
import dev.dagent.core.tools.ShellJobTool
import dev.dagent.core.tools.ShellTool
import dev.dagent.core.tools.WebFetchTool
import dev.dagent.core.tools.WebSearchTool
import dev.dagent.data.ChatSession
import dev.dagent.data.Settings
import dev.dagent.data.ToolInfo
import dev.dagent.phone.PhoneActions
import dev.dagent.phone.PhoneTool
import dev.dagent.schedule.ScheduleTool
import dev.dagent.service.AgentService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZonedDateTime

/** A tool call as the chat shows it right now. */
data class LiveTool(
    val title: String,
    val name: String,
    val running: Boolean,
    val progress: String = "",
    val millis: Long = 0,
    val error: Boolean = false,
    val files: List<String> = emptyList(),
)

data class ChatState(
    val session: ChatSession = ChatSession(),
    val running: Boolean = false,
    val streamText: String = "",
    val streamReasoning: String = "",
    val live: Map<String, LiveTool> = emptyMap(),
    val plan: List<PlanBoard.Step> = emptyList(),
    val notice: String = "",
    val error: String = "",
)

/**
 * Runs the agent for the open chat and keeps everything the chat screen draws in one state
 * flow. Work continues in a foreground service when the person leaves the app.
 */
class AgentRunner(private val g: Graph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    private var job: Job? = null
    private val plan = PlanBoard().also { board -> board.onChange = { steps -> _state.update { it.copy(plan = steps) } } }
    @Volatile private var place: String = ""

    // ---- chats ------------------------------------------------------------------------------

    fun newChat() {
        if (_state.value.running) stop()
        _state.value = ChatState()
        plan.set(emptyList())
    }

    fun open(id: String) {
        if (_state.value.running) stop()
        scope.launch {
            val s = g.chats.load(id) ?: return@launch
            _state.value = ChatState(session = s)
            plan.set(emptyList())
        }
    }

    fun delete(id: String) {
        g.chats.delete(id)
        if (_state.value.session.id == id) newChat()
    }

    fun dismissError() = _state.update { it.copy(error = "") }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, streamText = "", streamReasoning = "", live = it.live.mapValues { (_, t) -> t.copy(running = false) }) }
        g.speaker.stop()
        AgentService.stop(g.context)
    }

    // ---- sending ----------------------------------------------------------------------------

    fun send(text: String, images: List<Uri> = emptyList()) {
        if (_state.value.running || (text.isBlank() && images.isEmpty())) return
        job = scope.launch {
            val prepared = prepareImages(images)
            var userText = text.trim()
            if (prepared.isNotEmpty()) {
                userText += "\n\n[Attached: " + prepared.joinToString { g.workspace.display(it.second) } + "]"
            }
            val user = Msg.user(userText.trim(), prepared.map { it.first })
            var session = _state.value.session
            if (session.messages.isEmpty()) session = session.copy(title = titleFrom(text.ifBlank { "Photo" }))
            session = session.copy(messages = session.messages + user, updated = System.currentTimeMillis())
            _state.update { it.copy(session = session, running = true, error = "", notice = "", streamText = "", streamReasoning = "") }
            AgentService.start(g.context, g.settings.current.persona.name)
            try {
                runLoop()
            } finally {
                _state.update { it.copy(running = false, streamText = "", streamReasoning = "") }
                g.chats.save(_state.value.session)
                AgentService.stop(g.context)
            }
        }
    }

    /** Edits the last user message: drops everything after it and runs again. */
    fun retryLast() {
        if (_state.value.running) return
        val msgs = _state.value.session.messages
        val lastUser = msgs.indexOfLast { it.role == Role.USER }
        if (lastUser < 0) return
        val text = msgs[lastUser].text
        _state.update { it.copy(session = it.session.copy(messages = msgs.take(lastUser))) }
        send(text)
    }

    private suspend fun runLoop() {
        val prefs = g.settings.current
        val provider = g.settings.provider()
        try {
            if (!provider.ready) {
                throw IllegalStateException(
                    if (provider.preset.keyRequired && provider.apiKey.isBlank()) "Add your ${provider.preset.name} key in Settings > AI providers, or pick another provider."
                    else "Finish setting up ${provider.preset.name} in Settings > AI providers."
                )
            }
            val isLocal = provider.id == Providers.LOCAL_ID
            if (isLocal) {
                val file = g.local.installed().firstOrNull { it.name == prefs.localModelFile } ?: g.local.installed().firstOrNull()
                    ?: throw IllegalStateException("No on-phone model yet. Download one in Settings > On-phone models.")
                _state.update { it.copy(notice = "Loading ${file.nameWithoutExtension} on the phone...") }
                g.local.ensureRunning(file, prefs.localContext, prefs.localThreads)
                _state.update { it.copy(notice = "") }
            }
            val client: LlmClient = Providers.client(g.http, provider.kind, provider.baseUrl, provider.apiKey, provider.preset.reasoning, provider.preset.headers)
            val vision = when (prefs.visionOverride) {
                1 -> true
                2 -> false
                else -> !isLocal && VISION.containsMatchIn(provider.model.lowercase())
            }
            val tools = tools(prefs.agentMode, vision)
            refreshPlace()
            val situation = Situation(
                now = ZonedDateTime.now(),
                place = place,
                device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                battery = runCatching { g.phone.battery() }.getOrDefault(""),
                linuxReady = g.linux.installed,
                canSeeImages = vision,
                agentMode = prefs.agentMode,
                modelName = provider.model,
            )
            val system = PromptBuilder.build(prefs.persona, situation, g.memory.forPrompt(), g.skills.list(), tools.map { it.name }.toSet())
            val options = AgentOptions(
                maxSteps = if (prefs.agentMode) 40 else 6,
                think = prefs.think,
                contextChars = if (isLocal) (prefs.localContext * 3) else 240_000,
                maxTokens = if (isLocal) 2048 else 8192,
            )
            val loop = AgentLoop(client, provider.model, tools, options)
            val ctx = object : ToolContext {
                override val sessionId: String get() = _state.value.session.id
                override fun progress(text: String) {
                    _state.update { s ->
                        val running = s.live.entries.lastOrNull { it.value.running } ?: return@update s
                        s.copy(live = s.live + (running.key to running.value.copy(progress = text)))
                    }
                }
                override suspend fun confirm(title: String, detail: String) = g.interactions.confirm(title, detail)
            }
            var finalText = ""
            loop.run(system, _state.value.session.messages, ctx).collect { ev -> handle(ev) { finalText = it } }
            if (prefs.readAloud && finalText.isNotBlank()) g.speaker.say(finalText)
            if (!g.interactions.uiVisible && finalText.isNotBlank()) notifyDone(finalText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(error = e.message ?: e::class.simpleName.orEmpty()) }
        }
    }

    private fun handle(ev: AgentEvent, onFinal: (String) -> Unit) {
        when (ev) {
            is AgentEvent.TextDelta -> _state.update { it.copy(streamText = it.streamText + ev.text) }
            is AgentEvent.ReasoningDelta -> _state.update { it.copy(streamReasoning = it.streamReasoning + ev.text) }
            is AgentEvent.Notice -> _state.update { it.copy(notice = ev.text) }
            is AgentEvent.Usage -> Unit
            is AgentEvent.ToolStart -> _state.update {
                it.copy(live = it.live + (ev.call.id to LiveTool(ev.title, ev.call.name, running = true)), streamText = "", streamReasoning = "")
            }
            is AgentEvent.ToolEnd -> {
                val files = ev.result.files.map { it.absolutePath }
                _state.update { s ->
                    val old = s.live[ev.call.id]
                    val info = ToolInfo(old?.title ?: ev.call.name, ev.millis, ev.result.isError, files)
                    s.copy(
                        live = s.live + (ev.call.id to LiveTool(info.title, ev.call.name, false, "", ev.millis, ev.result.isError, files)),
                        session = s.session.copy(tools = s.session.tools + (ev.call.id to info)),
                    )
                }
            }
            is AgentEvent.Message -> {
                if (ev.msg.role == Role.ASSISTANT && ev.msg.toolCalls.isEmpty()) onFinal(ev.msg.text)
                _state.update { s ->
                    s.copy(
                        session = s.session.copy(messages = s.session.messages + ev.msg, updated = System.currentTimeMillis()),
                        streamText = if (ev.msg.role == Role.ASSISTANT) "" else s.streamText,
                        streamReasoning = if (ev.msg.role == Role.ASSISTANT) "" else s.streamReasoning,
                    )
                }
                if (ev.msg.role == Role.TOOL) scope.launch(Dispatchers.IO) { g.chats.saveNow(_state.value.session) }
            }
        }
    }

    private val locate: suspend () -> GeoPoint? = {
        runCatching { g.phone.currentLocation(ask = g.interactions.uiVisible)?.let { GeoPoint(it.latitude, it.longitude) } }.getOrNull()
    }

    private fun tools(agentMode: Boolean, vision: Boolean): List<Tool> {
        val ws = g.workspace
        val quick = listOf<Tool>(
            WebSearchTool(g.search),
            WebFetchTool(g.http),
            ImageTool(g.http, ws, { g.settings.secret(Settings.POLLINATIONS_KEY) }) { g.settings.provider("openai").apiKey to g.settings.provider("openrouter").apiKey },
            CharacterTool(g.character),
            WeatherTool(g.places, locate),
            PlacesTool(g.places, locate),
            RememberTool(g.memory),
            RecallTool(g.memory),
        )
        val connected = buildList<Tool> {
            if (g.connectors.emailAccounts.isNotEmpty()) add(EmailTool(g.email) { g.connectors.emailOrganize })
            if (g.connectors.githubToken.isNotBlank()) add(GitHubTool(GitHubClient({ g.connectors.githubToken }, http = g.http)) { g.connectors.githubWrite })
            if (g.connectors.termuxOn) add(TermuxTool(g.termux) { g.connectors.termuxOn })
        }
        if (!agentMode) return quick + connected
        return quick + connected + listOf(
            ForgetTool(g.memory),
            PlanTool(plan),
            ShellTool(g.linux),
            ShellJobTool(g.linux),
            FileReadTool(ws) { vision },
            FileWriteTool(ws),
            FileEditTool(ws),
            FileListTool(ws),
            BrowserTool(g.browser) { vision },
            PhoneTool(g.phone),
            ScheduleTool(g.scheduler),
            SkillTool(g.skills),
        )
    }

    /** Runs a prompt with no screen (scheduled tasks) and notifies the answer. */
    suspend fun runHeadless(prompt: String, label: String): String {
        val provider = g.settings.provider()
        val prefs = g.settings.current
        if (provider.id == Providers.LOCAL_ID) {
            val file = g.local.installed().firstOrNull { it.name == prefs.localModelFile } ?: g.local.installed().first()
            g.local.ensureRunning(file, prefs.localContext, prefs.localThreads)
        }
        val client = Providers.client(g.http, provider.kind, provider.baseUrl, provider.apiKey, provider.preset.reasoning, provider.preset.headers)
        val tools = tools(agentMode = true, vision = false).filterNot { it.name == "plan" }
        val system = PromptBuilder.build(prefs.persona, Situation(place = place, linuxReady = g.linux.installed, agentMode = true), g.memory.forPrompt(), g.skills.list(), tools.map { it.name }.toSet()) +
            "\n\nThis is a scheduled task named \"$label\" running in the background. The person is not watching; do the work and finish with a short message for them. Do not ask questions."
        var session = ChatSession(title = "⏰ $label", messages = listOf(Msg.user(prompt)))
        var final = ""
        val ctx = object : ToolContext { override val sessionId = session.id }
        AgentLoop(client, provider.model, tools, AgentOptions(maxSteps = 25)).run(system, session.messages, ctx).collect { ev ->
            if (ev is AgentEvent.Message) {
                session = session.copy(messages = session.messages + ev.msg, updated = System.currentTimeMillis())
                if (ev.msg.role == Role.ASSISTANT && ev.msg.toolCalls.isEmpty()) final = ev.msg.text
            }
        }
        g.chats.save(session)
        notifyDone(final.ifBlank { "Finished." }, label)
        return final
    }

    data class RemoteReply(val sessionId: String, val text: String, val files: List<File>)

    /**
     * Answers a message that came from outside the app (the Telegram bot), keeping the talk in
     * its own saved chat so it has the history. Risky phone actions are refused while the app
     * is not on screen, as everywhere else.
     */
    suspend fun runRemote(sessionId: String?, title: String, prompt: String, images: List<ImagePart>, note: String): RemoteReply {
        val provider = g.settings.provider()
        val prefs = g.settings.current
        if (provider.id == Providers.LOCAL_ID) {
            val file = g.local.installed().firstOrNull { it.name == prefs.localModelFile } ?: g.local.installed().first()
            g.local.ensureRunning(file, prefs.localContext, prefs.localThreads)
        }
        val client = Providers.client(g.http, provider.kind, provider.baseUrl, provider.apiKey, provider.preset.reasoning, provider.preset.headers)
        val tools = tools(agentMode = prefs.agentMode, vision = images.isNotEmpty()).filterNot { it.name == "plan" }
        val system = PromptBuilder.build(prefs.persona, Situation(place = place, linuxReady = g.linux.installed, agentMode = prefs.agentMode), g.memory.forPrompt(), g.skills.list(), tools.map { it.name }.toSet()) +
            "\n\n" + note
        var session = sessionId?.let { g.chats.load(it) } ?: ChatSession(title = title)
        session = session.copy(messages = (session.messages + Msg.user(prompt, images)).takeLast(60), updated = System.currentTimeMillis())
        var final = ""
        val files = ArrayList<File>()
        val ctx = object : ToolContext { override val sessionId = session.id }
        AgentLoop(client, provider.model, tools, AgentOptions(maxSteps = 25)).run(system, session.messages, ctx).collect { ev ->
            when (ev) {
                is AgentEvent.Message -> {
                    session = session.copy(messages = session.messages + ev.msg, updated = System.currentTimeMillis())
                    if (ev.msg.role == Role.ASSISTANT && ev.msg.toolCalls.isEmpty()) final = ev.msg.text
                }
                is AgentEvent.ToolEnd -> files += ev.result.files
                else -> Unit
            }
        }
        g.chats.save(session)
        return RemoteReply(session.id, final, files)
    }

    private fun notifyDone(text: String, title: String? = null) {
        runCatching {
            val n = NotificationCompat.Builder(g.context, PhoneActions.CHANNEL_AGENT)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title ?: g.settings.current.persona.name)
                .setContentText(text.take(200))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
                .setAutoCancel(true)
                .setContentIntent(PhoneActions.launchIntent(g.context))
                .build()
            NotificationManagerCompat.from(g.context).notify((System.currentTimeMillis() % 100000).toInt(), n)
        }
    }

    private suspend fun refreshPlace() {
        if (place.isNotEmpty()) return
        runCatching {
            val loc = g.phone.currentLocation(ask = false) ?: return
            place = g.phone.placeName(loc)
        }
    }

    /** Shrinks picked photos, keeps a copy in /workspace/uploads, and returns them for the model. */
    private suspend fun prepareImages(uris: List<Uri>): List<Pair<ImagePart, File>> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            runCatching {
                val bytes = g.context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val dir = File(g.workspace.root, "uploads").apply { mkdirs() }
                val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                file.writeBytes(out.toByteArray())
                ImagePart("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)) to file
            }.getOrNull()
        }
    }

    private fun titleFrom(text: String): String {
        val t = text.trim().lineSequence().first().replace(Regex("\\s+"), " ")
        return if (t.length <= 42) t else t.take(40).substringBeforeLast(' ') + "..."
    }

    companion object {
        private val VISION = Regex("gpt-4o|gpt-4\\.1|gpt-5|o3|o4|claude|gemini|grok-4|grok-2-vision|vision|-vl|llava|pixtral|gemma-3|gemma3|llama-4|openrouter/auto|mistral-medium|mistral-small")
    }
}
