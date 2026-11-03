package dev.dagent.core.oauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) values: a random verifier and its S256 challenge, plus a random state. */
object Pkce {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** 43 characters of base64url from 32 random bytes (the RFC allows 43 to 128). */
    fun verifier(): String = b64.encodeToString(ByteArray(32).also(random::nextBytes))

    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun state(): String = b64.encodeToString(ByteArray(16).also(random::nextBytes))
}

/** One OAuth provider as an app (public client, no secret) talks to it. */
data class OAuthConfig(
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    /** Extra authorize-URL parameters (Google: access_type=offline, prompt=consent). */
    val extraAuthParams: Map<String, String> = emptyMap(),
    /** GitHub's device flow endpoint, for providers that use RFC 8628 instead of a redirect. */
    val deviceCodeUrl: String = "",
)

/** Tokens as kept in the app's SecretStore (serialized to JSON, encrypted there). */
@Serializable
data class TokenSet(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch millis when [accessToken] stops working; 0 = no expiry given. */
    val expiresAt: Long = 0,
    val scope: String = "",
    val tokenType: String = "Bearer",
) {
    /** True when the token is gone or will be within [skewMs]. */
    fun needsRefresh(now: Long, skewMs: Long = 120_000): Boolean = expiresAt in 1..(now + skewMs)

    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(s: String): TokenSet? = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), s) }.getOrNull()
    }
}

/** A sign-in that was started and is waiting for the browser to come back. */
@Serializable
data class PendingAuth(val connectorId: String, val state: String, val verifier: String, val createdAt: Long)

class OAuthException(message: String, val error: String = "") : Exception(message)

/**
 * The testable half of OAuth 2.0 for public clients: builds the authorize URL with PKCE, checks
 * the redirect, and builds and sends the token requests. No client secret is ever used.
 */
class OAuthClient(private val http: OkHttpClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }

    fun start(connectorId: String): PendingAuth = PendingAuth(connectorId, Pkce.state(), Pkce.verifier(), clock())

    fun authorizeUrl(config: OAuthConfig, pending: PendingAuth): String {
        val b = config.authorizeUrl.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("redirect_uri", config.redirectUri)
            .addQueryParameter("state", pending.state)
            .addQueryParameter("code_challenge", Pkce.challenge(pending.verifier))
            .addQueryParameter("code_challenge_method", "S256")
        if (config.scopes.isNotEmpty()) b.addQueryParameter("scope", config.scopes.joinToString(" "))
        config.extraAuthParams.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    /**
     * The code from the redirect URI the browser came back with. Throws when the state doesn't
     * match (a forged redirect), the provider sent an error, or the sign-in is older than 10 minutes.
     */
    fun codeFrom(redirect: String, pending: PendingAuth): String {
        val q = queryOf(redirect)
        q["error"]?.let { throw OAuthException("Sign-in was cancelled or refused: ${q["error_description"] ?: it}", it) }
        if (q["state"] != pending.state) throw OAuthException("The sign-in answer didn't match the one D-Agent started. Try again.", "state_mismatch")
        if (clock() - pending.createdAt > 10 * 60_000) throw OAuthException("The sign-in took too long. Try again.", "expired")
        return q["code"]?.takeIf { it.isNotBlank() } ?: throw OAuthException("The sign-in answer had no code.", "no_code")
    }

    fun exchangeRequest(config: OAuthConfig, code: String, verifier: String): Request = tokenRequest(config.tokenUrl, mapOf(
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to config.redirectUri,
        "client_id" to config.clientId,
        "code_verifier" to verifier,
    ))

    fun refreshRequest(config: OAuthConfig, refreshToken: String): Request = tokenRequest(config.tokenUrl, mapOf(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to config.clientId,
    ))

    suspend fun exchange(config: OAuthConfig, code: String, verifier: String): TokenSet = send(exchangeRequest(config, code, verifier), null)

    /** A refreshed token set; keeps the old refresh token when the provider doesn't send a new one. */
    suspend fun refresh(config: OAuthConfig, tokens: TokenSet): TokenSet {
        val rt = tokens.refreshToken ?: throw OAuthException("No refresh token; sign in again.", "invalid_grant")
        return send(refreshRequest(config, rt), rt)
    }

    // ---- device flow (RFC 8628), used for GitHub ----------------------------------------------

    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val intervalSec: Int, val expiresAt: Long)

    suspend fun deviceStart(config: OAuthConfig): DeviceCode {
        val o = post(tokenRequest(config.deviceCodeUrl, mapOf("client_id" to config.clientId, "scope" to config.scopes.joinToString(" "))))
        o.err()?.let { throw it }
        return DeviceCode(
            deviceCode = o.s("device_code") ?: throw OAuthException("No device code in the answer."),
            userCode = o.s("user_code").orEmpty(),
            verificationUri = o.s("verification_uri") ?: o.s("verification_url") ?: "",
            intervalSec = o.s("interval")?.toIntOrNull() ?: 5,
            expiresAt = clock() + (o.s("expires_in")?.toLongOrNull() ?: 900) * 1000,
        )
    }

    fun devicePollRequest(config: OAuthConfig, code: DeviceCode): Request = tokenRequest(config.tokenUrl, mapOf(
        "client_id" to config.clientId,
        "device_code" to code.deviceCode,
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
    ))

    /** Polls until the person approves, refuses or the code expires. */
    suspend fun devicePoll(config: OAuthConfig, code: DeviceCode, sleep: suspend (Long) -> Unit = { delay(it) }): TokenSet {
        var interval = code.intervalSec.coerceAtLeast(1)
        while (true) {
            if (clock() > code.expiresAt) throw OAuthException("The code expired. Start the sign-in again.", "expired_token")
            sleep(interval * 1000L)
            val o = post(devicePollRequest(config, code))
            when (o.s("error")) {
                null -> return parse(o, null)
                "authorization_pending" -> Unit
                "slow_down" -> interval = o.s("interval")?.toIntOrNull() ?: (interval + 5)
                "expired_token" -> throw OAuthException("The code expired. Start the sign-in again.", "expired_token")
                "access_denied" -> throw OAuthException("Sign-in was refused.", "access_denied")
                else -> throw o.err()!!
            }
        }
    }

    // ---- plumbing -----------------------------------------------------------------------------

    private fun tokenRequest(url: String, form: Map<String, String>): Request {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return Request.Builder().url(url).header("Accept", "application/json").post(body).build()
    }

    private suspend fun send(req: Request, oldRefresh: String?): TokenSet {
        val o = post(req)
        o.err()?.let { throw it }
        return parse(o, oldRefresh)
    }

    private suspend fun post(req: Request): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: if (!r.isSuccessful) throw OAuthException("HTTP ${r.code}: ${text.take(200)}") else queryOf("?$text").let { m -> JsonObject(m.mapValues { JsonPrimitive(it.value) }) }
            if (!r.isSuccessful && o["error"] == null) throw OAuthException("HTTP ${r.code}: ${text.take(200)}")
            o
        }
    }

    fun parse(o: JsonObject, oldRefresh: String?): TokenSet {
        val access = o.s("access_token") ?: throw OAuthException("No access token in the answer.")
        val expires = (o["expires_in"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
        return TokenSet(
            accessToken = access,
            refreshToken = o.s("refresh_token") ?: oldRefresh,
            expiresAt = if (expires != null && expires > 0) clock() + expires * 1000 else 0,
            scope = o.s("scope").orEmpty(),
            tokenType = o.s("token_type") ?: "Bearer",
        )
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.err(): OAuthException? = s("error")?.let { OAuthException("${s("error_description") ?: it} ($it)", it) }

    companion object {
        /** Query (or fragment) parameters of a redirect such as dev.dagent:/oauth2redirect?code=...&state=... */
        fun queryOf(uri: String): Map<String, String> {
            val q = uri.substringAfter('?', "").substringBefore('#').ifBlank { uri.substringAfter('#', "") }
            return q.split('&').filter { it.contains('=') }.associate {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        }
    }
}

/** Where tokens live. The app's implementation writes only to its encrypted SecretStore. */
interface TokenStore {
    fun load(id: String): TokenSet?
    fun save(id: String, tokens: TokenSet)
    fun clear(id: String)
}

/**
 * Hands out a working access token, refreshing it shortly before it expires (one refresh at a
 * time per manager). A refresh the provider rejects (invalid_grant: revoked or expired) clears
 * the tokens so the connector shows "needs sign-in"; a network failure keeps them for next time.
 */
class TokenManager(
    private val store: TokenStore,
    private val refresher: suspend (id: String, TokenSet) -> TokenSet,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    fun signedIn(id: String) = store.load(id) != null

    suspend fun accessToken(id: String): String? = lock.withLock {
        val t = store.load(id) ?: return null
        if (!t.needsRefresh(clock())) return t.accessToken
        if (t.refreshToken == null) {
            // Can't refresh: still usable until it really expires.
            return if (t.expiresAt > clock()) t.accessToken else { store.clear(id); null }
        }
        try {
            val fresh = refresher(id, t)
            store.save(id, fresh)
            fresh.accessToken
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is OAuthException && e.error == "invalid_grant") { store.clear(id); null }
            else if (t.expiresAt > clock()) t.accessToken else throw e
        }
    }
}
