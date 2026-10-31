package dev.locus.core.data.mcp

import dev.locus.core.agent.ToolException
import dev.locus.core.datastore.SecretStore
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A local MCP server added in Library, reached through the same two MCP tools as remote ones. */
class LocalMcpToolsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val g = TestGraph(scope)
    private val fake = FakeStdioServer()

    @After
    fun cleanup() {
        scope.cancel()
    }

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (k, v) -> k to if (v is JsonObject) v else JsonPrimitive(v.toString()) },
    )

    private suspend fun add() = g.library.addLocalMcpServer(
        "Files", "npx -y @modelcontextprotocol/server-filesystem", listOf("/workspace"), listOf("API_KEY" to "  sk-local-123456  "),
    )

    @Test
    fun `adding one stores the env value in the vault and only its name in the connector`() = runBlocking<Unit> {
        add()
        val entry = g.library.library.first().connectors.single()
        val local = entry.connector.local!!
        assertEquals("files", entry.connector.id)
        assertEquals("files-api-key", local.env.single().secret)
        assertFalse("sk-local" in g.home.connectorsDir.resolve("files.json").readText())
        assertEquals("sk-local-123456", g.secrets.get(SecretStore.connectorSecret("files-api-key")))
        assertFalse(entry.draft)
        assertTrue(entry.hasSecret)
        assertEquals(LocalMcpHealth.Stopped(), entry.health)
    }

    @Test
    fun `the fixed MCP tools reach it, and its tools keep their read-only marks`() = runBlocking<Unit> {
        g.stdioLauncher = fake
        add()
        // No per-server tools: the declared tool list doesn't change when a server is added.
        assertEquals(listOf("mcp_call", "mcp_list_tools"), g.tools.registry.specs.map { it.name }.filter { it.startsWith("mcp") })

        val list = g.tools.registry["mcp_list_tools"]!!.execute(args("connector" to "files"))
        assertTrue("echo [read-only]" in list.content, list.content)

        val call = g.tools.registry["mcp_call"]!!
        // Read-only runs only allow observations: echo is one, env is not.
        assertTrue(call.isObservation(args("connector" to "files", "tool" to "echo")))
        assertFalse(call.isObservation(args("connector" to "files", "tool" to "env")))
        assertEquals(null, call.mustConfirm(args("connector" to "files", "tool" to "echo")))
        assertTrue(call.mustConfirm(args("connector" to "files", "tool" to "env")) != null)

        val reply = call.execute(args("connector" to "files", "tool" to "echo", "arguments" to args("text" to "hello")))
        assertEquals("hello", reply.content)
        assertEquals(1, fake.launches.size)
        assertIs<LocalMcpHealth.Running>(g.library.library.first().connectors.single().health)

        // Removing it stops the process.
        g.library.removeConnector("files")
        assertFalse(fake.processes.single().isAlive)
    }

    @Test
    fun `without Linux installed, listing its tools says so`() = runBlocking<Unit> {
        add()
        val e = assertFailsWith<ToolException> { g.tools.registry["mcp_list_tools"]!!.execute(args("connector" to "files")) }
        assertTrue("Linux" in e.message.orEmpty(), e.message)
        assertIs<LocalMcpHealth.Failed>(g.library.library.first().connectors.single().health)
    }

    @Test
    fun `a new key restarts a running server so it picks the value up`() = runBlocking<Unit> {
        g.stdioLauncher = fake
        add()
        g.library.startLocalMcp("files")
        assertEquals(1, fake.launches.size)
        g.library.setConnectorSecret("files-api-key", "sk-local-rotated")
        assertFalse(fake.processes.single().isAlive)
        g.tools.registry["mcp_list_tools"]!!.execute(args("connector" to "files"))
        assertEquals("sk-local-rotated", fake.launches.last().env["API_KEY"])
    }
}
