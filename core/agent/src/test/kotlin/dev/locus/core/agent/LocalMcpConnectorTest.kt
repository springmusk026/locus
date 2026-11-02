package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.homeTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Local MCP servers (stdio, run in Linux) as connectors: their shape, validation and drafting. */
class LocalMcpConnectorTest {
    private val dir: File = Files.createTempDirectory("home").toFile()
    private val home = AgentHome(dir)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val files = Connector(
        id = "files", kind = "mcp", name = "Files", description = "Workspace files",
        local = Connector.Local(
            command = "npx -y @modelcontextprotocol/server-filesystem /workspace",
            env = listOf(Connector.EnvVar("GITHUB_TOKEN", "github-token")),
        ),
    )

    @Test
    fun `a local server is stored with secret names only and read back unchanged`() {
        home.addConnector(files)
        val text = File(home.connectorsDir, "files.json").readText()
        assertTrue("github-token" in text)
        assertEquals(files, home.connector("files"))
        assertTrue(home.connector("files")!!.isLocalMcp)
        assertEquals(listOf("github-token"), files.secretNames)
        assertEquals("Linux on this phone", files.host)
    }

    @Test
    fun `existing HTTP and MCP connector files still load (no local field)`() {
        File(home.connectorsDir, "old.json").writeText(
            """{"id":"old","kind":"mcp","name":"Old","description":"d","baseUrl":"https://mcp.example.com/mcp","operations":[]}""",
        )
        val old = home.connector("old")!!
        assertNull(old.local)
        assertFalse(old.isLocalMcp)
    }

    @Test
    fun `local servers are validated`() {
        fun invalid(c: Connector) = assertFailsWith<IllegalArgumentException> { home.draftConnector(c) }
        invalid(files.copy(kind = "http"))
        invalid(files.copy(baseUrl = "https://example.com/mcp"))
        invalid(files.copy(auth = Connector.Auth("bearer", secret = "x")))
        invalid(files.copy(local = files.local!!.copy(command = " ")))
        invalid(files.copy(local = files.local!!.copy(command = "npx a\nrm -rf /")))
        invalid(files.copy(local = files.local!!.copy(env = listOf(Connector.EnvVar("PATH", "x")))))
        invalid(files.copy(local = files.local!!.copy(env = listOf(Connector.EnvVar("LD_PRELOAD", "x")))))
        invalid(files.copy(local = files.local!!.copy(env = listOf(Connector.EnvVar("PROOT_LOADER", "x")))))
        invalid(files.copy(local = files.local!!.copy(env = listOf(Connector.EnvVar("1BAD", "x")))))
        invalid(files.copy(local = files.local!!.copy(env = listOf(Connector.EnvVar("KEY", "")))))
        invalid(files.copy(local = files.local!!.copy(env = listOf(Connector.EnvVar("KEY", "a"), Connector.EnvVar("KEY", "b")))))
        home.draftConnector(files)
        assertEquals(listOf("files"), home.connectorDrafts().map { it.id })
    }

    @Test
    fun `the agent can only draft a local server, which names the secrets to add`() = runTest {
        val tools = homeTools(home, HttpClient(MockEngine { respond("") }), SecretResolver { null }).associateBy { it.spec.name }
        val json = AgentHome.json.encodeToString(Connector.serializer(), files)
        val result = tools["connector_create"]!!.execute(JsonObject(mapOf("connector_json" to JsonPrimitive(json))))
        assertTrue("github-token" in result.content, result.content)
        assertTrue(home.connectors().isEmpty())
        assertEquals("files", home.connectorDrafts().single().id)
        // The prompt catalogue lists only approved connectors.
        assertFalse("files" in home.snapshot())
        home.approveConnector("files")
        assertTrue("files (local MCP server, runs in Linux)" in home.snapshot())
    }
}
