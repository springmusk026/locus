package dev.locus.core.agent

import dev.locus.core.agent.auth.ChatGptAccount
import dev.locus.core.agent.auth.ChatGptAuthException
import dev.locus.core.agent.auth.ChatGptOAuth
import dev.locus.core.agent.auth.ChatGptSession
import dev.locus.core.agent.auth.ChatGptTokens
import dev.locus.core.agent.auth.OAuthRedirect
import dev.locus.core.agent.mcp.McpOAuth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatGptOAuthTest {
    private val now = 1_790_000_000_000L
    private val redirect = "http://127.0.0.1:1455/auth/callback"
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val seen = mutableListOf<HttpRequestData>()

    /** Token endpoint answers; [refreshDelay] keeps a refresh in flight so concurrent callers can race. */
    private var tokenResponse: (Map<String, String>) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" }
    private var refreshDelay = 0L

    private val oauth = ChatGptOAuth(
        HttpClient(MockEngine { req ->
            seen += req
            when (req.url.toString().substringBefore('?')) {
                ChatGptOAuth.DISCOVERY_URL -> respond(
                    """{"issuer":"https://auth.openai.com","jwks_uri":"https://auth.openai.com/.well-known/jwks.json","revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke"}""",
                    headers = json,
                )
                "https://auth.openai.com/.well-known/jwks.json" -> respond(jwks(), headers = json)
                ChatGptOAuth.TOKEN_URL -> {
                    val form = form(req)
                    if (form["grant_type"] == "refresh_token") delay(refreshDelay)
                    val (status, body) = tokenResponse(form)
                    respond(body, status, json)
                }
                "https://auth.openai.com/api/accounts/oauth/revoke" -> respond("", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }),
        now = { now },
    )

    @Test
    fun `first sign-in registers with the dynamic client and every required parameter`() {
        val pending = oauth.authorizationRequest("urn:uuid:host-1", redirect, account = null)
        val url = Url(pending.url)
        assertEquals("https://auth.openai.com/api/accounts/authorize", url.toString().substringBefore('?'))
        val q = url.parameters
        assertEquals("dynamic_agent_client", q["client_id"])
        assertEquals("Locus", q["agent_name_hint"])
        assertEquals("urn:uuid:host-1", q["ext_agent_host_id"])
        assertEquals("code", q["response_type"])
        assertEquals(redirect, q["redirect_uri"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", q["scope"])
        assertEquals("https://api.openai.com/v1", q["resource"])
        assertEquals(pending.state, q["state"])
        assertEquals(pending.nonce, q["nonce"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(McpOAuth.challengeFor(pending.verifier), q["code_challenge"])
        assertNull(q["login_hint"])
        assertNull(q["id_token_hint"])
        assertNull(q["prompt"])
        assertNull(pending.clientId)
        assertTrue(pending.state != pending.nonce && pending.verifier.length >= 43)
    }

    @Test
    fun `later sign-ins reuse the issued client with hints and never the name hint`() {
        val account = ChatGptAccount("oaiapp_123", "user-1", "a@example.com")
        val q = Url(oauth.authorizationRequest("urn:uuid:host-1", "http://127.0.0.1:54321/auth/callback", account, idTokenHint = "id.tok.en").url).parameters
        assertEquals("oaiapp_123", q["client_id"])
        assertNull(q["agent_name_hint"])
        assertEquals("a@example.com", q["login_hint"])
        assertEquals("id.tok.en", q["id_token_hint"])
        assertEquals("urn:uuid:host-1", q["ext_agent_host_id"])
        assertNull(q["prompt"])
        assertEquals("consent", Url(oauth.authorizationRequest("h", redirect, account.copy(planDeclined = true)).url).parameters["prompt"])
        // Only a 127.0.0.1 loopback on /auth/callback is accepted.
        assertFailsWith<IllegalArgumentException> { oauth.authorizationRequest("h", "http://localhost:1455/auth/callback", null) }
        assertFailsWith<IllegalArgumentException> { oauth.authorizationRequest("h", "http://127.0.0.1:1455/callback", null) }
    }

    @Test
    fun `callback checks state, errors and the issued client id`() {
        val fresh = oauth.authorizationRequest("h", redirect, null)
        assertEquals("oaiapp_new", oauth.callbackClientId(fresh, mapOf("state" to fresh.state, "code" to "c", "client_id" to "oaiapp_new")))
        assertFailsWith<ChatGptAuthException> { oauth.callbackClientId(fresh, mapOf("state" to "forged", "code" to "c", "client_id" to "oaiapp_new")) }
        assertTrue("cancelled" in assertFailsWith<ChatGptAuthException> { oauth.callbackClientId(fresh, mapOf("state" to fresh.state, "error" to "access_denied")) }.message!!)
        // A new registration must bring back an issued id, and never the registration entry point.
        assertFailsWith<ChatGptAuthException> { oauth.callbackClientId(fresh, mapOf("state" to fresh.state, "code" to "c")) }
        assertFailsWith<ChatGptAuthException> { oauth.callbackClientId(fresh, mapOf("state" to fresh.state, "code" to "c", "client_id" to "dynamic_agent_client")) }

        val again = oauth.authorizationRequest("h", redirect, ChatGptAccount("oaiapp_1", "user-1"))
        assertEquals("oaiapp_1", oauth.callbackClientId(again, mapOf("state" to again.state, "code" to "c")))
        assertFailsWith<ChatGptAuthException> { oauth.callbackClientId(again, mapOf("state" to again.state, "code" to "c", "client_id" to "oaiapp_2")) }
    }

    @Test
    fun `redirect parsing takes only the exact callback path`() {
        assertEquals(mapOf("code" to "a b", "state" to "s=1", "client_id" to "oaiapp_x"), OAuthRedirect.params("/auth/callback?code=a+b&state=s%3D1&client_id=oaiapp_x", "/auth/callback"))
        assertNull(OAuthRedirect.params("/favicon.ico", "/auth/callback"))
        assertNull(OAuthRedirect.params("/auth/callback/x?state=s", "/auth/callback"))
        assertNull(OAuthRedirect.params("/callback?state=s", "/auth/callback"))
        // A repeated parameter is refused rather than guessed at.
        assertNull(OAuthRedirect.params("/auth/callback?state=a&state=b", "/auth/callback"))
    }

    @Test
    fun `completes sign-in with a public-client exchange and a verified id token`() = runTest {
        val pending = oauth.authorizationRequest("h", redirect, null)
        tokenResponse = { HttpStatusCode.OK to tokens(idToken(aud = "oaiapp_new", nonce = pending.nonce)) }
        val session = oauth.complete(pending, mapOf("state" to pending.state, "code" to "code-1", "client_id" to "oaiapp_new"), oauth.discover())

        val form = form(seen.first { it.url.toString() == ChatGptOAuth.TOKEN_URL })
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code", "client_id" to "oaiapp_new", "code" to "code-1",
                "code_verifier" to pending.verifier, "redirect_uri" to redirect, "resource" to "https://api.openai.com/v1",
            ),
            form,
        )
        assertEquals("oaiapp_new", session.clientId)
        assertEquals("user-1", session.subject)
        assertEquals("a@example.com", session.email)
        assertEquals("at-1", session.accessToken)
        assertEquals("rt-1", session.refreshToken)
        assertEquals(now + 3_600_000, session.expiresAt)
        assertEquals(listOf("chatgpt.tokens.use.direct", "email", "offline_access", "openid", "profile", "resource.invoke"), session.scopes)
        assertTrue(session.canUsePlan)
    }

    @Test
    fun `rejects id tokens with the wrong nonce, audience, issuer or signature`() = runTest {
        val discovery = oauth.discover()
        suspend fun attempt(token: (String) -> String): ChatGptAuthException {
            val pending = oauth.authorizationRequest("h", redirect, null)
            tokenResponse = { HttpStatusCode.OK to tokens(token(pending.nonce)) }
            return assertFailsWith { oauth.complete(pending, mapOf("state" to pending.state, "code" to "c", "client_id" to "oaiapp_new"), discovery) }
        }
        attempt { idToken(aud = "oaiapp_new", nonce = "other") }
        attempt { idToken(aud = "oaiapp_someone_else", nonce = it) }
        attempt { idToken(aud = "oaiapp_new", nonce = it, iss = "https://evil.test") }
        attempt { idToken(aud = "oaiapp_new", nonce = it, exp = now / 1000 - 3_600) }
        attempt { idToken(aud = "oaiapp_new", nonce = it).let { t -> t.substringBeforeLast('.') + ".AAAA" } }
        // A returning account must come back as the same person.
        val again = oauth.authorizationRequest("h", redirect, ChatGptAccount("oaiapp_new", "user-2"))
        tokenResponse = { HttpStatusCode.OK to tokens(idToken(aud = "oaiapp_new", nonce = again.nonce)) }
        assertFailsWith<ChatGptAuthException> { oauth.complete(again, mapOf("state" to again.state, "code" to "c"), discovery) }
    }

    @Test
    fun `a grant without the plan scope can't be used for requests`() = runTest {
        val pending = oauth.authorizationRequest("h", redirect, null)
        tokenResponse = { HttpStatusCode.OK to tokens(idToken(aud = "oaiapp_new", nonce = pending.nonce), scope = "openid profile email offline_access") }
        val session = oauth.complete(pending, mapOf("state" to pending.state, "code" to "c", "client_id" to "oaiapp_new"), oauth.discover())
        assertFalse(session.canUsePlan)
        val store = mutableMapOf<String, ChatGptSession?>("p" to session)
        assertFailsWith<ChatGptAuthException> { ChatGptTokens(oauth, { store[it] }, { k, v -> store[k] = v }, { now }).accessToken("p") }
    }

    @Test
    fun `concurrent callers share one refresh and keep the rotated refresh token`() = runTest {
        var refreshes = 0
        tokenResponse = { form ->
            refreshes++
            assertEquals(mapOf("grant_type" to "refresh_token", "client_id" to "oaiapp_1", "refresh_token" to "rt-1", "resource" to "https://api.openai.com/v1"), form)
            HttpStatusCode.OK to """{"access_token":"at-2","refresh_token":"rt-2","token_type":"Bearer","expires_in":3600}"""
        }
        refreshDelay = 50
        val store = mutableMapOf<String, ChatGptSession?>("p" to expiring())
        val tokens = ChatGptTokens(oauth, { store[it] }, { k, v -> store[k] = v }, { now })
        val a = async { tokens.accessToken("p") }
        val b = async { tokens.accessToken("p") }
        assertEquals("at-2", a.await())
        assertEquals("at-2", b.await())
        assertEquals(1, refreshes)
        assertEquals("rt-2", store["p"]!!.refreshToken)
        // Omitting scope on refresh keeps the grant.
        assertTrue(store["p"]!!.canUsePlan)
    }

    @Test
    fun `an unusable refresh token clears the session and asks to sign in again`() = runTest {
        tokenResponse = { HttpStatusCode.BadRequest to """{"error":"refresh_token_reused"}""" }
        val store = mutableMapOf<String, ChatGptSession?>("p" to expiring())
        val e = assertFailsWith<ChatGptAuthException> { ChatGptTokens(oauth, { store[it] }, { k, v -> store[k] = v }, { now }).accessToken("p") }
        assertTrue(e.signInAgain)
        assertNull(store["p"])

        tokenResponse = { HttpStatusCode.Unauthorized to """{"error":"invalid_client"}""" }
        store["p"] = expiring()
        assertTrue(assertFailsWith<ChatGptAuthException> { ChatGptTokens(oauth, { store[it] }, { k, v -> store[k] = v }, { now }).accessToken("p") }.clientInvalid)
    }

    @Test
    fun `a token far from expiry is used without refreshing`() = runTest {
        val store = mutableMapOf<String, ChatGptSession?>("p" to expiring().copy(expiresAt = now + 3_000_000))
        assertEquals("at-1", ChatGptTokens(oauth, { store[it] }, { k, v -> store[k] = v }, { now }).accessToken("p"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `sign-out revokes the refresh token at the discovered endpoint`() = runTest {
        assertTrue(oauth.revoke(expiring(), oauth.discover()))
        val form = form(seen.last())
        assertEquals("https://auth.openai.com/api/accounts/oauth/revoke", seen.last().url.toString())
        assertEquals(mapOf("token" to "rt-1", "token_type_hint" to "refresh_token", "client_id" to "oaiapp_1"), form)
        assertFalse(oauth.revoke(expiring(), ChatGptOAuth.Discovery("https://x.test/jwks", revocationEndpoint = null)))
    }

    private fun expiring() = ChatGptSession(
        clientId = "oaiapp_1", subject = "user-1", email = "a@example.com", accessToken = "at-1", refreshToken = "rt-1",
        scopes = ChatGptOAuth.SCOPE.split(' ').sorted(), expiresAt = now + 60_000, savedAt = now - 3_540_000,
    )

    private fun tokens(idToken: String, scope: String = "chatgpt.tokens.use.direct email offline_access openid profile resource.invoke") =
        """{"access_token":"at-1","refresh_token":"rt-1","id_token":"$idToken","token_type":"Bearer","expires_in":3600,"scope":"$scope"}"""

    private fun idToken(aud: String, nonce: String, iss: String = "https://auth.openai.com", exp: Long = now / 1000 + 3_600): String {
        val header = b64("""{"alg":"RS256","kid":"k1","typ":"JWT"}""".toByteArray())
        val claims = b64("""{"iss":"$iss","aud":"$aud","sub":"user-1","email":"a@example.com","nonce":"$nonce","iat":${now / 1000},"exp":$exp}""".toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update("$header.$claims".toByteArray())
            sign()
        }
        return "$header.$claims.${b64(signature)}"
    }

    private fun jwks(): String {
        val key = keys.public as RSAPublicKey
        return """{"keys":[{"kty":"RSA","kid":"other","n":"${b64(unsigned(BigInteger.TWO.pow(2047)))}","e":"AQAB"},{"kty":"RSA","kid":"k1","use":"sig","alg":"RS256","n":"${b64(unsigned(key.modulus))}","e":"${b64(unsigned(key.publicExponent))}"}]}"""
    }

    private fun unsigned(n: BigInteger) = n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun form(req: HttpRequestData): Map<String, String> {
        val text = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        return parseQueryString(text).entries().associate { (k, v) -> k to v.single() }
    }
}
