package dev.locus.feature.settings

import dev.locus.core.agent.integrations.Integrations
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
}
