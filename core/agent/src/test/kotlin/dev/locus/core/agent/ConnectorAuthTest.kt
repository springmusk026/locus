package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.ConnectorKeys
import dev.locus.core.agent.home.KeyOrigins
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.homeTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Basic auth and header prefixes for HTTP connectors (docs/design/integrations.md): the credential goes
 * where the template says, in exactly the form the service expects, and no form of it comes back to the
 * model: not in a reply that echoes it, not in an error, not in the prompt.
 */
class ConnectorAuthTest {
    private val dir: File = Files.createTempDirectory("home").toFile()
    private val home = AgentHome(dir)
    private val password = "correct-horse-battery"
    private val basic = "alice:$password"
    private val token = "tok_9f8e7d6c5b4a"
    private val vault = mapOf("rss-basic" to basic, "paper-token" to token)
    private val resolver = SecretResolver { vault[it] }
    private val origins = KeyOrigins { setOf("https://svc.test") }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val me = Connector.Operation("me", "Who am I", "GET", "/me")
    private val post = Connector.Operation("post", "Post", "POST", "/statuses", listOf(Connector.Param("status", location = "body", required = true)), sideEffect = "outward")

    private fun connector(auth: Connector.Auth) = Connector(
        id = "svc", name = "Service", description = "A self-hosted service", baseUrl = "https://svc.test/api",
        auth = auth, operations = listOf(me, post),
    ).also { home.addConnector(it) }

    private fun args(op: String, vararg values: Pair<String, String>) = JsonObject(
        mapOf(
            "connector" to JsonPrimitive("svc"),
            "operation" to JsonPrimitive(op),
            "arguments" to JsonObject(values.associate { (k, v) -> k to JsonPrimitive(v) }),
        ),
    )

    private fun call(http: HttpClient) = homeTools(home, http, resolver, origins = origins).first { it.spec.name == "connector_call" }

    /** A server that echoes the Authorization header it got, as a misbehaving one might. */
    private fun echo(seen: MutableList<HttpRequestData>) = HttpClient(MockEngine { req -> seen += req; respond("""{"you_sent":"${req.headers["Authorization"]}"}""") })

    @Test
    fun `basic auth sends base64 of user and password, and scrubs every form of it`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        connector(Connector.Auth("basic", secret = "rss-basic"))
        val result = call(echo(seen)).execute(args("me"))
        val b64 = Base64.getEncoder().encodeToString(basic.toByteArray())
        assertEquals("Basic $b64", seen.single().headers["Authorization"])
        assertFalse(b64 in result.content, result.content)
        assertFalse(password in result.content)
        assertTrue("[secret]" in result.content)
    }

    @Test
    fun `a header prefix goes before the bare key`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        connector(Connector.Auth("header", name = "Authorization", secret = "paper-token", prefix = "Token "))
        val result = call(echo(seen)).execute(args("me"))
        assertEquals("Token $token", seen.single().headers["Authorization"])
        assertFalse(token in result.content)

        connector(Connector.Auth("header", name = "X-Auth-Token", secret = "paper-token"))
        call(echo(seen)).execute(args("me"))
        assertEquals(token, seen.last().headers["X-Auth-Token"])
        assertNull(seen.last().headers["Authorization"])

        connector(Connector.Auth("bearer", secret = "paper-token"))
        call(echo(seen)).execute(args("me"))
        assertEquals("Bearer $token", seen.last().headers["Authorization"])
    }

    @Test
    fun `an error never carries the basic credentials`() = runTest {
        val http = HttpClient(MockEngine { req -> throw java.io.IOException("timeout to ${req.url} with ${req.headers["Authorization"]} and $password") })
        connector(Connector.Auth("basic", secret = "rss-basic"))
        val e = assertFailsWith<ToolException> { call(http).execute(args("me")) }
        val message = e.message.orEmpty()
        assertFalse(password in message, message)
        assertFalse(Base64.getEncoder().encodeToString(basic.toByteArray()) in message, message)
    }

    @Test
    fun `outward operations always confirm and don't count as observations`() = runTest {
        connector(Connector.Auth("bearer", secret = "paper-token"))
        val tool = call(echo(mutableListOf()))
        assertNull(tool.mustConfirm(args("me")))
        assertTrue(tool.isObservation(args("me")))
        val reason = tool.mustConfirm(args("post", "status" to "hello"))
        assertTrue(reason != null && "publishes" in reason, reason)
        assertFalse(tool.isObservation(args("post", "status" to "hello")))
    }

    @Test
    fun `the prompt names connectors and operations, never a key`() {
        connector(Connector.Auth("basic", secret = "rss-basic"))
        val prompt = home.snapshot()
        assertTrue("svc" in prompt)
        vault.values.forEach { assertFalse(it in prompt) }
        assertFalse(password in prompt)
    }

    @Test
    fun `auth shapes are validated`() {
        val ok = Connector("svc", name = "S", description = "d", baseUrl = "https://svc.test", operations = listOf(me))
        fun invalid(auth: Connector.Auth) = assertFailsWith<IllegalArgumentException> { ok.copy(auth = auth).validate() }
        ok.copy(auth = Connector.Auth("basic", secret = "k")).validate()
        ok.copy(auth = Connector.Auth("header", name = "Authorization", secret = "k", prefix = "GoogleLogin auth=")).validate()
        invalid(Connector.Auth("header", name = "", secret = "k"))
        invalid(Connector.Auth("header", name = "X Api Key", secret = "k"))
        invalid(Connector.Auth("header", name = "Host", secret = "k"))
        invalid(Connector.Auth("header", name = "Authorization", secret = "k", prefix = "Token\r\nX-Evil: 1"))
        invalid(Connector.Auth("bearer", secret = "k", prefix = "Token "))
        invalid(Connector.Auth("header", name = "X", secret = "k", prefix = "x".repeat(41)))
        assertFailsWith<IllegalArgumentException> { ok.copy(operations = listOf(me.copy(sideEffect = "public"))).validate() }
        ok.copy(operations = listOf(post)).validate()
    }

    @Test
    fun `basic values refuse a colon in the user name`() {
        assertEquals("alice:pa:ss", ConnectorKeys.basicValue(" alice ", "pa:ss"))
        assertFailsWith<IllegalArgumentException> { ConnectorKeys.basicValue("al:ice", "x") }
        assertFailsWith<IllegalArgumentException> { ConnectorKeys.basicValue("", "x") }
        assertFailsWith<IllegalArgumentException> { ConnectorKeys.basicValue("alice", "") }
    }

    @Test
    fun `secret forms cover what a basic credential looks like on the wire`() {
        val forms = ConnectorKeys.secretForms(Connector.Auth("basic", secret = "n"), basic)
        assertTrue(basic in forms && password in forms && Base64.getEncoder().encodeToString(basic.toByteArray()) in forms)
        assertEquals(listOf(token), ConnectorKeys.secretForms(Connector.Auth("bearer", secret = "n"), token))
    }

    @Test
    fun `home assistant rules are validated`() {
        val ha = Connector("ha", kind = "mcp", name = "HA", description = "d", baseUrl = "https://ha.test/api/mcp/assist", auth = Connector.Auth("bearer", secret = "ha-token"))
        ha.copy(homeAssistant = Connector.HomeAssistantRules(listOf("vacuum"), 15.0, 25.0)).validate()
        assertFailsWith<IllegalArgumentException> { ha.copy(homeAssistant = Connector.HomeAssistantRules(climateMin = 25.0, climateMax = 15.0)).validate() }
        assertFailsWith<IllegalArgumentException> { ha.copy(homeAssistant = Connector.HomeAssistantRules(listOf("a\nb"))).validate() }
    }
}
