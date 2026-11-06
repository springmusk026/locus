package dev.locus.core.data.connectors

import dev.locus.core.agent.home.Connector
import dev.locus.core.datastore.SecretStore
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test connection and adding a service against a fake server (TestGraph's scripted HTTP engine): one
 * GET with the key, no redirects followed, messages without the key, and the key only in the vault.
 */
class ServiceConnectionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val g = TestGraph(scope)
    private val key = "mf-0123456789abcdef"

    @After
    fun cleanup() {
        scope.cancel()
    }

    private val miniflux = ConnectorTemplates.byId("miniflux")!!

    @Test
    fun `a working key passes with one harmless GET to the test path`() = runBlocking<Unit> {
        g.http = { req -> if (req.headers["X-Auth-Token"] == key) respond("""{"id":1,"username":"me"}""") else respond("", HttpStatusCode.Unauthorized) }
        val result = g.library.testService(miniflux, "rss.example.com", "", key)
        assertIs<ConnectionCheck.Passed>(result)
        val req = g.requests.single()
        assertEquals("GET", req.method.value)
        assertEquals("https://rss.example.com/v1/me", req.url.toString())
        assertFalse(key in result.detail)
    }

    @Test
    fun `a refused key, a missing page and a server error say so without the key`() = runBlocking<Unit> {
        for ((status, word) in listOf(HttpStatusCode.Unauthorized to "refused", HttpStatusCode.Forbidden to "permission", HttpStatusCode.NotFound to "404", HttpStatusCode.BadGateway to "error")) {
            g.http = { respond("bad key $key", status) }
            val result = g.library.testService(miniflux, "https://rss.example.com", "", key)
            assertIs<ConnectionCheck.Failed>(result)
            assertTrue(word in result.message, result.message)
            assertFalse(key in result.message)
        }
    }

    @Test
    fun `a redirect isn't followed, so the key never goes to the other address`() = runBlocking<Unit> {
        g.http = { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/v1/me")) }
        val result = g.library.testService(miniflux, "https://rss.example.com", "", key)
        assertIs<ConnectionCheck.Failed>(result)
        assertTrue("elsewhere.example" in result.message, result.message)
        assertEquals(listOf("rss.example.com"), g.requests.map { it.url.host })
    }

    @Test
    fun `a network failure that quotes the key is scrubbed`() = runBlocking<Unit> {
        g.http = { throw java.io.IOException("connection reset while sending X-Auth-Token: $key") }
        val result = g.library.testService(miniflux, "https://rss.example.com", "", key)
        assertIs<ConnectionCheck.Failed>(result)
        assertFalse(key in result.message, result.message)
    }

    @Test
    fun `a bad address fails before any request`() = runBlocking<Unit> {
        val result = g.library.testService(miniflux, "http://rss.example.com", "", key)
        assertIs<ConnectionCheck.Failed>(result)
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `adding keeps the key in the vault, tied to the address, and out of the connector and prompt`() = runBlocking<Unit> {
        g.library.addService(miniflux, "https://rss.example.com/", "", "  $key ")
        val c = g.home.connector("miniflux")!!
        assertEquals("https://rss.example.com/v1", c.baseUrl)
        assertEquals("miniflux", c.template)
        assertEquals(key, g.secrets.get(SecretStore.connectorSecret("miniflux-token")))
        assertEquals(setOf("https://rss.example.com"), g.keyOrigins.read("miniflux-token"))
        assertFalse(key in g.home.connectorsDir.resolve("miniflux.json").readText())
        assertFalse(key in g.home.snapshot())
        assertTrue(g.requests.isEmpty(), "adding doesn't call the server")
    }

    @Test
    fun `basic auth stores user and password together and sends them as Basic`() = runBlocking<Unit> {
        // No catalogue entry uses basic yet; a template with it behaves like the others.
        val basic = miniflux.copy(id = "rss-basic", auth = ConnectorTemplate.AuthSpec(type = "basic", label = "Password", where = "x", usernameLabel = "User"))
        g.http = { req -> respond(if (req.headers["Authorization"] == "Basic " + java.util.Base64.getEncoder().encodeToString("me:pw-123456".toByteArray())) "{}" else "", HttpStatusCode.OK) }
        assertIs<ConnectionCheck.Passed>(g.library.testService(basic, "https://rss.example.com", "me", "pw-123456"))
        g.library.addService(basic, "https://rss.example.com", "me", "pw-123456")
        assertEquals("basic", g.home.connector("rss-basic")!!.auth.type)
        assertEquals("me:pw-123456", g.secrets.get(SecretStore.connectorSecret("rss-basic-token")))
        assertFailsWith<IllegalArgumentException> { g.library.addService(basic, "https://rss.example.com", "m:e", "pw") }
    }

    @Test
    fun `FreshRSS signs in once and keeps the token, not the API password`() = runBlocking<Unit> {
        val freshrss = ConnectorTemplates.byId("freshrss")!!
        val token = "alice/0123456789abcdef0123456789abcdef01234567"
        g.http = { req ->
            when {
                req.url.encodedPath.endsWith("/accounts/ClientLogin") -> {
                    val form = String(req.body.toByteArray())
                    if ("Email=alice" in form && "Passwd=api-pass-1" in form) respond("SID=$token\nLSID=null\nAuth=$token\n") else respond("", HttpStatusCode.Unauthorized)
                }
                req.headers["Authorization"] == "GoogleLogin auth=$token" -> respond("""{"userName":"alice"}""")
                else -> respond("", HttpStatusCode.Unauthorized)
            }
        }
        assertIs<ConnectionCheck.Passed>(g.library.testService(freshrss, "https://rss.example.com/freshrss", "alice", "api-pass-1"))
        assertEquals("https://rss.example.com/freshrss/api/greader.php/reader/api/0/user-info", g.requests.last().url.toString())
        val refused = g.library.testService(freshrss, "https://rss.example.com/freshrss", "alice", "wrong")
        assertIs<ConnectionCheck.Failed>(refused)
        assertFalse("wrong" in refused.message)

        g.library.addService(freshrss, "https://rss.example.com/freshrss", "alice", "api-pass-1")
        assertEquals(token, g.secrets.get(SecretStore.connectorSecret("freshrss-token")))
        val c = g.home.connector("freshrss")!!
        assertEquals(Connector.Auth("header", "Authorization", "freshrss-token", prefix = "GoogleLogin auth="), c.auth)
        assertFalse("api-pass-1" in g.home.connectorsDir.resolve("freshrss.json").readText())
    }

    @Test
    fun `home assistant's test reaches the REST root with the token as bearer`() = runBlocking<Unit> {
        g.http = { req -> if (req.headers["Authorization"] == "Bearer llat-xyz-123") respond("""{"message":"API running."}""") else respond("", HttpStatusCode.Unauthorized) }
        val ok = g.library.testHomeAssistant("http://192.168.1.20:8123", "llat-xyz-123")
        assertIs<ConnectionCheck.Passed>(ok)
        assertEquals("http://192.168.1.20:8123/api/", g.requests.single().url.toString())
        val bad = g.library.testHomeAssistant("http://192.168.1.20:8123", "nope")
        assertIs<ConnectionCheck.Failed>(bad)
        assertNull(g.home.connector("home-assistant"), "testing adds nothing")
    }
}
