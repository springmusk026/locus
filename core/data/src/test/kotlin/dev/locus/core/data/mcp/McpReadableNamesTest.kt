package dev.locus.core.data.mcp

import dev.locus.core.agent.home.Connector
import dev.locus.core.data.connectors.ConnectorTemplates
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What people read: approval cards name the server and the action in words, and templates never show raw keys. */
class McpReadableNamesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val g = TestGraph(scope)

    @After
    fun cleanup() {
        scope.cancel()
    }

    private fun call(connector: String, tool: String, arguments: String = "{}") =
        Json.parseToJsonElement("""{"connector":"$connector","tool":"$tool","arguments":$arguments}""") as JsonObject

    @Test
    fun `an MCP call is described by the server's name and what it does`() {
        g.home.addConnector(
            Connector("home-assistant", kind = "mcp", name = "Home Assistant", description = "d", baseUrl = "https://ha.example/api/mcp/assist",
                auth = Connector.Auth("bearer", secret = "home-assistant-token"), template = "home-assistant"),
        )
        g.home.addConnector(Connector("github", kind = "mcp", name = "GitHub", description = "d", baseUrl = "https://api.githubcopilot.com/mcp/"))
        val tool = mcpTools(g.mcp).first { it.spec.name == "mcp_call" }
        assertEquals("Home Assistant · Unlock Front door", tool.describe(call("home-assistant", "intent__HassTurnOff", """{"name":"Front door","domain":["lock"]}""")))
        assertEquals("GitHub · Create issue", tool.describe(call("github", "create_issue")))
        // A connector that's gone: its id, made readable.
        assertEquals("Old server · List items", tool.describe(call("old-server", "list_items")))
    }

    @Test
    fun `template requirements are in words, never raw variable names`() {
        val raw = Regex("\\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\\b")
        McpTemplates.all.forEach { t ->
            assertFalse(raw.containsMatchIn(t.authSummary), "${t.id}: ${t.authSummary}")
            t.local?.let { local -> local.env.forEach { e -> assertTrue(e in local.labels, "${t.id}: label $e") } }
            t.note?.let { assertFalse(raw.containsMatchIn(it), "${t.id} note: $it") }
        }
        ConnectorTemplates.all.forEach { t -> assertFalse(raw.containsMatchIn(t.auth.label + t.description), t.id) }
        assertEquals("Needs your Nextcloud address, user name and app password", McpTemplates.byId("nextcloud")!!.authSummary)
        assertEquals("Needs a Brave Search API key", McpTemplates.byId("brave-local")!!.authSummary)
        assertEquals("Needs a long-lived access token", McpTemplates.byId("home-assistant")!!.authSummary)
    }
}
