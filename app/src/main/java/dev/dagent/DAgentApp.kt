package dev.dagent

import android.app.Application
import android.content.Context
import dev.dagent.agent.AgentRunner
import dev.dagent.browser.BrowserAgent
import dev.dagent.character.CharacterStudio
import dev.dagent.core.agent.MemoryStore
import dev.dagent.core.agent.SkillLibrary
import dev.dagent.core.tools.WebSearch
import dev.dagent.core.tools.Workspace
import dev.dagent.connectors.EmailClient
import dev.dagent.connectors.TelegramBot
import dev.dagent.connectors.TermuxBridge
import dev.dagent.core.places.PlacesClient
import dev.dagent.data.ChatStore
import dev.dagent.data.Connectors
import dev.dagent.data.Settings
import dev.dagent.linux.LinuxComputer
import dev.dagent.linux.PhoneBridge
import dev.dagent.local.LocalModels
import dev.dagent.phone.Interactions
import dev.dagent.phone.PhoneActions
import dev.dagent.phone.CloudVoice
import dev.dagent.phone.Speaker
import dev.dagent.schedule.Scheduler
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class DAgentApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        PhoneActions.ensureChannel(this)
        BuiltinSkills.install(this, graph.skills)
        if (graph.telegram.enabled) runCatching { graph.telegram.start() }
    }
}

/** Every long-lived object in the app, built once. */
class Graph(val context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val settings = Settings(context)
    val chats = ChatStore(context.filesDir)
    val workspace = Workspace(File(context.filesDir, "workspace"))
    val memory = MemoryStore(File(context.filesDir, "memory"))
    val skills = SkillLibrary(File(context.filesDir, "skills"))
    val interactions = Interactions(context)
    val cloudVoice = CloudVoice(settings, context.cacheDir, http)
    val speaker by lazy { Speaker(context, cloudVoice) }
    val phone = PhoneActions(context, interactions, { settings.current.confirmRisky }, { speaker.say(it) }, { settings.current.defaultSim })
    val bridge = PhoneBridge { action, args -> phone.run(action, args) }.also { it.start() }
    val linux = LinuxComputer(context, workspace.root, skills.dir, bridge, http).also { if (it.installed) it.configure() }
    val browser = BrowserAgent(context)
    val local = LocalModels(context)
    val search = WebSearch(http) { settings.current.searxUrl }
    val scheduler = Scheduler(context)
    val character = CharacterStudio(
        context, http, File(workspace.root, "images/characters"),
        pollinationsKey = { settings.secret(Settings.POLLINATIONS_KEY) },
        openAi = { settings.provider("openai").takeIf { it.apiKey.isNotBlank() }?.let { it.baseUrl to it.apiKey } },
    )
    val connectors = Connectors(context, settings)
    val places = PlacesClient(googleKey = { connectors.googlePlacesKey.ifBlank { null } }, http = http)
    val email = EmailClient { connectors.emailAccounts }
    val termux = TermuxBridge(context)
    val runner = AgentRunner(this)
    val telegram = TelegramBot(this)
}
