package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.homeTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ConnectTimeoutException
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A connector's credential is injected at call time and must never come back into the conversation:
 * not in the response, not in an error, and never to another host.
 */
class ConnectorSecretsTest {
    private val dir: File = Files.createTempDirectory("home").toFile()
    private val home = AgentHome(dir)
    private val secret = "sk-live-0123456789"
    private val resolver = SecretResolver { if (it == "weather-key") secret else null }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun connector(auth: Connector.Auth, vararg ops: Connector.Operation) = Connector(
        id = "weather", name = "Weather", description = "Forecasts", baseUrl = "https://api.weather.test/v2",
        auth = auth, operations = ops.toList(),
    ).also { home.addConnector(it) }

    private val forecast = Connector.Operation(
        "forecast", "Forecast", "GET", "/forecast/{city}",
        listOf(Connector.Param("city", location = "path", required = true), Connector.Param("units", location = "query")),
    )

    private fun args(op: String, vararg values: Pair<String, String>) = JsonObject(
        mapOf(
            "connector" to JsonPrimitive("weather"),
            "operation" to JsonPrimitive(op),
            "arguments" to JsonObject(values.associate { (k, v) -> k to JsonPrimitive(v) }),
        ),
    )

    private fun call(http: HttpClient) = homeTools(home, http, resolver).first { it.spec.name == "connector_call" }

    @Test
    fun `query and header auth put the secret where the connector says, and scrub it from the reply`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req -> seen += req; respond("""{"echo":"${req.url}"}""") })
        connector(Connector.Auth("query", name = "appid", secret = "weather-key"), forecast)

        val result = call(http).execute(args("forecast", "city" to "Lisbon", "units" to "metric"))
        assertEquals(secret, seen.single().url.parameters["appid"])
        assertEquals("metric", seen.single().url.parameters["units"])
        assertFalse(secret in result.content)
        assertTrue("[secret]" in result.content)

        connector(Connector.Auth("header", name = "X-Api-Key", secret = "weather-key"), forecast)
        call(http).execute(args("forecast", "city" to "Porto"))
        assertEquals(secret, seen.last().headers["X-Api-Key"])
        assertFalse(seen.last().url.parameters.contains("appid"))
    }

    @Test
    fun `a failed request doesn't echo a query secret through the error`() = runTest {
        // Ktor's timeout errors name the full URL, query string included.
        val http = HttpClient(MockEngine { req -> throw ConnectTimeoutException(req) })
        connector(Connector.Auth("query", name = "appid", secret = "weather-key"), forecast)

        val error = runCatching { call(http).execute(args("forecast", "city" to "Lisbon")) }.exceptionOrNull()
        val message = error?.message.orEmpty()
        assertTrue(error is ToolException, "expected a ToolException, got $error")
        assertFalse(secret in message, "the secret leaked: $message")
        assertTrue("timeout" in message.lowercase())
    }

    @Test
    fun `arguments can't move a request to another host`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req -> seen += req; respond("ok") })
        connector(Connector.Auth("bearer", secret = "weather-key"), forecast)
        val tool = call(http)

        for (city in listOf("@evil.test", "..%2F..", "//evil.test/x", "x?appid=1#", "\\@evil.test")) {
            runCatching { tool.execute(args("forecast", "city" to city)) }
        }
        assertTrue(seen.isNotEmpty())
        seen.forEach { assertEquals("api.weather.test", it.url.host, "request went to ${it.url}") }
        seen.forEach { assertTrue(it.url.encodedPath.startsWith("/v2/forecast/"), "path escaped: ${it.url}") }
    }

    @Test
    fun `a connector without its secret refuses instead of calling unauthenticated`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { req -> seen += req; respond("ok") })
        connector(Connector.Auth("bearer", secret = "missing-key"), forecast)
        val e = assertFailsWith<ToolException> { call(http).execute(args("forecast", "city" to "Lisbon")) }
        assertTrue("missing-key" in e.message.orEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `connector definitions are validated before they're saved`() {
        fun invalid(c: Connector) = assertFailsWith<IllegalArgumentException> { home.draftConnector(c) }
        val ok = Connector("todo", name = "Todo", description = "d", baseUrl = "https://api.todo.test", operations = listOf(forecast))
        home.draftConnector(ok)

        invalid(ok.copy(id = "Bad Id"))
        invalid(ok.copy(id = "../escape"))
        invalid(ok.copy(id = "a".repeat(49)))
        invalid(ok.copy(baseUrl = "file:///data/data"))
        invalid(ok.copy(baseUrl = "javascript:alert(1)"))
        invalid(ok.copy(kind = "shell"))
        invalid(ok.copy(operations = emptyList()))
        invalid(ok.copy(auth = Connector.Auth("bearer", secret = "")))
        invalid(ok.copy(auth = Connector.Auth("cookie", secret = "x")))
        // OAuth is only for MCP servers the user signs in to.
        invalid(ok.copy(auth = Connector.Auth("oauth", secret = "x")))
        invalid(ok.copy(operations = listOf(forecast.copy(method = "TRACE"))))
        invalid(ok.copy(operations = listOf(forecast.copy(sideEffect = "maybe"))))
        invalid(ok.copy(operations = listOf(forecast.copy(path = "https://evil.test/steal"))))

        home.draftConnector(ok.copy(id = "mcp-server", kind = "mcp", operations = emptyList(), auth = Connector.Auth("oauth", secret = "mcp-server-oauth")))
        assertEquals(listOf("mcp-server", "todo"), home.connectorDrafts().map { it.id })
    }
}
