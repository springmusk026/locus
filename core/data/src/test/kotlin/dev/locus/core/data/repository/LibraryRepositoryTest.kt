package dev.locus.core.data.repository

import dev.locus.core.agent.home.Connector
import dev.locus.core.data.di.DataModule
import dev.locus.core.datastore.SecretStore
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Connectors in the Library: drafts, secrets stored where connectors can use them, and removal. */
class LibraryRepositoryTest {
    private fun draft(id: String, auth: Connector.Auth) = Connector(
        id = id, name = id, description = "d", baseUrl = "https://$id.example", auth = auth,
        operations = listOf(Connector.Operation("list", "List", path = "/items")),
    )

    @Test
    fun `an MCP server added with a token is active at once, its token stored for connectors only`() = runTest {
        val g = TestGraph(backgroundScope)
        g.library.addMcpServer(" My Server! ", " https://mcp.example.com/mcp ", "  tok-123  ")

        val entry = g.library.library.first().connectors.single()
        assertEquals("my-server", entry.connector.id)
        assertEquals("My Server!", entry.connector.name)
        assertEquals("https://mcp.example.com/mcp", entry.connector.baseUrl)
        assertEquals(Connector.Auth("bearer", secret = "my-server-token"), entry.connector.auth)
        assertFalse(entry.draft)
        assertTrue(entry.hasSecret)
        assertEquals("tok-123", DataModule.secretResolver(g.secrets).resolve("my-server-token"))
        // Nothing went over the network: a token means no sign-in check.
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `a drafted connector waits for review and its secret`() = runTest {
        val g = TestGraph(backgroundScope)
        g.home.draftConnector(draft("todo", Connector.Auth("bearer", secret = "todo-token")))
        var entry = g.library.library.first().connectors.single()
        assertTrue(entry.draft)
        assertFalse(entry.hasSecret)

        g.library.approveConnector("todo")
        g.library.setConnectorSecret("todo-token", "  sk-1  ")
        entry = g.library.library.first { lib -> lib.connectors.single().hasSecret }.connectors.single()
        assertFalse(entry.draft)
        assertEquals("sk-1", g.secrets.get(SecretStore.connectorSecret("todo-token")))
    }

    @Test
    fun `an OAuth connector counts as set up only with its own session`() = runTest {
        val g = TestGraph(backgroundScope)
        g.home.addConnector(draft("notion", Connector.Auth("oauth", secret = "notion-oauth")).copy(kind = "mcp", operations = emptyList()))
        g.secrets.put(SecretStore.connectorSecret("notion-oauth"), "not a session")
        assertFalse(g.library.library.first().connectors.single().hasSecret)
        g.secrets.put(SecretStore.mcpOAuthSession("notion-oauth"), "{}")
        assertTrue(g.library.library.first { lib -> lib.connectors.single().hasSecret }.connectors.single().hasSecret)
    }

    @Test
    fun `connectors without auth need nothing, and removing one archives it`() = runTest {
        val g = TestGraph(backgroundScope)
        g.home.addConnector(draft("weather", Connector.Auth()))
        assertTrue(g.library.library.first().connectors.single().hasSecret)
        g.library.removeConnector("weather")
        assertTrue(g.library.library.first { it.connectors.isEmpty() }.connectors.isEmpty())
        assertNull(g.home.connector("weather"))
    }

    @Test
    fun `rejecting a draft removes it without activating anything`() = runTest {
        val g = TestGraph(backgroundScope)
        g.home.draftConnector(draft("spam", Connector.Auth("bearer", secret = "spam-token")))
        g.library.rejectConnector("spam")
        assertTrue(g.home.connectorDrafts().isEmpty())
        assertTrue(g.home.connectors().isEmpty())
    }
}
