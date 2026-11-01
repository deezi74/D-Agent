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

    var googlePlacesKey: String
        get() = settings.secret("google_places")
        set(v) = settings.setSecret("google_places", v)

    var emailAccounts: List<EmailAccount>
        get() = runCatching { json.decodeFromString(ListSerializer(EmailAccount.serializer()), settings.secret("email_accounts")) }.getOrDefault(emptyList())
        set(v) = settings.setSecret("email_accounts", json.encodeToString(ListSerializer(EmailAccount.serializer()), v))
}
