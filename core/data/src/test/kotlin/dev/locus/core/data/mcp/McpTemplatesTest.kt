package dev.locus.core.data.mcp

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.datastore.SecretStore
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The MCP server catalogue in Library → Connectors, and adding servers from it. */
class McpTemplatesTest {
    private val all = McpTemplates.all

    private fun isHttps(url: String) = runCatching { URI(url) }.getOrNull()?.let { it.scheme == "https" && !it.host.isNullOrBlank() } == true

    @Test
    fun `ids are unique slugs`() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach { assertTrue(AgentHome.SKILL_NAME.matches(it.id), it.id) }
    }

    @Test
    fun `every link is https`() {
        all.forEach { t ->
            assertTrue(isHttps(t.docsUrl), "${t.id} docs: ${t.docsUrl}")
            t.key?.let { assertTrue(isHttps(it.url), "${t.id} key: ${it.url}") }
        }
    }

    @Test
    fun `each template is either a remote https server or a local command`() {
        all.forEach { t ->
            if (t.local != null) {
                assertEquals("", t.url, t.id)
                assertTrue(t.local.command.isNotBlank() && '\n' !in t.local.command, t.id)
                assertFalse(t.signIn, t.id)
                assertNull(t.key, "${t.id}: local servers take keys as env vars")
                t.local.env.forEach { assertTrue(Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(it), it) }
            } else {
                assertTrue(isHttps(t.url), "${t.id}: ${t.url}")
            }
        }
    }

    @Test
    fun `remote servers have distinct hosts, so trust by host is unambiguous`() {
        val hosts = all.filter { it.local == null }.map { it.host.lowercase() }
        assertEquals(hosts.size, hosts.toSet().size, hosts.groupBy { it }.filterValues { it.size > 1 }.keys.toString())
    }

    @Test
    fun `a key is sent in exactly one way`() {
        all.mapNotNull { it.key }.forEach { k -> assertTrue(listOfNotNull(k.header, k.query).size <= 1, k.label) }
        val header = McpTemplate.Key("API key", "https://x.dev", header = "X-Goog-Api-Key").auth("s")
        assertEquals(Connector.Auth("header", "X-Goog-Api-Key", "s"), header)
        assertEquals(Connector.Auth("query", "tavilyApiKey", "s"), McpTemplate.Key("k", "https://x.dev", query = "tavilyApiKey").auth("s"))
        assertEquals(Connector.Auth("bearer", secret = "s"), McpTemplate.Key("k", "https://x.dev").auth("s"))
    }

    @Test
    fun `a server can be added without a sign-in or key only when it allows that`() {
        all.filter { it.local == null && !it.signIn }.forEach { t ->
            // Needs a key it can't do without: it must say where to get one.
            if (t.key != null && !t.key.optional) assertTrue(isHttps(t.key.url), t.id)
        }
    }

    @Test
    fun `stand-ins only name built-in integrations`() {
        val ids = Integrations.all.map { it.id }.toSet()
        all.mapNotNull { it.replaces }.forEach { assertTrue(it in ids, it) }
        assertEquals(all.mapNotNull { it.replaces }.size, all.mapNotNull { it.replaces }.toSet().size)
        assertEquals("github", McpTemplates.forIntegration("github")?.id)
        assertNull(McpTemplates.forIntegration("email"))
    }

    @Test
    fun `the vendor's own server is recognised, look-alikes are not`() {
        fun remote(url: String) = Connector(id = "x", kind = "mcp", name = "x", description = "", baseUrl = url)
        assertEquals("linear", McpTemplates.official(remote("https://mcp.linear.app/mcp/readonly"))?.id)
        assertEquals("supabase", McpTemplates.official(remote("https://mcp.supabase.com/mcp?project_ref=abc&read_only=true"))?.id)
        assertNull(McpTemplates.official(remote("http://mcp.linear.app/mcp")))
        assertNull(McpTemplates.official(remote("https://mcp.linear.app.evil.example/mcp")))
        assertNull(McpTemplates.official(remote("https://example.com/mcp")))
        assertNull(McpTemplates.official(remote("https://mcp.linear.app/mcp").copy(kind = "http")))
        val local = Connector(id = "f", kind = "mcp", name = "f", description = "", local = Connector.Local("npx -y @modelcontextprotocol/server-filesystem /workspace/project"))
        assertEquals("filesystem", McpTemplates.official(local)?.id)
        assertNull(McpTemplates.official(local.copy(local = Connector.Local("npx -y some-other-server"))))
    }

    // ---- Adding from a template --------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val g = TestGraph(scope)

    @After
    fun cleanup() {
        scope.cancel()
    }

    @Test
    fun `a header key goes to the vault, and only its name to the connector`() = runBlocking<Unit> {
        val stitch = McpTemplates.byId("stitch")!!
        g.library.addMcpTemplate(stitch, key = "  AIza-secret  ")
        val c = g.home.connector("stitch")!!
        assertEquals(Connector.Auth("header", "X-Goog-Api-Key", "stitch-token"), c.auth)
        assertEquals("https://stitch.googleapis.com/mcp", c.baseUrl)
        assertEquals("AIza-secret", g.secrets.get(SecretStore.connectorSecret("stitch-token")))
        assertFalse("AIza" in g.home.connectorsDir.resolve("stitch.json").readText())
        assertTrue(g.library.library.first().connectors.single().hasSecret)
        assertNotNull(McpTemplates.official(c))
    }

    @Test
    fun `an optional key can be left out, a required one can't`() = runBlocking<Unit> {
        g.library.addMcpTemplate(McpTemplates.byId("deepwiki")!!)
        assertEquals("none", g.home.connector("deepwiki")!!.auth.type)
        g.library.addMcpTemplate(McpTemplates.byId("exa")!!, key = " ")
        assertEquals("none", g.home.connector("exa")!!.auth.type)
        assertFailsWith<IllegalArgumentException> { g.library.addMcpTemplate(McpTemplates.byId("render")!!) }
        assertNull(g.home.connector("render"))
    }

    @Test
    fun `options added to the URL are kept, but it must stay https`() = runBlocking<Unit> {
        val supabase = McpTemplates.byId("supabase")!!
        g.library.addMcpTemplate(supabase, url = "https://mcp.supabase.com/mcp?project_ref=abc&read_only=true", key = "sbp_123")
        assertEquals("https://mcp.supabase.com/mcp?project_ref=abc&read_only=true", g.home.connector("supabase")!!.baseUrl)
        assertEquals("bearer", g.home.connector("supabase")!!.auth.type)
        assertFailsWith<IllegalArgumentException> { g.library.addMcpTemplate(supabase, url = "http://mcp.supabase.com/mcp", key = "x") }
    }

    @Test
    fun `a template never replaces someone else's connector with the same id`() = runBlocking<Unit> {
        g.library.addMcpServer("GitHub", "https://example.com/my-github-proxy", "tok")
        g.library.addMcpTemplate(McpTemplates.byId("github")!!, key = "ghp_x")
        assertEquals("https://example.com/my-github-proxy", g.home.connector("github")!!.baseUrl)
        assertEquals("https://api.githubcopilot.com/mcp/", g.home.connector("github-mcp")!!.baseUrl)
        // Adding the template again updates its own connector.
        g.library.addMcpTemplate(McpTemplates.byId("github")!!, url = "https://api.githubcopilot.com/mcp/readonly", key = "ghp_y")
        assertEquals("https://api.githubcopilot.com/mcp/readonly", g.home.connector("github-mcp")!!.baseUrl)
    }

    @Test
    fun `a local template saves its env values in the vault`() = runBlocking<Unit> {
        g.library.addMcpTemplate(McpTemplates.byId("brave-local")!!, env = listOf("BRAVE_API_KEY" to "bsk-1"))
        val c = g.library.library.first().connectors.single().connector
        assertEquals("npx -y @brave/brave-search-mcp-server", c.local!!.command)
        assertEquals("bsk-1", g.secrets.get(SecretStore.connectorSecret(c.local!!.env.single().secret)))
        assertNotNull(McpTemplates.official(c))
    }
}
