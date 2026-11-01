package dev.locus.core.data.mcp

import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.McpFirst
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Role
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A user with both a built-in integration and the vendor's MCP server: new chats leave the built-in's
 * tools out (keeping workspace deploys and the search backends), decided when the chat is created.
 * Chats that already exist keep their tool list, request after request.
 */
class McpFirstChatsTest {
    private val quiet = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private fun githubMcp(id: String = "github") = Connector(
        id = id, kind = "mcp", name = "GitHub", description = "d", baseUrl = "https://api.githubcopilot.com/mcp/",
        auth = Connector.Auth("bearer", secret = "$id-token"), createdBy = "user",
    )

    private suspend fun TestGraph.githubBoth() {
        secrets.put(SecretStore.connectorSecret(Integrations.github.secret), "ghp_builtin")
        home.addConnector(githubMcp())
        secrets.put(SecretStore.connectorSecret("github-token"), "ghp_mcp")
    }

    /** Sends [text] and waits for the reply; returns the tool names the request declared and its system prompt. */
    private suspend fun TestGraph.turn(chat: String, text: String): Pair<List<String>, String> {
        val before = conversations.history(chat).count { it.role == Role.ASSISTANT }
        runner.send(chat, text)
        conversations.observeMessages(chat).first { m -> m.count { it.role == Role.ASSISTANT } > before }
        val body = requests.last { it.url.host == "llm.test" }.body as OutgoingContent.ByteArrayContent
        val json = Json.parseToJsonElement(body.bytes().decodeToString()).jsonObject
        val tools = json.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
        val system = json.getValue("messages").jsonArray.first().jsonObject.getValue("content").toString()
        return tools to system
    }

    private fun TestScope.graph(settings: AppSettings = quiet) = TestGraph(backgroundScope, settings)

    @Test
    fun `a new chat leaves out the built-in tools, an existing chat keeps them`() = runTest {
        val g = graph()
        g.script(*Array(4) { ScriptedProvider.text("ok") })
        g.secrets.put(SecretStore.connectorSecret(Integrations.github.secret), "ghp_builtin")
        val old = g.conversations.create(quiet.defaultProviderId).id
        assertEquals(emptySet(), g.conversations.get(old)!!.mcpFirst)

        // The user adds GitHub's MCP server afterwards.
        g.home.addConnector(githubMcp())
        g.secrets.put(SecretStore.connectorSecret("github-token"), "ghp_mcp")
        val new = g.conversations.create(quiet.defaultProviderId).id
        assertEquals(setOf("github"), g.conversations.get(new)!!.mcpFirst)

        val (oldTools, oldSystem) = g.turn(old, "hi")
        val (newTools, newSystem) = g.turn(new, "hi")
        assertTrue("github_repos" in oldTools)
        assertTrue(McpFirst.leftOut.getValue("github").none { it in newTools }, newTools.toString())
        assertTrue("mcp_call" in newTools && "mcp_list_tools" in newTools)
        // Only GitHub's tools went; everything else is the same list in the same order.
        assertEquals(oldTools - McpFirst.leftOut.getValue("github"), newTools)
        assertTrue("Services through MCP" in newSystem && "“github”" in newSystem)
        assertFalse("Services through MCP" in oldSystem)

        // Switching back in Settings affects the next new chat only; both chats keep their lists.
        g.settingsRepository.setUseMcpFor("github", false)
        val third = g.conversations.create(quiet.defaultProviderId).id
        assertEquals(emptySet(), g.conversations.get(third)!!.mcpFirst)
        assertEquals(oldTools, g.turn(old, "again").first)
        assertEquals(newTools to newSystem, g.turn(new, "again"))
    }

    @Test
    fun `workspace deploys and the search backends always stay`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.text("ok"))
        listOf("cloudflare", "netlify", "supabase", "firecrawl", "exa").forEach { id ->
            val integration = Integrations.all.single { it.id == id }
            g.secrets.put(SecretStore.connectorSecret(integration.secret), "k-$id")
            val template = McpTemplates.forIntegration(id)!!
            g.home.addConnector(Connector(id = id, kind = "mcp", name = id, description = "d", baseUrl = template.url, auth = Connector.Auth("bearer", secret = "$id-t"), createdBy = "user"))
            g.secrets.put(SecretStore.connectorSecret("$id-t"), "v")
        }
        val chat = g.conversations.create(quiet.defaultProviderId).id
        // The search services have nothing to hand over.
        assertEquals(setOf("cloudflare", "netlify", "supabase"), g.conversations.get(chat)!!.mcpFirst)
        val tools = g.turn(chat, "hi").first
        listOf("cloudflare_deploy_static", "netlify_deploy", "supabase_function_deploy", "web_search", "web_read").forEach { assertTrue(it in tools, it) }
        listOf("cloudflare_dns", "netlify_sites", "supabase_sql").forEach { assertFalse(it in tools, it) }
        // Untouched services keep theirs.
        assertTrue("github_repos" in tools && "render_services" in tools && "vercel_deploy" in tools)
    }

    @Test
    fun `helpers declare their chat's list`() = runTest {
        val g = graph()
        g.githubBoth()
        val chat = g.conversations.create(quiet.defaultProviderId)
        val helper = g.conversations.createHelper("h1", chat.id, "call", "researcher", "look", null, null, "sys", chat.mcpFirst)
        assertEquals(setOf("github"), helper.mcpFirst)
        assertEquals(g.tools.forChat(chat).specs, g.tools.forChat(helper).specs)
        // A copy of the chat (fork) keeps it too.
        g.conversations.appendUserMessage(chat.id, "hi")
        val copy = g.conversations.fork(chat.id, g.conversations.history(chat.id).last().id, inclusive = true)!!
        assertEquals(chat.mcpFirst, copy.mcpFirst)
        assertEquals(chat.systemPrompt, copy.systemPrompt)
    }

    @Test
    fun `nothing is left out without both, when switched back, or when the server fails`() = runTest {
        val g = graph()
        // Only the MCP server: no built-in key, so nothing overlaps.
        g.home.addConnector(githubMcp())
        g.secrets.put(SecretStore.connectorSecret("github-token"), "ghp_mcp")
        assertEquals(emptyMap(), g.mcpPreference.choose())
        // Both, but the connector's key is missing.
        g.secrets.put(SecretStore.connectorSecret(Integrations.github.secret), "ghp_builtin")
        g.secrets.put(SecretStore.connectorSecret("github-token"), null)
        assertEquals(emptyMap(), g.mcpPreference.choose())
        assertEquals("Its key is missing", g.mcpPreference.statuses().single { it.integration.id == "github" }.problem)
        g.secrets.put(SecretStore.connectorSecret("github-token"), "ghp_mcp")
        assertEquals(mapOf("github" to "github"), g.mcpPreference.choose())

        // The user keeps the built-in.
        g.settingsRepository.setUseMcpFor("github", false)
        assertEquals(emptyMap(), g.mcpPreference.choose())
        g.settingsRepository.setUseMcpFor("github", true)

        // A server that failed in this run isn't relied on; one that answered again is.
        val settings = g.settingsRepository.current()
        val names = g.secrets.names.first()
        val connectors = g.home.connectors()
        val failing = McpFirstRule.statuses(settings, names, connectors, RemoteMcpHealth(failing = mapOf("github" to "401 Unauthorized")))
        assertEquals(emptyMap(), McpFirstRule.choose(failing))
        assertEquals("401 Unauthorized", failing.single { it.integration.id == "github" }.problem)
        val working = McpFirstRule.statuses(settings, names, connectors, RemoteMcpHealth(working = setOf("github")))
        assertEquals(mapOf("github" to "github"), McpFirstRule.choose(working))
    }

    @Test
    fun `only the vendor's own server counts, and drafts don't`() = runTest {
        val g = graph()
        g.secrets.put(SecretStore.connectorSecret(Integrations.github.secret), "ghp_builtin")
        // A look-alike host, and a local server, are not GitHub's server.
        g.home.addConnector(githubMcp("gh-proxy").copy(baseUrl = "https://github-mcp.example.com/mcp"))
        g.secrets.put(SecretStore.connectorSecret("gh-proxy-token"), "x")
        assertEquals(emptyMap(), g.mcpPreference.choose())
        // A draft the agent proposed isn't active.
        g.home.draftConnector(githubMcp("gh-draft"))
        g.secrets.put(SecretStore.connectorSecret("gh-draft-token"), "x")
        assertEquals(emptyMap(), g.mcpPreference.choose())
    }

    @Test
    fun `a chat with nothing chosen uses the shared registry`() = runTest {
        val g = graph()
        val chat = g.conversations.create(null)
        assertTrue(g.tools.forChat(chat) === g.tools.registry)
        assertTrue(g.tools.forChat(null) === g.tools.registry)
    }
}
