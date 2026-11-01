package dev.locus.feature.settings

import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.home.Connector
import dev.locus.core.data.mcp.McpFirstStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which built-in integrations recommend their vendor's MCP server (docs/design/mcp-connectors.md). */
class OfficialMcpTest {
    @Test
    fun `built-ins with a usable official server recommend it, the rest don't`() {
        val recommended = Integrations.all.filter { officialMcp(it.id) != null }.map { it.id }.toSet()
        assertEquals(setOf("github", "netlify", "cloudflare", "supabase", "render", "firecrawl", "tavily", "exa"), recommended)
    }

    @Test
    fun `the recommendation is a remote https server`() {
        Integrations.all.mapNotNull { officialMcp(it.id) }.forEach { t ->
            assertTrue(t.url.startsWith("https://") && t.local == null, t.id)
        }
    }

    private fun status(using: Boolean = true, kept: Boolean = false, problem: String? = null) = McpFirstStatus(
        integration = Integrations.netlify,
        connector = Connector(id = "netlify", kind = "mcp", name = "Netlify", description = "d", baseUrl = "https://netlify-mcp.netlify.app/mcp"),
        builtInKey = true, mcpReady = problem == null, problem = problem, keptBuiltIn = kept,
    ).also { assertEquals(using, it.usingMcp) }

    @Test
    fun `with both set up, the sheet says which one new chats use`() {
        val using = status()
        assertEquals(USING_MCP, mcpFirstTitle(using))
        val body = mcpFirstBody(Integrations.netlify, using, "Netlify")
        assertTrue("netlify_deploy stays" in body, body)
        assertTrue("Chats you already have keep the tools they started with" in body)

        val kept = status(using = false, kept = true)
        assertEquals("Using the built-in tools in new chats", mcpFirstTitle(kept))

        val failing = status(using = false, problem = "401 Unauthorized")
        assertEquals("The MCP server isn't working", mcpFirstTitle(failing))
        assertTrue("401 Unauthorized" in mcpFirstBody(Integrations.netlify, failing, "Netlify"))
    }
}
