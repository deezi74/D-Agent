package dev.dagent.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.dagent.BuildConfig
import dev.dagent.Graph
import dev.dagent.connectors.EmailTool
import dev.dagent.connectors.TermuxTool
import dev.dagent.core.agent.Tool
import dev.dagent.core.connectors.AuthType
import dev.dagent.core.connectors.Connector
import dev.dagent.core.connectors.ConnectorRegistry
import dev.dagent.core.connectors.ConnectorStatus
import dev.dagent.core.connectors.ToolProvider
import dev.dagent.core.mcp.McpAuth
import dev.dagent.core.mcp.McpClient
import dev.dagent.core.mcp.McpServerConfig
import dev.dagent.core.mcp.McpToolProvider
import dev.dagent.core.oauth.OAuthClient
import dev.dagent.core.oauth.OAuthConfig
import dev.dagent.core.oauth.OAuthException
import dev.dagent.core.oauth.OAuthProviders
import dev.dagent.core.oauth.PendingAuth
import dev.dagent.core.oauth.TokenManager
import dev.dagent.core.oauth.TokenSet
import dev.dagent.core.oauth.TokenStore
import dev.dagent.core.tools.AiCallClient
import dev.dagent.core.tools.AiCallTool
import dev.dagent.core.tools.AppBuilderTool
import dev.dagent.core.tools.GitHubClient
import dev.dagent.core.tools.GitHubTool
import dev.dagent.core.tools.GoogleTool
import dev.dagent.core.tools.PhoneBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Every connector the agent can use, in one [ConnectorRegistry]: the built-in ones (email,
 * GitHub, Google, Termux, PC, AI calls) and each MCP server the person added.
 *
 * Storage: MCP server definitions (name, URL, which tools are off) are plain app preferences;
 * every token (OAuth tokens, MCP bearer tokens, a sign-in in progress) goes ONLY to the
 * Keystore-backed SecretStore through [Settings.secret], never to plain preferences.
 */
class ConnectorHub(private val g: Graph) {
    val registry = ConnectorRegistry()
    private val context: Context get() = g.context
    private val prefs = context.getSharedPreferences("connector_hub", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val oauth = OAuthClient(g.http)

    /** Bumped whenever a status may have changed, so screens redraw. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private fun changed() { _version.value++ }

    /** The last sign-in message for the Connectors screen ("Signed in to Google", an error...). */
    val message = MutableStateFlow("")

    // ---- tokens: SecretStore only -------------------------------------------------------------

    private val tokenStore = object : TokenStore {
        override fun load(id: String): TokenSet? = g.settings.secret("oauth.$id").takeIf { it.isNotBlank() }?.let { TokenSet.fromJson(it) }
        override fun save(id: String, tokens: TokenSet) { g.settings.setSecret("oauth.$id", tokens.toJson()); changed() }
        override fun clear(id: String) { g.settings.setSecret("oauth.$id", ""); changed() }
    }

    val tokens = TokenManager(tokenStore, refresher = { id, t ->
        val cfg = oauthConfig(id) ?: throw OAuthException("This sign-in isn't set up any more.", "invalid_grant")
        oauth.refresh(cfg, t)
    })

    fun signedIn(id: String) = tokenStore.load(id) != null

    fun signOut(id: String) {
        tokenStore.clear(id)
        registry.clearError(id)
        changed()
    }

    // ---- built-in OAuth providers ---------------------------------------------------------------

    val googleClientId: String get() = BuildConfig.GOOGLE_CLIENT_ID
    val githubClientId: String get() = BuildConfig.GITHUB_CLIENT_ID

    /** Opt-in: ask Google for permission to create drafts and calendar events. */
    var googleWrite: Boolean
        get() = prefs.getBoolean("google.write", false)
        set(v) { prefs.edit().putBoolean("google.write", v).apply(); changed() }

    fun oauthConfig(id: String): OAuthConfig? = when {
        id == GOOGLE -> googleClientId.takeIf { it.isNotBlank() }?.let { OAuthProviders.google(it, context.packageName, googleWrite) }
        id == GITHUB -> githubClientId.takeIf { it.isNotBlank() }?.let { OAuthProviders.github(it, g.connectors.githubWrite) }
        id.startsWith(MCP_PREFIX) -> server(id.removePrefix(MCP_PREFIX))?.takeIf { it.auth == McpAuth.OAUTH }?.let {
            OAuthProviders.custom(it.oauthAuthorizeUrl, it.oauthTokenUrl, it.oauthClientId, it.oauthScopes, OAuthProviders.DAGENT_REDIRECT)
        }
        else -> null
    }

    /** GitHub: the OAuth sign-in if there is one, else the pasted personal access token. */
    fun githubToken(): String = tokenStore.load(GITHUB)?.accessToken ?: g.connectors.githubToken

    /**
     * Starts a browser sign-in (Google or an MCP server with OAuth): remembers the PKCE verifier
     * and state in the SecretStore and opens the provider's page. The answer arrives in
     * [dev.dagent.connectors.OAuthRedirectActivity], which calls [completeRedirect].
     */
    fun startBrowserSignIn(id: String): String? {
        val cfg = oauthConfig(id) ?: return if (id == GOOGLE) "Google sign-in isn't set up in this build: add dagent.googleClientId to local.properties (see README)." else "This connector has no OAuth settings."
        val pending = oauth.start(id)
        g.settings.setSecret(PENDING, json.encodeToString(PendingAuth.serializer(), pending))
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(oauth.authorizeUrl(cfg, pending))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); null }.getOrElse { "Couldn't open the browser: ${it.message}" }
    }

    /** Finishes a browser sign-in from the redirect URI. Returns a message for the person. */
    suspend fun completeRedirect(uri: String): String {
        val pending = g.settings.secret(PENDING).takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(PendingAuth.serializer(), it) }.getOrNull() }
            ?: return "No sign-in was waiting. Start it again from Settings > Connectors."
        g.settings.setSecret(PENDING, "")
        val msg = try {
            val cfg = oauthConfig(pending.connectorId) ?: throw OAuthException("This connector isn't set up for sign-in.")
            val code = oauth.codeFrom(uri, pending)
            tokenStore.save(pending.connectorId, oauth.exchange(cfg, code, pending.verifier))
            registry.clearError(pending.connectorId)
            if (pending.connectorId.startsWith(MCP_PREFIX)) refreshMcp(force = true)
            "Signed in to ${registry.get(pending.connectorId)?.name ?: pending.connectorId}."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "Sign-in failed: ${e.message}"
        }
        message.value = msg
        changed()
        return msg
    }

    /** GitHub device flow, step 1: the code to type at github.com/login/device. */
    suspend fun githubDeviceStart(): OAuthClient.DeviceCode {
        val cfg = oauthConfig(GITHUB) ?: throw OAuthException("GitHub sign-in isn't set up in this build: add dagent.githubClientId to local.properties (see README).")
        return oauth.deviceStart(cfg)
    }

    /** Step 2: waits for the person to approve, then saves the token. */
    suspend fun githubDeviceFinish(code: OAuthClient.DeviceCode) {
        val cfg = oauthConfig(GITHUB) ?: throw OAuthException("GitHub sign-in isn't set up.")
        tokenStore.save(GITHUB, oauth.devicePoll(cfg, code))
        registry.clearError(GITHUB)
        changed()
    }

    // ---- MCP servers ----------------------------------------------------------------------------

    private val providers = ConcurrentHashMap<String, McpToolProvider>()
    private val mcpHttp by lazy {
        g.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    }

    fun servers(): List<McpServerConfig> = runCatching {
        json.decodeFromString(ListSerializer(McpServerConfig.serializer()), prefs.getString("mcp.servers", "[]")!!)
    }.getOrDefault(emptyList())

    fun server(id: String): McpServerConfig? = servers().firstOrNull { it.id == id }

    private fun saveServers(list: List<McpServerConfig>) {
        prefs.edit().putString("mcp.servers", json.encodeToString(ListSerializer(McpServerConfig.serializer()), list)).apply()
        changed()
    }

    /** Adds or replaces a server. [bearer] null keeps the saved token; blank removes it. */
    fun saveServer(cfg: McpServerConfig, bearer: String? = null): McpServerConfig {
        val c = if (cfg.id.isBlank()) cfg.copy(id = UUID.randomUUID().toString().take(8)) else cfg
        c.validate()?.let { throw IllegalArgumentException(it) }
        saveServers(servers().filterNot { it.id == c.id } + c)
        if (bearer != null) g.settings.setSecret(c.secretName, bearer.trim())
        if (c.auth != McpAuth.OAUTH) tokenStore.clear(MCP_PREFIX + c.id)
        providers.remove(c.id)
        registerMcp(c)
        return c
    }

    fun removeServer(id: String) {
        saveServers(servers().filterNot { it.id == id })
        g.settings.setSecret("mcp.$id", "")
        tokenStore.clear(MCP_PREFIX + id)
        providers.remove(id)?.let { p -> scope.launch { runCatching { p.client.close() } } }
        registry.unregister(MCP_PREFIX + id)
    }

    fun setToolEnabled(serverId: String, tool: String, on: Boolean) {
        val s = server(serverId) ?: return
        saveServers(servers().map { if (it.id == serverId) s.copy(disabledTools = if (on) s.disabledTools - tool else s.disabledTools + tool) else it })
    }

    fun setServerEnabled(serverId: String, on: Boolean) {
        saveServers(servers().map { if (it.id == serverId) it.copy(enabled = on) else it })
    }

    fun provider(serverId: String): McpToolProvider? = providers[serverId]

    private fun mcpToken(c: McpServerConfig): suspend () -> String? = {
        when (c.auth) {
            McpAuth.NONE -> null
            McpAuth.BEARER -> g.settings.secret(c.secretName).ifBlank { null }
            McpAuth.OAUTH -> tokens.accessToken(MCP_PREFIX + c.id)
        }
    }

    private fun registerMcp(c: McpServerConfig) {
        val p = providers.getOrPut(c.id) {
            McpToolProvider({ server(c.id) ?: c }, McpClient(c.url, mcpHttp, mcpToken(c), timeoutMs = 60_000, clientVersion = BuildConfig.VERSION_NAME))
        }
        registry.register(Connector(
            id = MCP_PREFIX + c.id, name = c.name,
            auth = when (c.auth) { McpAuth.NONE -> AuthType.NONE; McpAuth.BEARER -> AuthType.API_KEY; McpAuth.OAUTH -> AuthType.OAUTH_PKCE },
            scopes = c.oauthScopes.split(' ', ',').filter { it.isNotBlank() },
            provider = p,
            description = c.url,
            status = {
                val now = server(c.id) ?: c
                when {
                    !now.enabled -> ConnectorStatus.OFF
                    now.auth == McpAuth.BEARER && g.settings.secret(now.secretName).isBlank() -> ConnectorStatus.needsSignIn("Add the server's token.")
                    now.auth == McpAuth.OAUTH && !signedIn(MCP_PREFIX + now.id) -> ConnectorStatus.needsSignIn()
                    else -> ConnectorStatus.CONNECTED
                }
            },
        ))
    }

    /**
     * Asks MCP servers for their tool lists: all of them when [force], otherwise only those never
     * asked or asked more than 15 minutes ago. Bounded so a dead server can't hold up a chat.
     */
    suspend fun refreshMcp(force: Boolean = false, limitMs: Long = 20_000) {
        val now = System.currentTimeMillis()
        val due = servers().filter { s -> force || (providers[s.id]?.refreshedAt ?: 0) < now - 15 * 60_000 }.map { MCP_PREFIX + it.id }
        if (due.isEmpty()) return
        withTimeoutOrNull(limitMs) { registry.refresh(due) }
        changed()
    }

    // ---- built-in connectors ----------------------------------------------------------------------

    private fun termuxTool(): TermuxTool? = if (g.connectors.termuxOn || g.termux.isInstalled()) TermuxTool(g.termux) { true } else null

    init {
        registry.register(Connector("email", "Email", AuthType.API_KEY,
            provider = ToolProvider.of { listOf(EmailTool(g.email) { g.connectors.emailOrganize }) },
            status = { if (g.connectors.emailAccounts.isNotEmpty()) ConnectorStatus.CONNECTED else ConnectorStatus.needsSignIn("Add an account with an app password.") }))
        registry.register(Connector(GITHUB, "GitHub", AuthType.OAUTH_DEVICE, scopes = OAuthProviders.github("", true).scopes,
            provider = ToolProvider.of {
                val gh = GitHubClient({ githubToken() }, http = g.http)
                val termux = termuxTool()
                listOf<Tool>(
                    GitHubTool(gh) { g.connectors.githubWrite },
                    AppBuilderTool(gh, { g.connectors.builtApps }, { g.connectors.builtApps = g.connectors.builtApps + it }, { githubToken() },
                        phone = termux?.let { t -> PhoneBuilder { script -> t.startJob(script).text } }),
                )
            },
            status = { if (githubToken().isNotBlank()) ConnectorStatus.CONNECTED else ConnectorStatus.needsSignIn() }))
        registry.register(Connector(GOOGLE, "Google (Gmail, Calendar, Drive)", AuthType.OAUTH_PKCE,
            scopes = OAuthProviders.GOOGLE_READ_SCOPES,
            provider = ToolProvider.of { listOf(GoogleTool({ tokens.accessToken(GOOGLE) }, { googleWrite && hasGoogleWriteScopes() }, g.http)) },
            status = {
                when {
                    googleClientId.isBlank() -> ConnectorStatus.needsSignIn("Not set up in this build (no Google client ID).")
                    !signedIn(GOOGLE) -> ConnectorStatus.needsSignIn()
                    googleWrite && !hasGoogleWriteScopes() -> ConnectorStatus.needsSignIn("Sign in again to allow drafts and events.")
                    else -> ConnectorStatus.CONNECTED
                }
            }))
        registry.register(Connector("termux", "Termux", AuthType.NONE,
            provider = ToolProvider.of { listOfNotNull(termuxTool()) },
            status = { if (termuxTool() != null) ConnectorStatus.CONNECTED else ConnectorStatus.OFF }))
        registry.register(Connector("pc", "Always-on computer", AuthType.API_KEY,
            provider = ToolProvider.of {
                listOf(dev.dagent.core.tools.PcTool(dev.dagent.connectors.PcWatch.client(g), g.workspace) { id, title ->
                    dev.dagent.connectors.PcWatch.add(g.context, g, id, title)
                })
            },
            status = { if (g.connectors.pcUrl.isNotBlank() && g.connectors.pcCode.isNotBlank()) ConnectorStatus.CONNECTED else ConnectorStatus.needsSignIn() }))
        registry.register(Connector("ai_call", "AI phone calls", AuthType.API_KEY,
            provider = ToolProvider.of { listOf(AiCallTool(AiCallClient({ g.connectors.aiCallKey }, http = g.http), g.connectors.aiCallBudget) { g.connectors.ownerName() }) },
            status = { if (g.connectors.aiCallKey.isNotBlank()) ConnectorStatus.CONNECTED else ConnectorStatus.needsSignIn() }))
        registry.register(Connector("manus", "Manus agent", AuthType.API_KEY,
            provider = ToolProvider.of {
                listOf(dev.dagent.core.tools.ManusTool(manusClient(), { g.connectors.manusAgent }, g.connectors.manusCalls, ::contactNumber) { g.connectors.ownerName() })
            },
            status = { if (g.connectors.manusKey.isNotBlank()) ConnectorStatus.CONNECTED else ConnectorStatus.needsSignIn() }))
        servers().forEach(::registerMcp)
    }

    /** First phone number of the best-matching contact, or null. */
    private suspend fun contactNumber(name: String): String? = runCatching {
        val text = g.phone.run("contacts_search", mapOf("query" to name.removePrefix("my ").trim()))
        Regex("""\+?\d[\d ()\-.]{8,}\d""").find(text)?.value
    }.getOrNull()

    fun manusClient() = dev.dagent.core.tools.ManusClient({ g.connectors.manusKey }, http = g.http)

    private fun hasGoogleWriteScopes(): Boolean {
        val granted = tokenStore.load(GOOGLE)?.scope.orEmpty()
        return OAuthProviders.GOOGLE_WRITE_SCOPES.all { granted.contains(it) }
    }

    /** Connector tools for one run, after the built-in [taken] names. */
    fun tools(agentMode: Boolean, taken: Set<String>): List<Tool> = registry.tools(agentMode, taken)

    companion object {
        const val GOOGLE = "google"
        const val GITHUB = "github"
        const val MCP_PREFIX = "mcp."
        private const val PENDING = "oauth.pending"
    }
}
