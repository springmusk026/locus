package dev.locus.core.agent

import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.McpFirst
import dev.locus.core.agent.integrations.integrationTools
import dev.locus.core.agent.tools.Workspace
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which built-in tools a chat that uses a vendor's MCP server leaves out, and which always stay. */
class McpFirstTest {
    private val base = Files.createTempDirectory("mcp-first").toFile()
    private val tools = integrationTools(
        HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
        SecretResolver { null },
        Workspace(base.resolve("workspace"), base.resolve("trash")),
    )
    private val names = tools.map { it.spec.name }.toSet()

    @Test
    fun `every tool it leaves out exists and belongs to its integration`() {
        McpFirst.leftOut.forEach { (id, left) ->
            assertTrue(Integrations.all.any { it.id == id }, id)
            left.forEach { name ->
                assertTrue(name in names, "$name isn't a built-in tool")
                assertTrue(name.startsWith("${id}_"), "$name isn't $id's")
            }
        }
    }

    @Test
    fun `workspace deploys and the search backends are never left out`() {
        val all = McpFirst.leftOut(McpFirst.leftOut.keys)
        McpFirst.alwaysKept.forEach { assertTrue(it !in all, it) }
        listOf("cloudflare_deploy_static", "netlify_deploy", "supabase_function_deploy", "web_search", "web_read").forEach { assertTrue(it in names && it !in all, it) }
        // Search services have nothing to hand over: their keys back web_search.
        Integrations.searchOrder.forEach { assertTrue(!McpFirst.canHandOver(it.id), it.id) }
        // Every other tool of a service that hands over is left out, so the chat has one way to it.
        McpFirst.leftOut.keys.forEach { id ->
            assertEquals(names.filter { it.startsWith("${id}_") }.toSet() - McpFirst.alwaysKept, McpFirst.leftOut.getValue(id), id)
        }
    }

    @Test
    fun `a registry without them keeps the rest in order`() {
        val registry = ToolRegistry(tools)
        val trimmed = registry.without(McpFirst.leftOut(setOf("github")))
        assertEquals(registry.specs.filterNot { it.name.startsWith("github_") }, trimmed.specs)
        assertTrue(registry.without(emptySet()) === registry)
        assertTrue(registry.without(setOf("not_a_tool")) === registry)
        assertEquals(null, trimmed["github_repos"])
    }

    @Test
    fun `the prompt note names each service's connector, and is empty without any`() {
        assertEquals("", McpFirst.promptNote(emptyMap()))
        val note = McpFirst.promptNote(mapOf("GitHub" to "github", "Render" to "render-mcp"))
        assertTrue("GitHub through the MCP connector “github”" in note)
        assertTrue("Render through the MCP connector “render-mcp”" in note)
        assertTrue("Settings → Integrations" in note)
    }
}
