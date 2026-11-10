package dev.locus.feature.settings

import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.home.Connector
import dev.locus.core.data.mcp.McpFirstStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which built-in integrations recommend their vendor's MCP server (docs/design/mcp-connectors.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfficialMcpTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

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
        assertEquals("Using the MCP server in new chats", mcpFirstTitle(res, using))
        val body = mcpFirstBody(res, Integrations.netlify, using, "Netlify")
        assertTrue("netlify_deploy stays" in body, body)
        assertTrue("Chats you already have keep the tools they started with" in body)

        val kept = status(using = false, kept = true)
        assertEquals("Using the built-in tools in new chats", mcpFirstTitle(res, kept))

        val failing = status(using = false, problem = "401 Unauthorized")
        assertEquals("The MCP server isn't working", mcpFirstTitle(res, failing))
        assertTrue("401 Unauthorized" in mcpFirstBody(res, Integrations.netlify, failing, "Netlify"))
    }
}
