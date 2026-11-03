package dev.dagent.core.connectors

import dev.dagent.core.agent.Tool
import java.util.concurrent.ConcurrentHashMap

/** How a connector proves who the person is to the service. */
enum class AuthType(val label: String) {
    /** Browser sign-in with PKCE (RFC 7636); tokens are refreshed by themselves. */
    OAUTH_PKCE("Sign in with the browser"),
    /** OAuth device flow (RFC 8628): the person types a short code on the service's site. */
    OAUTH_DEVICE("Sign in with a code"),
    /** A key or token the person pastes. */
    API_KEY("Key or token"),
    NONE("No sign-in"),
}

enum class ConnectorState(val label: String) {
    CONNECTED("Connected"),
    NEEDS_SIGN_IN("Needs sign-in"),
    ERROR("Error"),
    /** Set up but switched off by the person. */
    OFF("Off"),
}

data class ConnectorStatus(val state: ConnectorState, val detail: String = "") {
    companion object {
        val CONNECTED = ConnectorStatus(ConnectorState.CONNECTED)
        fun needsSignIn(detail: String = "") = ConnectorStatus(ConnectorState.NEEDS_SIGN_IN, detail)
        fun error(detail: String) = ConnectorStatus(ConnectorState.ERROR, detail)
        val OFF = ConnectorStatus(ConnectorState.OFF)
    }
}

/**
 * Where a connector's tools come from. [tools] must be quick and must not touch the network
 * (it runs while the prompt is built); [refresh] is where slow work such as asking an MCP server
 * for its tool list happens.
 */
interface ToolProvider {
    /** The tools for this run. [agentMode] false means quick chat, where only light tools belong. */
    fun tools(agentMode: Boolean): List<Tool>

    /** Re-reads whatever [tools] serves from (network, disk). Throws on failure. */
    suspend fun refresh() {}

    companion object {
        /** A provider with a fixed list (or nothing in chat mode when [agentOnly]). */
        fun of(agentOnly: Boolean = false, build: () -> List<Tool>) = object : ToolProvider {
            override fun tools(agentMode: Boolean) = if (agentOnly && !agentMode) emptyList() else build()
        }
    }
}

/**
 * One service the agent can use: the built-in ones (GitHub, Google, email...) and every MCP
 * server the person adds. [status] says whether it can be used right now.
 */
data class Connector(
    val id: String,
    val name: String,
    val auth: AuthType,
    /** OAuth scopes the connector asks for (empty for keys and no-auth). */
    val scopes: List<String> = emptyList(),
    val provider: ToolProvider,
    val status: () -> ConnectorStatus = { ConnectorStatus.CONNECTED },
    val description: String = "",
)

/**
 * Every connector, in the order they were registered, and the single place the agent's
 * connector tools come from. Tools of connectors that aren't [ConnectorState.CONNECTED] are left
 * out; a provider that throws is reported as an error instead of breaking the run, and a tool
 * name that was already taken is skipped (the first one wins), so the list is stable.
 */
class ConnectorRegistry {
    private val connectors = LinkedHashMap<String, Connector>()
    private val errors = ConcurrentHashMap<String, String>()

    @Synchronized fun register(c: Connector) { connectors[c.id] = c }
    @Synchronized fun unregister(id: String) { connectors.remove(id); errors.remove(id) }
    @Synchronized fun all(): List<Connector> = connectors.values.toList()
    @Synchronized fun get(id: String): Connector? = connectors[id]

    /** The connector's own status, or the last error from building or refreshing its tools. */
    fun status(id: String): ConnectorStatus {
        val c = get(id) ?: return ConnectorStatus.error("Unknown connector")
        val own = runCatching { c.status() }.getOrElse { ConnectorStatus.error(it.message ?: it.javaClass.simpleName) }
        if (own.state != ConnectorState.CONNECTED) return own
        return errors[id]?.let { ConnectorStatus.error(it) } ?: own
    }

    /** Asks every connected connector to refresh; failures are recorded, not thrown. */
    suspend fun refresh(ids: Collection<String>? = null) {
        for (c in all()) {
            if (ids != null && c.id !in ids) continue
            if (runCatching { c.status() }.getOrNull()?.state != ConnectorState.CONNECTED) continue
            try {
                c.provider.refresh()
                errors.remove(c.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                errors[c.id] = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun clearError(id: String) { errors.remove(id) }

    /** Every usable connector tool, for one run. */
    fun tools(agentMode: Boolean, taken: Set<String> = emptySet()): List<Tool> {
        val names = HashSet(taken)
        val out = ArrayList<Tool>()
        for (c in all()) {
            if (runCatching { c.status() }.getOrNull()?.state != ConnectorState.CONNECTED) continue
            val list = try {
                c.provider.tools(agentMode)
            } catch (e: Throwable) {
                errors[c.id] = e.message ?: e.javaClass.simpleName
                continue
            }
            for (t in list) if (names.add(t.name)) out += t
        }
        return out
    }
}
