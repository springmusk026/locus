package dev.locus.core.data.mcp

import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.datastore.SecretStore
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Use the key saved for <Integration>" on an MCP template: which templates offer it, and the vault-to-vault copy. */
class SavedKeysTest {
    private fun template(id: String) = McpTemplates.byId(id)!!

    @Test
    fun `offered exactly where the server takes the built-in's kind of key`() {
        val offered = McpTemplates.all.mapNotNull { t -> SavedKeys.source(t)?.let { t.id to it.id } }.toMap()
        assertEquals(
            mapOf(
                "github" to "github", "supabase" to "supabase", "render" to "render", "cloudflare" to "cloudflare",
                "firecrawl" to "firecrawl", "tavily" to "tavily", "exa" to "exa",
            ),
            offered,
        )
        // Each one sends the key the way the vendor's MCP docs say.
        assertEquals(SavedKeys.Shape("bearer"), SavedKeys.shape(template("github").key!!))
        assertEquals(SavedKeys.Shape("header", "x-firecrawl-api-key"), SavedKeys.shape(template("firecrawl").key!!))
        assertEquals(SavedKeys.Shape("query", "tavilyapikey"), SavedKeys.shape(template("tavily").key!!))
        assertEquals(SavedKeys.Shape("header", "x-api-key"), SavedKeys.shape(template("exa").key!!))
    }

    @Test
    fun `never offered for sign-in-only, unrelated or local templates`() {
        // Netlify's server is OAuth only: no key to put the saved one in.
        assertNull(template("netlify").key)
        assertNull(SavedKeys.source(template("netlify")))
        // Sign-in only, and no built-in behind them.
        listOf("notion", "sentry", "atlassian", "railway").forEach { assertNull(SavedKeys.source(template(it)), it) }
        // Takes a key, but no built-in stands behind it.
        listOf("linear", "stitch", "context7", "gitlab").forEach { assertNull(SavedKeys.source(template(it)), it) }
        // Brave runs locally with an env var; Vercel has no usable template.
        assertNull(SavedKeys.source(template("brave-local")))
        assertNull(McpTemplates.forIntegration("vercel"))
    }

    @Test
    fun `a template whose auth changes stops offering the saved key`() {
        val github = template("github")
        assertNull(SavedKeys.source(github.copy(key = github.key!!.copy(header = "X-Other-Key"))))
        assertNull(SavedKeys.source(github.copy(key = null, signIn = true)))
        val tavily = template("tavily")
        assertNull(SavedKeys.source(tavily.copy(key = tavily.key!!.copy(query = null))))
    }

    @Test
    fun `only the key goes, not the extra words some built-ins keep`() {
        assertEquals("cf-token", SavedKeys.keyPart("  cf-token   0123abcd "))
        assertEquals("ghp_x", SavedKeys.keyPart("ghp_x\n"))
        assertNull(SavedKeys.keyPart("   "))
    }

    @Test
    fun `the saved key only goes to the template's own host`() {
        val github = template("github")
        assertTrue(SavedKeys.sameHost(github, "https://api.githubcopilot.com/mcp/readonly"))
        assertTrue(SavedKeys.sameHost(github, "https://API.githubcopilot.com/mcp/x/repos"))
        assertFalse(SavedKeys.sameHost(github, "https://evil.example/mcp/"))
        assertFalse(SavedKeys.sameHost(github, "http://api.githubcopilot.com/mcp/"))
        assertFalse(SavedKeys.sameHost(github, "https://user@api.githubcopilot.com/mcp/"))
        assertFalse(SavedKeys.sameHost(github, "https://api.githubcopilot.com.evil.example/mcp/"))
    }

    @Test
    fun `adding with the saved key copies it vault to vault and nowhere else`() = runTest {
        val g = TestGraph(backgroundScope)
        val key = "github_pat_SECRET123"
        g.secrets.put(SecretStore.connectorSecret(Integrations.github.secret), key)

        g.library.addMcpTemplate(template("github"), "https://api.githubcopilot.com/mcp/readonly", key = null, useSavedKey = true)

        val connector = g.home.connector("github")!!
        assertEquals(Connector.Auth("bearer", secret = "github-token"), connector.auth)
        assertEquals("https://api.githubcopilot.com/mcp/readonly", connector.baseUrl)
        // The copy sits in the vault under the connector's own name; the built-in keeps its own.
        assertEquals(key, g.secrets.get(SecretStore.connectorSecret("github-token")))
        assertEquals(key, g.secrets.get(SecretStore.connectorSecret(Integrations.github.secret)))
        // Nothing outside the vault holds it: not the connector file, not what Library shows.
        val files = g.root.walkTopDown().filter(File::isFile).filterNot { it.name.endsWith(".preferences_pb") }
        files.forEach { assertFalse(key in it.readText(Charsets.ISO_8859_1), it.path) }
        val library = g.library.library.first()
        assertFalse(key in library.toString())
        assertTrue("github" in library.savedIntegrationKeys)
        assertTrue(library.connectors.single().hasSecret)
        // No request was made: a key means no sign-in.
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `a Cloudflare key with its account ID copies just the token, into the template's header shape`() = runTest {
        val g = TestGraph(backgroundScope)
        g.secrets.put(SecretStore.connectorSecret(Integrations.cloudflare.secret), "cf-token-1 0123456789abcdef")
        g.library.addMcpTemplate(template("cloudflare"), template("cloudflare").url, useSavedKey = true)
        assertEquals("cf-token-1", g.secrets.get(SecretStore.connectorSecret("cloudflare-token")))

        g.secrets.put(SecretStore.connectorSecret(Integrations.firecrawl.secret), "fc-1")
        g.library.addMcpTemplate(template("firecrawl"), template("firecrawl").url, useSavedKey = true)
        assertEquals(Connector.Auth("header", "x-firecrawl-api-key", "firecrawl-token"), g.home.connector("firecrawl")!!.auth)
        assertEquals("fc-1", g.secrets.get(SecretStore.connectorSecret("firecrawl-token")))
    }

    @Test
    fun `refusals save nothing and never repeat the key`() = runTest {
        val g = TestGraph(backgroundScope)
        val key = "rnd_SECRET456"
        // Nothing saved yet.
        val missing = assertFailsWith<IllegalStateException> { g.library.addMcpTemplate(template("render"), template("render").url, useSavedKey = true) }
        assertTrue("Render" in missing.message.orEmpty())
        assertNull(g.home.connector("render"))

        g.secrets.put(SecretStore.connectorSecret(Integrations.render.secret), key)
        // An edited host never gets the saved key.
        val moved = assertFailsWith<IllegalArgumentException> {
            g.library.addMcpTemplate(template("render"), "https://render.evil.example/mcp", useSavedKey = true)
        }
        assertFalse(key in moved.message.orEmpty())
        // A template that takes no key of that kind.
        val netlify = assertFailsWith<IllegalArgumentException> { g.library.addMcpTemplate(template("netlify"), template("netlify").url, useSavedKey = true) }
        assertFalse(key in netlify.message.orEmpty())
        assertNull(g.home.connector("render"))
        assertNull(g.home.connector("netlify"))
        assertNull(g.secrets.get(SecretStore.connectorSecret("render-token")))
        assertEquals(setOf(SecretStore.connectorSecret(Integrations.render.secret)), g.secrets.names.first())
    }
}
