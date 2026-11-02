package dev.locus.core.data.mcp

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Library → Connectors draws an added connector with its template's logo, by [McpTemplates.iconId]. */
class ConnectorIconIdTest {
    /** The connector Library saves for [t], the way LibraryRepository.addMcpTemplate names it. */
    private fun added(t: McpTemplate, id: String = t.id) = if (t.local != null) {
        Connector(id = AgentHome.slug(t.name).take(48), kind = "mcp", name = t.name, description = t.description, local = Connector.Local(t.local.command, emptyList(), emptyList()), createdBy = "user")
    } else {
        Connector(id = id, kind = "mcp", name = t.name, description = t.description, baseUrl = t.url, createdBy = "user")
    }

    @Test
    fun `every template, once added, is drawn by its own id`() {
        val wrong = McpTemplates.all.filter { McpTemplates.iconId(added(it)) != it.id }.map { it.id to McpTemplates.iconId(added(it)) }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `a template added again under its -mcp id keeps the logo`() {
        val linear = McpTemplates.byId("linear")!!
        assertEquals("linear", McpTemplates.iconId(added(linear, id = "linear-mcp")))
        // Even when the user changed the address, the id it was saved under still says what it is.
        assertEquals("linear", McpTemplates.iconId(added(linear, id = "linear-mcp").copy(baseUrl = "https://proxy.example.com/mcp")))
    }

    @Test
    fun `a custom MCP server has no logo id, so it gets a monogram`() {
        val custom = Connector(id = "my-server", kind = "mcp", name = "My server", description = "d", baseUrl = "https://mcp.example.com/mcp", createdBy = "user")
        assertNull(McpTemplates.iconId(custom))
        val local = Connector(id = "tool", kind = "mcp", name = "Tool", description = "d", local = Connector.Local("python3 tool.py", emptyList(), emptyList()))
        assertNull(McpTemplates.iconId(local))
    }

    @Test
    fun `a connector Locus drafted for a service is drawn by its own id`() {
        val drafted = Connector(id = "github", name = "GitHub", description = "d", baseUrl = "https://api.github.com")
        assertEquals("github", McpTemplates.iconId(drafted))
    }
}
