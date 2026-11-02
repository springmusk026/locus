package dev.locus.core.data.repository

import dev.locus.core.agent.home.Connector
import dev.locus.core.model.AppSettings
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.Part
import dev.locus.core.model.ProjectPolicy
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Projects end to end (docs/design/projects.md): what a project chat freezes at creation, where its
 * file tools land, which policy applies, which connectors it may use, and when a chat may move.
 */
class ProjectChatsTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
        toolPolicies = mapOf("write_file" to ToolPolicy.ALLOW, "mcp_call" to ToolPolicy.ALLOW),
    )

    private fun TestScope.graph(s: AppSettings = settings) = TestGraph(backgroundScope, s)

    /** Sends [text], waits for the reply, and returns the chat's tool results. */
    private suspend fun TestGraph.turn(chat: String, text: String): List<Part.ToolResult> {
        val before = conversations.history(chat).count { it.role == Role.ASSISTANT }
        runner.send(chat, text)
        conversations.observeMessages(chat).first { m -> m.count { it.role == Role.ASSISTANT && it.parts.none { p -> p is Part.ToolCall } } > before }
        return conversations.history(chat).flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
    }

    @Test
    fun `a project chat freezes the instructions and memory scope it started with`() = runTest {
        val g = graph()
        val project = assertNotNull(g.projects.create("Garden", instructions = "Use metric units."))
        assertEquals("projects/garden", project.folder)
        val first = g.conversations.create(null, project.id)
        assertEquals(project.id, first.projectId)
        assertEquals(MemoryScope.PROJECT_AND_GLOBAL, first.projectMemory)
        assertTrue("Use metric units." in first.systemPrompt)
        assertTrue("<project>" in first.systemPrompt)

        g.projects.setInstructions(project.id, "Use imperial units.")
        g.projects.update(project.copy(memoryScope = MemoryScope.PROJECT_ONLY))
        val kept = assertNotNull(g.conversations.get(first.id))
        assertEquals(first.systemPrompt, kept.systemPrompt)
        assertEquals(MemoryScope.PROJECT_AND_GLOBAL, kept.projectMemory)

        val second = g.conversations.create(null, project.id)
        assertTrue("Use imperial units." in second.systemPrompt && "metric" !in second.systemPrompt)
        assertEquals(MemoryScope.PROJECT_ONLY, second.projectMemory)
        // The project's name is for the user only.
        assertFalse("Garden" in second.systemPrompt)
        // Chats outside projects are unchanged.
        assertNull(g.conversations.create(null).projectId)
    }

    @Test
    fun `file tools in a project chat are rooted in its folder`() = runTest {
        val g = graph()
        File(g.workspace.base, "notes.txt").writeText("global")
        val project = assertNotNull(g.projects.create("Garden"))
        val chat = g.conversations.create(null, project.id).id
        g.script(
            ScriptedProvider.toolCall("c1", "write_file", """{"path":"plan.md","content":"beds"}"""),
            ScriptedProvider.toolCall("c2", "read_file", """{"path":"../../notes.txt"}"""),
            ScriptedProvider.text("done"),
        )
        val results = g.turn(chat, "go")
        assertEquals("beds", File(g.workspace.base, "projects/garden/plan.md").readText())
        assertFalse(File(g.workspace.base, "plan.md").exists())
        val escape = results.single { it.callId == "c2" }
        assertTrue(escape.isError && "outside the project's folder" in escape.content, escape.content)
    }

    @Test
    fun `the project overlay narrows the global policy unless widened`() = runTest {
        val g = graph()
        val project = assertNotNull(g.projects.create("Garden"))
        val write = g.tools.registry["write_file"]!!
        val run = g.tools.registry["run_command"]!!
        g.projects.setPolicy(project.id, ProjectPolicy(toolPolicies = mapOf("write_file" to ToolPolicy.ASK, "run_command" to ToolPolicy.ALLOW)))
        val s = g.settingsRepository.current()
        assertEquals(ToolPolicy.ALLOW, g.tools.policy(write, s))
        assertEquals(ToolPolicy.ASK, g.tools.policy(write, s, project.id))
        // Allow in the project doesn't loosen run_command's default (Ask)...
        assertEquals(run.defaultPolicy, g.tools.policy(run, s, project.id))
        // ...unless the user widened it there.
        g.projects.setPolicy(project.id, g.projects.policy(project.id).copy(widened = setOf("run_command")))
        assertEquals(ToolPolicy.ALLOW, g.tools.policy(run, g.settingsRepository.current(), project.id))
        // Deleting the project drops its permissions.
        g.projects.delete(project.id)
        assertNull(g.settingsRepository.current().projectPolicies[project.id])
    }

    @Test
    fun `connectors outside the project's allow-list are refused and left out of the prompt`() = runTest {
        val g = graph()
        g.home.addConnector(Connector(id = "linear", kind = "mcp", name = "Linear", description = "Issues.", baseUrl = "https://mcp.linear.app/mcp", auth = Connector.Auth("none"), createdBy = "user"))
        val project = assertNotNull(g.projects.create("Garden"))
        g.projects.setPolicy(project.id, ProjectPolicy(allowedConnectors = emptySet()))
        val chat = g.conversations.create(null, project.id)
        assertFalse("- linear" in chat.systemPrompt)
        assertTrue("- linear" in g.conversations.create(null).systemPrompt)
        g.script(ScriptedProvider.toolCall("c1", "mcp_call", """{"connector":"linear","tool":"list_issues","arguments":{}}"""), ScriptedProvider.text("ok"))
        val refused = g.turn(chat.id, "issues?").single { it.callId == "c1" }
        assertTrue(refused.isError && "isn't enabled for this project" in refused.content, refused.content)
    }

    @Test
    fun `a project's default model is used for its new chats`() = runTest {
        val other = ProviderConfig("other", "Other", ScriptedProvider.config.format, "https://other.test/v1", "other-model", requiresKey = false)
        val g = graph(settings.copy(providers = settings.providers + other))
        val project = assertNotNull(g.projects.create("Garden"))
        g.projects.update(project.copy(providerId = "other", model = "big-model"))
        val chat = g.conversations.create(ScriptedProvider.config.id, project.id)
        assertEquals("other", chat.providerId)
        assertEquals("big-model", chat.modelOverride)
        // A model the user picks explicitly wins.
        assertEquals(ScriptedProvider.config.id, g.conversations.create(ScriptedProvider.config.id, project.id, explicitModel = true).providerId)
    }

    @Test
    fun `only a chat with no messages moves into a project`() = runTest {
        val g = graph()
        val project = assertNotNull(g.projects.create("Garden", instructions = "Use metric units."))
        val empty = g.conversations.create(null)
        assertTrue(g.conversations.moveToProject(empty.id, project.id))
        val moved = assertNotNull(g.conversations.get(empty.id))
        assertEquals(project.id, moved.projectId)
        assertTrue("Use metric units." in moved.systemPrompt)

        val used = g.conversations.create(null)
        g.conversations.appendUserMessage(used.id, "hello")
        assertFalse(g.conversations.moveToProject(used.id, project.id))
        val unchanged = assertNotNull(g.conversations.get(used.id))
        assertNull(unchanged.projectId)
        assertEquals(used.systemPrompt, unchanged.systemPrompt)
    }

    @Test
    fun `a project folder is unique and clean`() = runTest {
        val g = graph()
        assertNotNull(g.projects.create("Garden"))
        assertNull(g.projects.create("Garden 2", folder = "projects/garden"))
        assertNull(g.projects.create("Escape", folder = "../outside"))
    }
}
