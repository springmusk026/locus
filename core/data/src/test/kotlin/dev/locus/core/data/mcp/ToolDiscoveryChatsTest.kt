package dev.locus.core.data.mcp

import dev.locus.core.agent.ToolDiscovery
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.McpFirst
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.AppSettings
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.Role
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import io.ktor.http.content.OutgoingContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tool discovery is chosen when a chat is created and kept for its life (docs/design/tool-discovery.md):
 * new chats declare the core set plus find_tools and call_tool, older chats keep every tool, and
 * helpers and copies declare their chat's list.
 */
class ToolDiscoveryChatsTest {
    private val quiet = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private fun TestScope.graph() = TestGraph(backgroundScope, quiet)

    private suspend fun TestGraph.declared(chat: String, text: String): Pair<List<String>, String> {
        val before = conversations.history(chat).count { it.role == Role.ASSISTANT }
        runner.send(chat, text)
        conversations.observeMessages(chat).first { m -> m.count { it.role == Role.ASSISTANT } > before }
        val body = requests.last { it.url.host == "llm.test" }.body as OutgoingContent.ByteArrayContent
        val json = Json.parseToJsonElement(body.bytes().decodeToString()).jsonObject
        val tools = json.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
        return tools to json.getValue("tools").toString()
    }

    @Test
    fun `new chats freeze discovery, older chats keep every tool, request after request`() = runTest {
        val g = graph()
        g.script(*Array(4) { ScriptedProvider.text("ok") })
        // A chat from before discovery.
        g.conversations.discoveryForNewChats = null
        val old = g.conversations.create(quiet.defaultProviderId)
        assertNull(old.toolDiscovery)
        g.conversations.discoveryForNewChats = DiscoveryTools.CURRENT
        val new = g.conversations.create(quiet.defaultProviderId)
        assertEquals(DiscoveryTools.CURRENT, new.toolDiscovery)
        assertEquals(DiscoveryTools.CURRENT, g.conversations.get(new.id)!!.toolDiscovery)
        assertTrue(ToolDiscovery.PROMPT_NOTE in new.systemPrompt)
        assertFalse(ToolDiscovery.PROMPT_NOTE in old.systemPrompt)

        val (oldTools, oldWire) = g.declared(old.id, "hi")
        val (newTools, newWire) = g.declared(new.id, "hi")
        assertTrue("github_repos" in oldTools && "device_alarm" in oldTools && DiscoveryTools.FIND_TOOLS !in oldTools)
        assertEquals(ToolDiscovery.CORE_V1.filter { it in oldTools || it in setOf(DiscoveryTools.FIND_TOOLS, DiscoveryTools.CALL_TOOL) }.sorted(), newTools.sorted())
        assertTrue("github_repos" !in newTools && "device_alarm" !in newTools)
        assertTrue(newWire.length * 2 < oldWire.length, "the declared tools shrink: ${newWire.length} vs ${oldWire.length} chars")
        // The next turn of each sends byte-identical tools: the cached prefix holds.
        assertEquals(oldWire, g.declared(old.id, "again").second)
        assertEquals(newWire, g.declared(new.id, "again").second)
        // Same choice, same registry instance.
        assertTrue(g.tools.forChat(new) === g.tools.forChat(g.conversations.get(new.id)))
    }

    @Test
    fun `helpers and copies declare their chat's list`() = runTest {
        val g = graph()
        val chat = g.conversations.create(quiet.defaultProviderId)
        val helper = g.conversations.createHelper("h1", chat.id, "call", "researcher", "look", null, null, "sys", chat.mcpFirst, chat.toolDiscovery)
        assertEquals(chat.toolDiscovery, helper.toolDiscovery)
        assertEquals(g.tools.forChat(chat).specs, g.tools.forChat(helper).specs)
        g.conversations.appendUserMessage(chat.id, "hi")
        val copy = g.conversations.fork(chat.id, g.conversations.history(chat.id).last().id, inclusive = true)!!
        assertEquals(chat.toolDiscovery, copy.toolDiscovery)
    }

    @Test
    fun `with an MCP-first service, its built-in tools aren't even deferred`() = runTest {
        val g = graph()
        g.secrets.put(SecretStore.connectorSecret(Integrations.github.secret), "ghp_builtin")
        g.home.addConnector(
            Connector(id = "github", kind = "mcp", name = "GitHub", description = "d", baseUrl = "https://api.githubcopilot.com/mcp/", auth = Connector.Auth("bearer", secret = "github-token"), createdBy = "user"),
        )
        g.secrets.put(SecretStore.connectorSecret("github-token"), "ghp_mcp")
        val chat = g.conversations.create(quiet.defaultProviderId)
        assertEquals(setOf("github"), chat.mcpFirst)
        val tools = g.tools.forChat(chat)
        val everything = (tools.specs + tools.deferredSpecs).map { it.name }
        assertTrue(McpFirst.leftOut.getValue("github").none { it in everything })
        assertTrue("vercel_deploy" in tools.deferredSpecs.map { it.name })
        assertTrue(DiscoveryTools.FIND_TOOLS in tools.specs.map { it.name })
    }

    // ---- MCP tools through discovery ---------------------------------------------------------------

    private val createIssue = Tool(
        name = "create_issue",
        description = "Create an issue in a Linear team.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("title") { put("type", "string"); put("description", "Issue title"); put("title", "Title") }
                putJsonObject("priority") { put("type", "integer"); putJsonArray("enum") { add(kotlinx.serialization.json.JsonPrimitive(1)); add(kotlinx.serialization.json.JsonPrimitive(2)) } }
                putJsonObject("assignee") {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("id") { put("type", "string"); put("description", "User id") } }
                }
            },
            required = listOf("title"),
        ),
    )

    @Test
    fun `MCP tools are offered under their own names with compact schemas, and route to mcp_call`() = runTest {
        val discovery = McpDiscovery(
            tools = { id -> if (id == "linear") listOf(createIssue) else error("401 Unauthorized") },
            trusted = { false },
            servers = { listOf(McpDiscovery.Server("linear", "Linear"), McpDiscovery.Server("broken", "Broken")) },
        )
        val listing = discovery.list()
        val spec = listing.tools.single()
        assertEquals("mcp__linear__create_issue", spec.name)
        assertTrue(spec.description.startsWith("Linear: Create an issue"), spec.description)
        val schema = spec.inputSchema.toString()
        assertTrue("Issue title" in schema && "\"enum\":[1,2]" in schema && "User id" in schema && "\"required\":[\"title\"]" in schema, schema)
        assertFalse("\"title\":\"Title\"" in schema, "titles are dropped: $schema")
        assertTrue(listing.problems.single().startsWith("“broken”"))

        val args = buildJsonObject { put("title", "Bug") }
        val (tool, routed) = assertNotNull(discovery.route("mcp__linear__create_issue", args))
        assertEquals("mcp_call", tool)
        assertEquals(buildJsonObject { put("connector", "linear"); put("tool", "create_issue"); put("arguments", args) }, routed)
        assertNull(discovery.route("github_issues", JsonObject(emptyMap())))
    }

    @Test
    fun `mcp_list_tools schemas keep descriptions, enums and nesting`() {
        val compact = McpSchemas.compact(createIssue).toString()
        assertTrue("Issue title" in compact && "[1,2]" in compact && "\"assignee\"" in compact && "User id" in compact, compact)
        assertEquals(createIssue.schemaJson()["required"], McpSchemas.compact(createIssue)["required"])
    }
}
