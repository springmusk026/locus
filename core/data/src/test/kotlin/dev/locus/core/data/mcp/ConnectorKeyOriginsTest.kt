package dev.locus.core.data.mcp

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.mcp.OAuthSession
import dev.locus.core.data.repository.KeyState
import dev.locus.core.datastore.SecretStore
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A connector key goes only to the address it was saved for, and a sign-in only to the server it was
 * issued for (docs/design/mcp-connectors.md, "Keys belong to an address").
 */
class ConnectorKeyOriginsTest {
    private fun remote(id: String, url: String, auth: Connector.Auth) =
        Connector(id = id, kind = "mcp", name = id, description = "d", baseUrl = url, auth = auth)

    private fun session(resource: String) = Json.encodeToString(
        OAuthSession.serializer(),
        OAuthSession(issuer = "https://auth.test", clientId = "c", tokenEndpoint = "https://auth.test/token", resource = resource, accessToken = "at-GITHUB"),
    )

    @Test
    fun `a sign-in issued for one server is never sent to another`() = runTest {
        val g = TestGraph(backgroundScope)
        g.secrets.put(SecretStore.mcpOAuthSession("issues-oauth"), session("https://api.githubcopilot.com/mcp"))
        g.home.addConnector(remote("issues", "https://attacker.example/mcp", Connector.Auth("oauth", secret = "issues-oauth")))

        assertNull(g.mcpSignIn.accessToken("issues-oauth", "https://attacker.example/mcp"))
        assertEquals("at-GITHUB", g.mcpSignIn.accessToken("issues-oauth", "https://api.githubcopilot.com/mcp"))
        val e = assertFailsWith<ToolException> { g.mcp.client("issues") }
        assertTrue("api.githubcopilot.com" in e.message.orEmpty(), e.message)
        assertTrue(g.requests.isEmpty())
        // Library doesn't call it signed in.
        val entry = g.library.library.first().connectors.single()
        assertFalse(entry.hasSecret)
        assertTrue(entry.key is KeyState.Elsewhere)
    }

    @Test
    fun `keys saved before addresses were recorded are tied to their only connector`() = runTest {
        val g = TestGraph(backgroundScope)
        g.home.addConnector(remote("todo", "https://api.todo.example/mcp", Connector.Auth("bearer", secret = "todo-token")))
        g.secrets.put(SecretStore.connectorSecret("todo-token"), "sk-todo")

        val entry = g.library.library.first().connectors.single()
        assertEquals(KeyState.Ready, entry.key)
        assertEquals(setOf("https://api.todo.example"), g.keyOrigins.read("todo-token"))
        assertEquals("1", g.secrets.get(SecretStore.CONNECTOR_ORIGINS_MIGRATED))
    }

    @Test
    fun `a key shared across addresses, or a built-in's, waits for the user to confirm it`() = runTest {
        val g = TestGraph(backgroundScope)
        // Written as an older Locus would have: shared names weren't refused then.
        g.home.connectorsDir.resolve("a.json").writeText(Json.encodeToString(Connector.serializer(), remote("a", "https://a.example/mcp", Connector.Auth("bearer", secret = "shared"))))
        g.home.connectorsDir.resolve("b.json").writeText(Json.encodeToString(Connector.serializer(), remote("b", "https://b.example/mcp", Connector.Auth("bearer", secret = "shared"))))
        g.home.connectorsDir.resolve("gh.json").writeText(Json.encodeToString(Connector.serializer(), remote("gh", "https://api.githubcopilot.com/mcp", Connector.Auth("bearer", secret = "integration-github"))))
        g.secrets.put(SecretStore.connectorSecret("shared"), "sk-shared")
        g.secrets.put(SecretStore.connectorSecret("integration-github"), "ghp_x")

        g.keyOrigins.migrate()
        assertTrue(g.keyOrigins.read("shared").isEmpty())
        assertTrue(g.keyOrigins.read("integration-github").isEmpty())
        assertFailsWith<ToolException> { g.mcp.client("a") }.also { assertTrue("confirm" in it.message.orEmpty(), it.message) }

        // Confirming once ties it to that connector's address only.
        g.library.confirmConnectorKey("a")
        assertEquals(setOf("https://a.example"), g.keyOrigins.read("shared"))
        val entries = g.library.library.first().connectors.associateBy { it.connector.id }
        assertEquals(KeyState.Ready, entries.getValue("a").key)
        assertEquals(KeyState.Elsewhere(listOf("a.example")), entries.getValue("b").key)
        assertEquals(KeyState.Unconfirmed, entries.getValue("gh").key)

        // A new key for b gets a name of its own, so a's key is untouched.
        g.library.setConnectorSecret("shared", "sk-b", connectorId = "b")
        val b = g.home.connector("b")!!
        assertEquals("b-token", b.auth.secret)
        assertEquals("sk-b", g.secrets.get(SecretStore.connectorSecret("b-token")))
        assertEquals("sk-shared", g.secrets.get(SecretStore.connectorSecret("shared")))
        assertEquals(setOf("https://b.example"), g.keyOrigins.read("b-token"))
    }

    @Test
    fun `migration ties keys to approved connectors, never to a draft approved afterwards`() = runTest {
        val g = TestGraph(backgroundScope)
        g.home.addConnector(remote("todo", "https://api.todo.example/mcp", Connector.Auth("bearer", secret = "todo-token")))
        g.secrets.put(SecretStore.connectorSecret("todo-token"), "sk-todo")
        // A draft replacing it at another address, from before drafts were checked.
        g.home.connectorDraftsDir.resolve("todo.json").writeText(
            Json.encodeToString(Connector.serializer(), remote("todo", "https://attacker.example/mcp", Connector.Auth("bearer", secret = "todo-token"))),
        )
        g.library.approveConnector("todo")
        assertEquals(setOf("https://api.todo.example"), g.keyOrigins.read("todo-token"))
        assertFailsWith<ToolException> { g.mcp.client("todo") }
    }

    @Test
    fun `a key the user adds is tied to the server they added it for`() = runTest {
        val g = TestGraph(backgroundScope)
        g.library.addMcpServer("Tracker", "https://mcp.tracker.example/mcp", "tok-1")
        assertEquals(setOf("https://mcp.tracker.example"), g.keyOrigins.read("tracker-token"))
        assertEquals(KeyState.Ready, g.library.library.first().connectors.single().key)
    }
}
