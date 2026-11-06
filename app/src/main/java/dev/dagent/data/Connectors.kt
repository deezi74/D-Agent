package dev.dagent.data

import android.content.Context
import dev.dagent.connectors.EmailAccount
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Switches and accounts for the connectors (email, GitHub, Telegram, Termux, places). Secrets live in [Settings]' encrypted store. */
class Connectors(context: Context, private val settings: Settings) {
    private val prefs = context.getSharedPreferences("connectors", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var emailOrganize: Boolean
        get() = prefs.getBoolean("email.organize", false)
        set(v) { prefs.edit().putBoolean("email.organize", v).apply() }

    var githubWrite: Boolean
        get() = prefs.getBoolean("github.write", false)
        set(v) { prefs.edit().putBoolean("github.write", v).apply() }

    var termuxOn: Boolean
        get() = prefs.getBoolean("termux.on", false)
        set(v) { prefs.edit().putBoolean("termux.on", v).apply() }

    /** Repos the app builder created; it only ever writes to these. */
    var builtApps: Set<String>
        get() = prefs.getStringSet("apps.built", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("apps.built", v).apply() }

    var githubToken: String
        get() = settings.secret("github")
        set(v) = settings.setSecret("github", v)

    /** Bland AI key for calls where the AI talks. */
    /** Your PC: D-Agent PC's address and pairing code. */
    var pcUrl: String
        get() = prefs.getString("pc.url", "")!!
        set(v) { prefs.edit().putString("pc.url", v.trim()).apply() }

    var pcCode: String
        get() = settings.secret("pc_code")
        set(v) = settings.setSecret("pc_code", v.trim())

    /** PC tasks to report when they finish, as "id|title". */
    var pcWatched: Set<String>
        get() = prefs.getStringSet("pc.watched", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("pc.watched", v).apply() }

    var aiCallKey: String
        get() = settings.secret("ai_call")
        set(v) = settings.setSecret("ai_call", v)

    /** Most the AI calls may spend each month, in dollars (0 = no limit). */
    var aiCallCap: Double
        get() = prefs.getFloat("ai_call.cap", dev.dagent.core.tools.CallBudget.DEFAULT_CAP.toFloat()).toDouble()
        set(v) { prefs.edit().putFloat("ai_call.cap", v.coerceAtLeast(0.0).toFloat()).apply() }

    /** What the AI calls spent this month (see CallBudget). */
    val aiCallBudget = dev.dagent.core.tools.CallBudget(
        load = { prefs.getString("ai_call.spend", "")!! },
        save = { prefs.edit().putString("ai_call.spend", it).commit() },
        limit = { aiCallCap },
    )

    /** Who makes calls: "auto", "manus", "ai_call" (Bland), "phone" (your own phone, you talk) or "ask" (each time). */
    var aiCaller: String
        get() = prefs.getString("ai_caller", "auto")!!
        set(v) { prefs.edit().putString("ai_caller", v).apply() }

    /** The tool that should make AI-talking calls right now, or null when none is set up. */
    fun aiCallerTool(): String? {
        val ready = listOfNotNull("manus".takeIf { manusKey.isNotBlank() }, "ai_call".takeIf { aiCallKey.isNotBlank() })
        if (aiCaller == "phone") return null
        return aiCaller.takeIf { it in ready } ?: ready.firstOrNull()
    }

    /** True when the person wants to pick bot or own phone for every call. */
    fun askWhoCalls(): Boolean = aiCaller == "ask"

    /** The person's own Manus agent (its API key, which agent, and a monthly call limit). */
    var manusKey: String
        get() = settings.secret("manus")
        set(v) = settings.setSecret("manus", v)

    /** The chosen agent's main task id; blank means Manus's default agent. */
    var manusAgent: String
        get() = prefs.getString("manus.agent", "")!!
        set(v) { prefs.edit().putString("manus.agent", v.trim()).apply() }
    var manusAgentName: String
        get() = prefs.getString("manus.agent_name", "")!!
        set(v) { prefs.edit().putString("manus.agent_name", v).apply() }

    /** Most calls the Manus agent may make for D-Agent each month (0 = no limit). */
    var manusCap: Int
        get() = prefs.getInt("manus.cap", 20)
        set(v) { prefs.edit().putInt("manus.cap", v.coerceAtLeast(0)).apply() }

    val manusCalls = dev.dagent.core.tools.MonthlyCount(
        load = { prefs.getString("manus.calls", "")!! },
        save = { prefs.edit().putString("manus.calls", it).commit() },
        limit = { manusCap },
    )

    /** The person's own details for checkout forms and for saying who a call is for. */
    var myName: String
        get() = settings.secret("me.name")
        set(v) = settings.setSecret("me.name", v)
    var myPhone: String
        get() = settings.secret("me.phone")
        set(v) = settings.setSecret("me.phone", v)
    var myEmail: String
        get() = settings.secret("me.email")
        set(v) = settings.setSecret("me.email", v)
    var myAddress: String
        get() = settings.secret("me.address")
        set(v) = settings.setSecret("me.address", v)

    fun ownerName(): String = myName.trim().ifBlank { "the phone's owner" }

    fun detailsText(): String = listOf("Name" to myName, "Phone" to myPhone, "Email" to myEmail, "Delivery address" to myAddress)
        .filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }

    var googlePlacesKey: String
        get() = settings.secret("google_places")
        set(v) = settings.setSecret("google_places", v)

    var emailAccounts: List<EmailAccount>
        get() = runCatching { json.decodeFromString(ListSerializer(EmailAccount.serializer()), settings.secret("email_accounts")) }.getOrDefault(emptyList())
        set(v) = settings.setSecret("email_accounts", json.encodeToString(ListSerializer(EmailAccount.serializer()), v))
}
