package dev.dagent.core.mcp

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Risk
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.connectors.ToolProvider
import dev.dagent.core.llm.ImagePart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How D-Agent signs in to an MCP server. */
@Serializable
enum class McpAuth { NONE, BEARER, OAUTH }

/**
 * One MCP server the person added. Secrets (bearer token, OAuth tokens) are NOT here; the app
 * keeps them in its encrypted SecretStore under [secretName].
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val auth: McpAuth = McpAuth.NONE,
    val enabled: Boolean = true,
    /** Tool names (the server's own names) the person switched off. */
    val disabledTools: Set<String> = emptySet(),
    /** OAuth details when [auth] is OAUTH; the person types these from the server's docs. */
    val oauthAuthorizeUrl: String = "",
    val oauthTokenUrl: String = "",
    val oauthClientId: String = "",
    val oauthScopes: String = "",
) {
    /** Short lowercase slug used in tool names: mcp_<slug>_<tool>. */
    val slug: String get() = McpNames.slug(name.ifBlank { id })
    val secretName: String get() = "mcp.$id"

    fun validate(): String? = when {
        name.isBlank() -> "Give the server a name."
        !(url.startsWith("https://") || url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1") || url.startsWith("http://10.0.2.2")) ->
            "The URL must start with https:// (plain http:// only works to this phone)."
        auth == McpAuth.OAUTH && (oauthAuthorizeUrl.isBlank() || oauthTokenUrl.isBlank() || oauthClientId.isBlank()) ->
            "OAuth needs the authorize URL, token URL and client ID from the server's docs."
        else -> null
    }
}

object McpNames {
    /** Model APIs accept ^[a-zA-Z0-9_-]{1,64}$ for tool names. */
    private val BAD = Regex("[^a-z0-9_]+")

    fun slug(s: String): String = s.lowercase().replace(BAD, "_").trim('_').ifBlank { "server" }.take(20)

    fun toolName(server: String, tool: String): String {
        val full = "mcp_${slug(server)}_${tool.lowercase().replace(BAD, "_").trim('_').ifBlank { "tool" }}"
        if (full.length <= 64) return full
        // Keep it unique when cut: the tail is a hash of the full name.
        val hash = Integer.toHexString(full.hashCode()).take(6)
        return full.take(57) + "_" + hash
    }

    private val READ_VERBS = Regex("^(get|list|search|read|fetch|find|query|describe|show|view|lookup|look_up|retrieve|count|check|browse|download|inspect|preview|explain|summari[sz]e)([_-]|$|[A-Z])", RegexOption.IGNORE_CASE)

    /**
     * How risky an MCP tool is. The server's own annotations win: readOnlyHint=true is a lookup.
     * Without them, a name that starts with a reading verb (get_, list_, search_...) is a lookup
     * and anything else may change things outside the phone, so the Guard treats it as OUTWARD
     * (reviewed, held when nobody is watching) and the tool itself asks the person first.
     */
    fun risk(def: McpToolDef): Risk = when {
        def.readOnlyHint == true -> Risk.READ
        def.readOnlyHint == false -> Risk.OUTWARD
        def.destructiveHint == true -> Risk.OUTWARD
        READ_VERBS.containsMatchIn(def.name) -> Risk.READ
        else -> Risk.OUTWARD
    }
}

/** One tool from an MCP server, as the agent sees it. */
class McpTool(
    private val serverName: String,
    val def: McpToolDef,
    private val call: suspend (String, JsonObject) -> McpCallResult,
) : Tool {
    override val name = McpNames.toolName(serverName, def.name)
    override val description = "[$serverName, via MCP] " + (def.description.ifBlank { def.title ?: def.name }).take(1_500)
    override val parameters: JsonObject = withTitle(def.inputSchema)
    override val declaredRisk: Risk = McpNames.risk(def)
    override val readOnly: Boolean = declaredRisk == Risk.READ

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val ownTitle = (def.inputSchema["properties"] as? JsonObject)?.containsKey("title") == true
        val sent = if (ownTitle) args.raw else JsonObject(args.raw - "title")
        if (declaredRisk != Risk.READ) {
            val detail = sent.entries.joinToString("\n") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.content ?: v.toString()}".take(200) }
            if (!ctx.confirm("Allow $serverName: ${def.title ?: def.name}?", "This MCP tool may change things in $serverName.\n\n$detail")) {
                return ToolResult.error("The person said no to this step. Don't retry it; ask what they'd like instead.")
            }
        }
        ctx.progress("Asking $serverName...")
        val r = call(def.name, sent)
        return ToolResult(
            text = r.text.ifBlank { if (r.isError) "The tool failed without saying why." else "(no output)" },
            isError = r.isError,
            images = r.images.map { (mime, data) -> ImagePart(mime, data) },
        )
    }

    companion object {
        /** The schema as the server gave it, plus D-Agent's "title" field when it has none. */
        fun withTitle(schema: JsonObject): JsonObject {
            val props = (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
            if (props.containsKey("title")) return ensureObject(schema)
            val title = buildJsonObject {
                put("type", "string")
                put("description", "A 3 to 8 word summary of this step, shown to the person.")
            }
            return JsonObject(ensureObject(schema) + ("properties" to JsonObject(props + ("title" to title))))
        }

        private fun ensureObject(schema: JsonObject): JsonObject =
            if (schema["type"] == null) JsonObject(schema + ("type" to JsonPrimitive("object"))) else schema
    }
}

/**
 * Serves one MCP server's tools to the [dev.dagent.core.connectors.ConnectorRegistry]: [refresh]
 * asks the server for its list (a network call), [tools] hands out the last list it got, minus
 * the tools the person switched off.
 */
class McpToolProvider(
    private val config: () -> McpServerConfig,
    val client: McpClient,
) : ToolProvider {
    @Volatile var discovered: List<McpToolDef> = emptyList()
        private set
    @Volatile var refreshedAt: Long = 0
        private set

    override suspend fun refresh() {
        client.initialize()
        discovered = client.listTools()
        refreshedAt = System.currentTimeMillis()
    }

    override fun tools(agentMode: Boolean): List<Tool> {
        val c = config()
        if (!c.enabled) return emptyList()
        return discovered.filter { it.name !in c.disabledTools }.map { def -> McpTool(c.name, def) { n, a -> client.callTool(n, a) } }
    }
}
