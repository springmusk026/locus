package dev.locus.core.agent

import dev.locus.core.agent.mcp.McpAuthException
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpOAuthTest {
    private val seen = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** A server at https://mcp.test/v1/mcp whose authorization server is https://auth.test/t1. */
    private fun oauth(asMeta: String = AS_META, routes: Map<String, String> = emptyMap()) = McpOAuth(
        HttpClient(MockEngine { req ->
            seen += req
            val url = req.url.toString().substringBefore('?')
            when {
                url == "https://mcp.test/v1/mcp" -> respond("", HttpStatusCode.Unauthorized, headersOf("WWW-Authenticate", """Bearer error="invalid_token", scope="files:read""""))
                url == "https://mcp.test/.well-known/oauth-protected-resource/v1/mcp" -> respond("""{"resource":"https://mcp.test/v1/mcp","authorization_servers":["https://auth.test/t1"],"scopes_supported":["files:read","files:write"]}""", headers = json)
                url == "https://auth.test/.well-known/oauth-authorization-server/t1" -> respond(asMeta, headers = json)
                url in routes -> respond(routes.getValue(url), headers = json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }),
        now = { 1_000_000L },
    )

    @Test
    fun `well-known urls follow the spec order`() {
        assertEquals(
            listOf("https://mcp.test/.well-known/oauth-protected-resource/v1/mcp", "https://mcp.test/.well-known/oauth-protected-resource"),
            McpOAuth.protectedResourceMetadataUrls("https://mcp.test/v1/mcp/"),
        )
        assertEquals(
            listOf("https://auth.test/.well-known/oauth-authorization-server/t1", "https://auth.test/.well-known/openid-configuration/t1", "https://auth.test/t1/.well-known/openid-configuration"),
            McpOAuth.authServerMetadataUrls("https://auth.test/t1"),
        )
        assertEquals(listOf("https://auth.test/.well-known/oauth-authorization-server", "https://auth.test/.well-known/openid-configuration"), McpOAuth.authServerMetadataUrls("https://auth.test"))
        assertEquals("https://mcp.test:8443/mcp", McpOAuth.canonicalResource("HTTPS://MCP.test:8443/mcp/#x"))
    }

    @Test
    fun `parses challenges and pkce`() {
        val c = McpOAuth.parseChallenge("""Bearer resource_metadata="https://a.test/.well-known/oauth-protected-resource", scope="a b", error=invalid_token""")
        assertEquals("https://a.test/.well-known/oauth-protected-resource", c["resource_metadata"])
        assertEquals("a b", c["scope"])
        assertEquals("invalid_token", c["error"])
        // Expected value computed independently: base64url(sha256(verifier)) without padding, via Python hashlib.
        assertEquals("G2YlssBI6uV1Nn1jb05JfDIjHFtLs90DSNMW0T4rR-Q", McpOAuth.challengeFor("dBjftJeZ4CVP-mJ92K9rtvlAAEqASm4C5Bmt4BYpYJk"))
    }

    @Test
    fun `discovers, registers, authorizes and exchanges the code`() = runTest {
        val oauth = oauth(routes = mapOf(
            "https://auth.test/register" to """{"client_id":"locus-123"}""",
            "https://auth.test/token" to """{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}""",
        ))
        val d = oauth.discover("https://mcp.test/v1/mcp")!!
        assertEquals("https://mcp.test/v1/mcp", d.resource)
        assertEquals("files:read", d.scope) // the challenge's scope wins over scopes_supported
        val clientId = oauth.register(d.server, "http://127.0.0.1:33418/callback")
        assertEquals("locus-123", clientId)
        val reg = (seen.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue("\"application_type\":\"native\"" in reg && "\"token_endpoint_auth_method\":\"none\"" in reg)

        val pending = oauth.authorizationRequest(d, clientId, "http://127.0.0.1:33418/callback")
        val q = Url(pending.url).parameters
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(McpOAuth.challengeFor(pending.verifier), q["code_challenge"])
        assertEquals("https://mcp.test/v1/mcp", q["resource"])
        assertEquals("files:read offline_access", q["scope"])

        val session = oauth.complete(d, clientId, pending, mapOf("code" to "c1", "state" to pending.state, "iss" to "https://auth.test/t1"))
        val form = (seen.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue("code_verifier=${pending.verifier}" in form && "resource=https%3A%2F%2Fmcp.test%2Fv1%2Fmcp" in form)
        assertEquals("at", session.accessToken)
        assertEquals("rt", session.refreshToken)
        assertEquals(1_000_000L + 3_600_000L, session.expiresAt)
    }

    @Test
    fun `rejects wrong state, wrong issuer and missing pkce`() = runTest {
        val oauth = oauth()
        val d = oauth.discover("https://mcp.test/v1/mcp")!!
        val pending = oauth.authorizationRequest(d, "c", "http://127.0.0.1:1/callback")
        assertFailsWith<McpAuthException> { oauth.complete(d, "c", pending, mapOf("code" to "x", "state" to "other")) }
        assertFailsWith<McpAuthException> { oauth.complete(d, "c", pending, mapOf("code" to "x", "state" to pending.state, "iss" to "https://evil.test")) }
        assertFailsWith<McpAuthException> {
            oauth(AS_META.replace(""""code_challenge_methods_supported":["S256"],""", "")).discover("https://mcp.test/v1/mcp")
        }
        // Metadata for a different issuer must not be used.
        assertFailsWith<McpAuthException> { oauth(AS_META.replace("https://auth.test/t1\"", "https://other.test\"")).discover("https://mcp.test/v1/mcp") }
    }

    @Test
    fun `insecure endpoints and mismatched metadata are refused`() = runTest {
        assertFailsWith<McpAuthException> { oauth(AS_META.replace("https://auth.test/token", "http://auth.test/token")).discover("https://mcp.test/v1/mcp") }
        assertTrue(McpOAuth.isSecure("http://127.0.0.1:8080/x"))
        assertTrue(!McpOAuth.isSecure("intent://x#Intent;end"))
        assertEquals(null, McpOAuth.withOfflineAccess(null, listOf("offline_access")))
        assertEquals("a offline_access", McpOAuth.withOfflineAccess("a", listOf("offline_access")))
    }

    @Test
    fun `servers without a 401 need no sign-in`() = runTest {
        val oauth = McpOAuth(HttpClient(MockEngine { respond("{}", headers = json) }))
        assertNull(oauth.discover("https://open.test/mcp"))
    }

    private companion object {
        const val AS_META = """{"issuer":"https://auth.test/t1","authorization_endpoint":"https://auth.test/authorize","token_endpoint":"https://auth.test/token","registration_endpoint":"https://auth.test/register","code_challenge_methods_supported":["S256"],"scopes_supported":["files:read","offline_access"],"authorization_response_iss_parameter_supported":true}"""
    }
}
