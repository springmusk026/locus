package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.KeyOrigins
import dev.locus.core.agent.home.ScriptEngine
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.homeTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What connector_call does with the network: how much of a reply it keeps in memory, where a redirect may
 * lead (a key never leaves the address it was saved for), and what a connector's script gets to see.
 */
class ConnectorTransportTest {
    private val dir: File = Files.createTempDirectory("home").toFile()
    private val home = AgentHome(dir)
    private val secret = "sk-live-0123456789"
    private val resolver = SecretResolver { if (it == "weather-key") secret else null }
    private val origins = KeyOrigins { if (it == "weather-key") setOf("https://api.weather.test") else emptySet() }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val forecast = Connector.Operation("forecast", "Forecast", "GET", "/forecast/{city}", listOf(Connector.Param("city", location = "path", required = true)))
    private val create = Connector.Operation("create", "Create", "POST", "/items", listOf(Connector.Param("name", location = "body")), sideEffect = "write")

    private fun connector(auth: Connector.Auth, script: String = "") = Connector(
        id = "weather", name = "Weather", description = "Forecasts", baseUrl = "https://api.weather.test/v2",
        auth = auth, operations = listOf(forecast, create), script = script,
    ).also { home.addConnector(it) }

    private fun args(op: String, vararg values: Pair<String, String>) = JsonObject(
        mapOf(
            "connector" to JsonPrimitive("weather"),
            "operation" to JsonPrimitive(op),
            "arguments" to JsonObject(values.associate { (k, v) -> k to JsonPrimitive(v) }),
        ),
    )

    private fun call(http: HttpClient, scripts: ScriptEngine? = null) =
        homeTools(home, http, resolver, scripts, origins = origins).first { it.spec.name == "connector_call" }

    private fun MockRequestHandleScope.redirect(to: String, status: HttpStatusCode = HttpStatusCode.Found) = respond("", status, headersOf(HttpHeaders.Location, to))

    // ---- A big reply is read up to a cap, not into memory ----

    @Test
    fun `a huge reply is read only up to the cap and the cut is reported`() = runTest {
        val produced = AtomicLong()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        // 40 MB, produced only as fast as the reader takes it: how much was produced is how much was read.
        val http = HttpClient(
            MockEngine {
                val source = scope.writer {
                    val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
                    repeat(640) {
                        channel.writeFully(chunk)
                        channel.flush()
                        produced.addAndGet(chunk.size.toLong())
                    }
                }
                respond(source.channel, HttpStatusCode.OK)
            },
        )
        connector(Connector.Auth("none"))
        try {
            val result = call(http).execute(args("forecast", "city" to "Lisbon"))
            assertTrue("stopped reading at 2 MB" in result.content, result.content.take(200))
            assertTrue("…[truncated]" in result.content)
            assertTrue(result.content.length < 31_000)
            assertTrue(produced.get() < 10_000_000, "read ${produced.get()} bytes of a 40 MB reply")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a reply within the cap isn't reported as cut`() = runTest {
        val http = HttpClient(MockEngine { respond("sunny") })
        connector(Connector.Auth("none"))
        val result = call(http).execute(args("forecast", "city" to "Lisbon"))
        assertTrue(result.content.startsWith("HTTP 200\n"), result.content)
        assertTrue("sunny" in result.content)
    }

    // ---- A redirect never takes the key to another address ----

    @Test
    fun `a redirect to another host doesn't carry a header key`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req -> seen += req; if (req.url.host == "api.weather.test") redirect("https://evil.test/steal") else respond("pwned") })
        connector(Connector.Auth("header", name = "X-Api-Key", secret = "weather-key"))

        val e = assertFailsWith<ToolException> { call(http).execute(args("forecast", "city" to "Lisbon")) }
        assertTrue("Blocked" in e.message.orEmpty(), e.message)
        assertEquals(listOf("api.weather.test"), seen.map { it.url.host })
        assertTrue(seen.none { it.url.host == "evil.test" }, "followed the redirect to ${seen.last().url}")
        assertFalse(secret in e.message.orEmpty())
    }

    @Test
    fun `a redirect to another host doesn't carry a query key`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req -> seen += req; if (req.url.host == "api.weather.test") redirect("https://evil.test/steal?x=1") else respond("pwned") })
        connector(Connector.Auth("query", name = "appid", secret = "weather-key"))

        assertFailsWith<ToolException> { call(http).execute(args("forecast", "city" to "Lisbon")) }
        assertTrue(seen.none { it.url.host == "evil.test" })
    }

    @Test
    fun `a redirect from https to http is refused`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req -> seen += req; redirect("http://api.weather.test/v2/forecast/Lisbon") })
        connector(Connector.Auth("header", name = "X-Api-Key", secret = "weather-key"))

        val e = assertFailsWith<ToolException> { call(http).execute(args("forecast", "city" to "Lisbon")) }
        assertTrue("insecure" in e.message.orEmpty(), e.message)
        assertEquals(1, seen.size)
    }

    @Test
    fun `a redirect to another port of the same host is refused, since the key wasn't saved for it`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req -> seen += req; redirect("https://api.weather.test:8443/v2/forecast/Lisbon") })
        connector(Connector.Auth("header", name = "X-Api-Key", secret = "weather-key"))

        val e = assertFailsWith<ToolException> { call(http).execute(args("forecast", "city" to "Lisbon")) }
        assertTrue("wasn't saved for" in e.message.orEmpty(), e.message)
        assertEquals(1, seen.size)
    }

    @Test
    fun `a redirect within the same address is followed and the key is added again`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        // The new address already has a parameter named like the key's: the real key replaces it.
        val http = HttpClient(
            MockEngine { req ->
                seen += req
                if (req.url.encodedPath.endsWith("/Lisbon")) redirect("/v2/moved?appid=stale") else respond("moved ok")
            },
        )
        connector(Connector.Auth("query", name = "appid", secret = "weather-key"))
        val result = call(http).execute(args("forecast", "city" to "Lisbon"))
        assertTrue("moved ok" in result.content)
        assertEquals(2, seen.size)
        assertEquals(listOf(secret), seen.last().url.parameters.getAll("appid"))

        connector(Connector.Auth("header", name = "X-Api-Key", secret = "weather-key"))
        call(http).execute(args("forecast", "city" to "Lisbon"))
        assertEquals(secret, seen.last().headers["X-Api-Key"])
        assertEquals("/v2/moved", seen.last().url.encodedPath)
    }

    @Test
    fun `a redirect turns a post into a get without its body, except 307`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        var status = HttpStatusCode.SeeOther
        val http = HttpClient(MockEngine { req -> seen += req; if (req.url.encodedPath.endsWith("/items")) redirect("/v2/done", status) else respond("done") })
        connector(Connector.Auth("header", name = "X-Api-Key", secret = "weather-key"))

        call(http).execute(args("create", "name" to "milk"))
        assertEquals(listOf("POST", "GET"), seen.map { it.method.value })

        seen.clear()
        status = HttpStatusCode.TemporaryRedirect
        call(http).execute(args("create", "name" to "milk"))
        assertEquals(listOf("POST", "POST"), seen.map { it.method.value })
    }

    @Test
    fun `a redirect loop ends`() = runTest {
        val http = HttpClient(MockEngine { redirect("/v2/again") })
        connector(Connector.Auth("none"))
        val e = assertFailsWith<ToolException> { call(http).execute(args("forecast", "city" to "Lisbon")) }
        assertTrue("Too many redirects" in e.message.orEmpty())
    }

    // ---- A connector's script never sees the key ----

    @Test
    fun `an echoed key is taken out before the response script sees it`() = runTest {
        val http = HttpClient(MockEngine { req -> respond("""{"you sent":"${req.headers["X-Api-Key"]}"}""") })
        val evaluated = mutableListOf<String>()
        // Stand-in for the sandbox: the hex-encoding script of the report would turn what it got into hex.
        val engine = ScriptEngine { code ->
            evaluated += code
            if ("JSON.stringify" in code) "null" else code.substringAfter("response(").toByteArray().joinToString("") { "%02x".format(it) }
        }
        connector(Connector.Auth("header", name = "X-Api-Key", secret = "weather-key"), script = "function response(op, s, t){ return hex(t) }")

        val result = call(http, engine).execute(args("forecast", "city" to "Lisbon"))
        val hook = evaluated.single { "JSON.stringify" !in it }
        assertFalse(secret in hook, "the script was given the key")
        assertTrue("[secret]" in hook)
        assertFalse(secret in result.content)
        val hex = secret.toByteArray().joinToString("") { "%02x".format(it) }
        assertFalse(hex in result.content, "the key reached the model, hex-encoded")
    }
}
