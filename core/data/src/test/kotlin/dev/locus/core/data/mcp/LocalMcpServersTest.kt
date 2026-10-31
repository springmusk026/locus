package dev.locus.core.data.mcp

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.linux.LinuxNotInstalledException
import dev.locus.core.linux.OutputRedactor
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Local MCP servers: launch building, JSON-RPC over stdio against a fake server, and the lifecycle
 * (start on demand, reuse, idle stop, crash, one retry, Linux missing). Real threads and pipes, a
 * fake clock for idleness.
 */
class LocalMcpServersTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val secret = "ghp_live_0123456789abcdef"
    private val vault = mutableMapOf("github-token" to secret)
    private val secrets = SecretResolver { vault[it] }
    private var now = 1_000L
    private val redactor = OutputRedactor()

    @After
    fun cleanup() {
        scope.cancel()
    }

    private val connector = Connector(
        id = "files", kind = "mcp", name = "Files", description = "d",
        local = Connector.Local(
            command = "npx -y @modelcontextprotocol/server-filesystem",
            args = listOf("/workspace", "it's here"),
            env = listOf(Connector.EnvVar("GITHUB_TOKEN", "github-token")),
        ),
    )

    private fun servers(launcher: StdioLauncher, idleTimeoutMs: Long = 60_000) =
        LocalMcpServers(launcher, secrets, scope, redactor, idleTimeoutMs = idleTimeoutMs, idleCheckMs = 20, clock = { now })

    private suspend fun eventually(what: String, check: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!check()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    // ---- Launch building -------------------------------------------------------------------------

    @Test
    fun `the command line quotes each arg as one shell word`() {
        assertEquals(
            "npx -y @modelcontextprotocol/server-filesystem '/workspace' 'it'\\''s here'",
            LocalLaunch.commandLine(connector.local!!),
        )
        assertEquals("'a b; rm -rf /'", LocalLaunch.shellQuote("a b; rm -rf /"))
    }

    @Test
    fun `secrets are injected as env from the vault, and never printed`() = runBlocking<Unit> {
        val launch = LocalLaunch.build(connector, secrets)
        assertEquals(mapOf("GITHUB_TOKEN" to secret), launch.env)
        assertFalse(secret in launch.toString(), launch.toString())
        assertFalse(secret in launch.command)
        assertTrue("GITHUB_TOKEN" in launch.toString())
    }

    @Test
    fun `a missing secret is refused by name before anything starts`() = runBlocking<Unit> {
        vault.clear()
        val fake = FakeStdioServer()
        val e = assertFailsWith<ToolException> { servers(fake).use(connector) { it.listTools() } }
        assertTrue("“github-token”" in e.message.orEmpty(), e.message)
        assertTrue(fake.launches.isEmpty())
    }

    // ---- JSON-RPC over stdio ---------------------------------------------------------------------

    @Test
    fun `talks newline-delimited JSON-RPC - handshake, then list and call tools`() = runBlocking<Unit> {
        val fake = FakeStdioServer()
        val local = servers(fake)
        val tools = local.use(connector, idempotent = true) { it.listTools().tools }
        assertEquals(listOf("echo", "env", "crash"), tools.map { it.name })
        assertEquals(true, tools.first().annotations?.readOnlyHint)
        val reply = local.use(connector) { it.callTool("echo", mapOf("text" to JsonPrimitive("hi\nthere"))) }
        assertEquals("hi\nthere", (reply.content.single() as TextContent).text)

        // One JSON-RPC message per line, the handshake first; a newline inside a value stays escaped.
        val methods = fake.received.map { Json.parseToJsonElement(it).jsonObject["method"]?.jsonPrimitive?.content }
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), methods)
        fake.received.forEach { line -> assertEquals("2.0", Json.parseToJsonElement(line).jsonObject["jsonrpc"]?.jsonPrimitive?.content) }
        assertTrue(fake.received.any { "hi\\nthere" in it })
        // Stderr chatter is logging, not an error.
        assertIs<LocalMcpHealth.Running>(local.health("files"))
    }

    @Test
    fun `the server gets its secret through the environment`() = runBlocking<Unit> {
        val fake = FakeStdioServer()
        val reply = servers(fake).use(connector) { it.callTool("env", mapOf("name" to JsonPrimitive("GITHUB_TOKEN"))) }
        assertEquals(secret, (reply.content.single() as TextContent).text)
    }

    // ---- Lifecycle -------------------------------------------------------------------------------

    @Test
    fun `starts on first use, is reused, stops when idle and starts again when needed`() = runBlocking<Unit> {
        val fake = FakeStdioServer()
        val local = servers(fake, idleTimeoutMs = 60_000)
        assertIs<LocalMcpHealth.Stopped>(local.health("files"))
        assertTrue(fake.launches.isEmpty())

        local.use(connector, idempotent = true) { it.listTools() }
        local.use(connector) { it.callTool("echo", mapOf("text" to JsonPrimitive("x"))) }
        assertEquals(1, fake.launches.size)
        assertIs<LocalMcpHealth.Running>(local.health("files"))
        // While it runs, its secret is scrubbed from Linux command output.
        assertEquals("[secret]", redactor.scrub(secret))

        now += 61_000
        eventually("idle stop") { local.health("files") is LocalMcpHealth.Stopped }
        assertTrue("idle" in (local.health("files") as LocalMcpHealth.Stopped).reason.orEmpty())
        val first = fake.processes.single()
        assertFalse(first.isAlive)
        assertTrue(first.stoppedByClient)
        assertEquals(secret, redactor.scrub(secret))

        local.use(connector, idempotent = true) { it.listTools() }
        assertEquals(2, fake.launches.size)
    }

    @Test
    fun `a server in use is not stopped for idleness`() = runBlocking<Unit> {
        val fake = FakeStdioServer()
        val local = servers(fake, idleTimeoutMs = 1_000)
        local.use(connector) {
            now += 10_000
            delay(200) // several idle checks pass while the call is in flight
            it.callTool("echo", mapOf("text" to JsonPrimitive("still here")))
        }
        assertIs<LocalMcpHealth.Running>(local.health("files"))
        assertTrue(fake.processes.single().isAlive)
    }

    @Test
    fun `a crash during a call is a clear error with the stderr tail, not repeated`() = runBlocking<Unit> {
        val fake = FakeStdioServer()
        val local = servers(fake)
        local.use(connector, idempotent = true) { it.listTools() }
        val e = assertFailsWith<ToolException> { local.use(connector) { it.callTool("crash", emptyMap()) } }
        val message = e.message.orEmpty()
        assertTrue("stopped unexpectedly" in message && "boom: fatal error" in message, message)
        assertTrue("wasn't repeated" in message, message)
        // The server printed its environment to stderr: the secret is scrubbed everywhere.
        assertFalse(secret in message, message)
        val health = local.health("files")
        assertIs<LocalMcpHealth.Failed>(health)
        assertTrue("3" in health.reason && "boom" in health.stderrTail, health.toString())
        assertFalse(secret in health.stderrTail)
        assertEquals(1, fake.launches.size)

        // The next use starts a fresh process.
        local.use(connector, idempotent = true) { it.listTools() }
        assertEquals(2, fake.launches.size)
        assertIs<LocalMcpHealth.Running>(local.health("files"))
    }

    @Test
    fun `listing tools is retried once on a fresh process after a crash`() = runBlocking<Unit> {
        val fake = FakeStdioServer(crashOnList = 1)
        val local = servers(fake)
        val tools = local.use(connector, idempotent = true) { it.listTools().tools }
        assertEquals(3, tools.size)
        assertEquals(2, fake.launches.size)

        fake.crashOnList = 4 // lists 3 and 4 crash too: one retry only, then the error
        assertFailsWith<ToolException> { local.use(connector, idempotent = true) { it.listTools() } }
        assertEquals(3, fake.launches.size)
    }

    @Test
    fun `a failed start is retried once`() = runBlocking<Unit> {
        val fake = FakeStdioServer(failStarts = 1)
        val local = servers(fake)
        local.use(connector, idempotent = true) { it.listTools() }
        assertEquals(2, fake.launches.size)
        assertIs<LocalMcpHealth.Running>(local.health("files"))
    }

    @Test
    fun `a server that never starts fails with its stderr, after exactly two tries`() = runBlocking<Unit> {
        val fake = FakeStdioServer(failStarts = 99)
        val local = servers(fake)
        val e = assertFailsWith<ToolException> { local.use(connector, idempotent = true) { it.listTools() } }
        assertTrue("tried twice" in e.message.orEmpty() && "npm ERR! network" in e.message.orEmpty(), e.message)
        assertEquals(2, fake.launches.size)
        val health = local.health("files")
        assertIs<LocalMcpHealth.Failed>(health)
        assertTrue("exited with code 1" in health.reason, health.reason)
        assertTrue("npm ERR!" in health.stderrTail)
    }

    @Test
    fun `a crash between calls shows as Failed without waiting for the next call`() = runBlocking<Unit> {
        val fake = FakeStdioServer()
        val local = servers(fake)
        local.use(connector, idempotent = true) { it.listTools() }
        // Simulate the process dying on its own (e.g. the OOM killer).
        fake.processes.single().let { p -> runCatching { p.stdin.close() } }
        eventually("crash noticed") { local.health("files") is LocalMcpHealth.Failed }
    }

    @Test
    fun `stop ends the process and says so`() = runBlocking<Unit> {
        val fake = FakeStdioServer()
        val local = servers(fake)
        local.start(connector)
        local.stop("files", "Stopped by you")
        assertEquals(LocalMcpHealth.Stopped("Stopped by you"), local.health("files"))
        assertFalse(fake.processes.single().isAlive)
    }

    @Test
    fun `without Linux it refuses clearly, once, and shows why`() = runBlocking<Unit> {
        var tries = 0
        val local = servers({ tries++; throw LinuxNotInstalledException() })
        val e = assertFailsWith<ToolException> { local.use(connector, idempotent = true) { it.listTools() } }
        assertTrue("Linux" in e.message.orEmpty() && "install" in e.message.orEmpty(), e.message)
        assertEquals(1, tries)
        assertIs<LocalMcpHealth.Failed>(local.health("files"))
    }
}
