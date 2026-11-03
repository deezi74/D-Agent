package dev.dagent.core

import dev.dagent.core.oauth.OAuthClient
import dev.dagent.core.oauth.OAuthException
import dev.dagent.core.oauth.OAuthProviders
import dev.dagent.core.oauth.Pkce
import dev.dagent.core.oauth.TokenManager
import dev.dagent.core.oauth.TokenSet
import dev.dagent.core.oauth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OAuthTest {
    private var now = 1_000_000L
    private val client = OAuthClient(OkHttpClient()) { now }

    @Test fun pkceMatchesRfc7636Example() {
        // BASE64URL(SHA256(verifier)) without padding, value cross-checked with Python hashlib.
        assertEquals("bwWFMyPfdG9qreDhH2lmftFx_dFeLDalzcT1gb_j68g", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r7wW1gFWFOEjXk"))
        val v = Pkce.verifier()
        assertTrue(v.length in 43..128 && Regex("^[A-Za-z0-9_-]+$").matches(v))
        assertTrue(Pkce.state() != Pkce.state())
    }

    @Test fun authorizeUrlAndRedirect() {
        val cfg = OAuthProviders.google("123.apps.googleusercontent.com", "dev.dagent", write = false)
        val p = client.start("google")
        val url = client.authorizeUrl(cfg, p).toHttpUrl()
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("dev.dagent:/oauth2redirect", url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(Pkce.challenge(p.verifier), url.queryParameter("code_challenge"))
        assertEquals(p.state, url.queryParameter("state"))
        assertTrue(url.queryParameter("scope")!!.contains(OAuthProviders.GMAIL_READ))
        assertFalse(url.queryParameter("scope")!!.contains(OAuthProviders.GMAIL_COMPOSE))
        assertEquals("offline", url.queryParameter("access_type"))

        assertEquals("abc/1", client.codeFrom("dev.dagent:/oauth2redirect?state=${p.state}&code=abc%2F1", p))
        try { client.codeFrom("dev.dagent:/oauth2redirect?state=evil&code=x", p); fail() } catch (e: OAuthException) { assertEquals("state_mismatch", e.error) }
        try { client.codeFrom("dev.dagent:/oauth2redirect?error=access_denied&state=${p.state}", p); fail() } catch (e: OAuthException) { assertEquals("access_denied", e.error) }
        now += 11 * 60_000
        try { client.codeFrom("dev.dagent:/oauth2redirect?state=${p.state}&code=x", p); fail() } catch (e: OAuthException) { assertEquals("expired", e.error) }
    }

    @Test fun tokenRequestsAreFormEncodedWithoutSecrets() {
        val cfg = OAuthProviders.google("cid", "dev.dagent", false)
        val ex = client.exchangeRequest(cfg, "the-code", "the-verifier")
        val body = Buffer().also { ex.body!!.writeTo(it) }.readUtf8()
        assertEquals("https://oauth2.googleapis.com/token", ex.url.toString())
        assertTrue(body.contains("grant_type=authorization_code") && body.contains("code_verifier=the-verifier") && body.contains("client_id=cid"))
        assertFalse(body.contains("secret"))
        val rf = Buffer().also { client.refreshRequest(cfg, "r1").body!!.writeTo(it) }.readUtf8()
        assertTrue(rf.contains("grant_type=refresh_token") && rf.contains("refresh_token=r1"))
    }

    @Test fun exchangeAndRefreshAgainstAFakeServer() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"access_token":"a1","refresh_token":"r1","expires_in":3600,"scope":"x"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"a2","expires_in":3600}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Token has been revoked"}"""))
        server.start()
        val cfg = OAuthProviders.custom("https://x/auth", server.url("/token").toString(), "cid", "a b", OAuthProviders.DAGENT_REDIRECT)
        val t = client.exchange(cfg, "c", "v")
        assertEquals(TokenSet("a1", "r1", now + 3_600_000, "x"), t)
        val t2 = client.refresh(cfg, t)
        assertEquals("a2", t2.accessToken)
        assertEquals("r1", t2.refreshToken) // kept
        try { client.refresh(cfg, t2); fail() } catch (e: OAuthException) { assertEquals("invalid_grant", e.error) }
        server.shutdown()
    }

    @Test fun deviceFlowPollsUntilApproved() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"device_code":"d","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""))
        server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
        server.enqueue(MockResponse().setBody("""{"error":"slow_down","interval":10}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"gho_x","token_type":"bearer","scope":"repo"}"""))
        server.start()
        val cfg = OAuthProviders.github("cid", false).copy(deviceCodeUrl = server.url("/device").toString(), tokenUrl = server.url("/token").toString())
        val code = client.deviceStart(cfg)
        assertEquals("ABCD-1234", code.userCode)
        val waits = mutableListOf<Long>()
        val t = client.devicePoll(cfg, code) { waits += it }
        assertEquals("gho_x", t.accessToken)
        assertEquals(0L, t.expiresAt)
        assertEquals(listOf(5_000L, 5_000L, 10_000L), waits)
        server.takeRequest()
        val poll = server.takeRequest().body.readUtf8()
        assertTrue(poll.contains("device_code=d") && poll.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        server.shutdown()
    }

    private class MemStore : TokenStore {
        val m = HashMap<String, TokenSet>()
        override fun load(id: String) = m[id]
        override fun save(id: String, tokens: TokenSet) { m[id] = tokens }
        override fun clear(id: String) { m.remove(id) }
    }

    @Test fun managerRefreshesBeforeExpiryAndClearsOnRevoke() = runBlocking {
        val store = MemStore()
        var refreshes = 0
        var revoke = false
        val mgr = TokenManager(store, { _, t ->
            refreshes++
            if (revoke) throw OAuthException("revoked", "invalid_grant")
            t.copy(accessToken = "fresh$refreshes", expiresAt = now + 3_600_000)
        }) { now }
        assertNull(mgr.accessToken("g"))
        store.save("g", TokenSet("old", "r", expiresAt = now + 3_600_000))
        assertEquals("old", mgr.accessToken("g"))
        now += 3_600_000 - 60_000 // a minute left: refresh early
        assertEquals("fresh1", mgr.accessToken("g"))
        assertEquals("fresh1", store.load("g")!!.accessToken)
        now += 3_600_000
        revoke = true
        assertNull(mgr.accessToken("g"))
        assertFalse(mgr.signedIn("g"))
        // No expiry (GitHub OAuth app tokens): never refreshed.
        store.save("gh", TokenSet("gho"))
        assertEquals("gho", mgr.accessToken("gh"))
        assertEquals(2, refreshes)
        assertEquals(TokenSet("gho"), TokenSet.fromJson(TokenSet("gho").toJson()))
    }
}
