package dev.dagent.core.oauth

/**
 * The OAuth providers D-Agent knows. Client IDs are never built in: the app reads them from
 * local.properties (see README, "Connectors and sign-in") and passes them here. Blank = the
 * owner hasn't created the OAuth client yet, so the connector says so instead of signing in.
 */
object OAuthProviders {
    // Google: read-only scopes by default; write scopes only when the person opts in.
    const val GMAIL_READ = "https://www.googleapis.com/auth/gmail.readonly"
    const val CALENDAR_READ = "https://www.googleapis.com/auth/calendar.readonly"
    const val DRIVE_READ = "https://www.googleapis.com/auth/drive.readonly"
    const val GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
    const val CALENDAR_EVENTS = "https://www.googleapis.com/auth/calendar.events"

    val GOOGLE_READ_SCOPES = listOf("openid", "email", GMAIL_READ, CALENDAR_READ, DRIVE_READ)
    /** Opt-in: create Gmail drafts and calendar events. */
    val GOOGLE_WRITE_SCOPES = listOf(GMAIL_COMPOSE, CALENDAR_EVENTS)

    /**
     * Google sign-in for an "Android" OAuth client with "custom URI scheme" enabled; the redirect
     * is the app's package name as a scheme, e.g. dev.dagent:/oauth2redirect (debug builds:
     * dev.dagent.debug:/oauth2redirect).
     */
    fun google(clientId: String, packageName: String, write: Boolean) = OAuthConfig(
        authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl = "https://oauth2.googleapis.com/token",
        clientId = clientId,
        redirectUri = "$packageName:/oauth2redirect",
        scopes = GOOGLE_READ_SCOPES + if (write) GOOGLE_WRITE_SCOPES else emptyList(),
        // offline + consent: Google only sends a refresh token when asked like this.
        extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent", "include_granted_scopes" to "true"),
    )

    /** GitHub OAuth app with Device Flow enabled: no redirect and no client secret. */
    fun github(clientId: String, write: Boolean) = OAuthConfig(
        authorizeUrl = "https://github.com/login/oauth/authorize",
        tokenUrl = "https://github.com/login/oauth/access_token",
        deviceCodeUrl = "https://github.com/login/device/code",
        clientId = clientId,
        redirectUri = "",
        // repo covers private repos and issues; workflow lets the app builder push CI files.
        scopes = if (write) listOf("repo", "workflow", "read:user", "notifications") else listOf("repo", "read:user", "notifications"),
    )

    /** For an MCP server whose docs give an authorize URL, token URL and client ID. */
    fun custom(authorizeUrl: String, tokenUrl: String, clientId: String, scopes: String, redirect: String) = OAuthConfig(
        authorizeUrl = authorizeUrl.trim(), tokenUrl = tokenUrl.trim(), clientId = clientId.trim(), redirectUri = redirect,
        scopes = scopes.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() },
    )

    /** The app's own redirect for custom (MCP) OAuth: dagent://oauth */
    const val DAGENT_REDIRECT = "dagent://oauth"
}
