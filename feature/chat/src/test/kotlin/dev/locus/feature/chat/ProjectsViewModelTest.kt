package dev.locus.feature.chat

import dev.locus.core.model.AppSettings
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The project editor and list (docs/design/projects.md). */
class ProjectsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private fun TestScope.graph() = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))

    private fun TestScope.editor(g: TestGraph, id: String?) =
        host.add(ProjectEditorViewModel(id, g.projects, g.settingsRepository, g.tools, g.library))

    /** Waits until the editor has loaded the project and its permissions, as the screen does before showing them. */
    private suspend fun ProjectEditorViewModel.loaded() = apply { state.first { !it.loading && it.tools.isNotEmpty() } }

    @Test
    fun `creating a project makes its folder and saves its instructions`() = runTest {
        val g = graph()
        val vm = editor(g, null).loaded()
        vm.setName("Garden")
        vm.setInstructions("Use metric units.")
        vm.setMemoryScope(MemoryScope.PROJECT_ONLY)
        var saved: String? = null
        vm.save { saved = it }.join()
        val project = assertNotNull(g.projects.get(saved))
        assertEquals("projects/garden", project.folder)
        assertEquals(MemoryScope.PROJECT_ONLY, project.memoryScope)
        assertEquals("Use metric units.", g.projects.instructions(project.id))
        assertTrue(File(g.workspace.base, "projects/garden").isDirectory)
    }

    @Test
    fun `a bad folder or a missing name is refused`() = runTest {
        val g = graph()
        val vm = editor(g, null).loaded()
        vm.save { }.join()
        assertNotNull(vm.state.value.error)
        vm.setName("X")
        vm.setFolder("../escape")
        vm.save { }.join()
        assertNotNull(vm.state.value.error)
        assertTrue(g.projects.allOnce().isEmpty())
    }

    @Test
    fun `Allow only applies once confirmed as a widening`() = runTest {
        val g = graph()
        val project = assertNotNull(g.projects.create("Garden"))
        val vm = editor(g, project.id).loaded()
        vm.setToolPolicy("run_command", ToolPolicy.ALLOW).join()
        assertNull(g.projects.policy(project.id).toolPolicies["run_command"])
        vm.setToolPolicy("run_command", ToolPolicy.ALLOW, widen = true).join()
        val policy = g.projects.policy(project.id)
        assertEquals(ToolPolicy.ALLOW, policy.toolPolicies["run_command"])
        assertTrue("run_command" in policy.widened)
        assertEquals(ToolPolicy.ALLOW, vm.state.value.tools.single { it.name == "run_command" }.effective)
        // Narrowing again drops the widening.
        vm.setToolPolicy("run_command", ToolPolicy.DENY).join()
        assertEquals(setOf<String>(), g.projects.policy(project.id).widened)
        assertEquals(ToolPolicy.DENY, vm.state.value.tools.single { it.name == "run_command" }.effective)
    }

    @Test
    fun `the list counts a project's chats and new chats start inside it`() = runTest {
        val g = graph()
        val project = assertNotNull(g.projects.create("Garden"))
        val vm = host.add(ProjectsViewModel(g.projects, g.conversations, g.settingsRepository))
        backgroundScope.launch { vm.rows.collect {} }
        var opened: String? = null
        vm.newChat(project.id) { opened = it }.join()
        val chat = assertNotNull(g.conversations.get(opened!!))
        assertEquals(project.id, chat.projectId)
        g.conversations.appendUserMessage(chat.id, "hello")
        val row = vm.rows.first { r -> r?.singleOrNull()?.chats == 1 }!!.single()
        assertEquals("Garden", row.project.name)
    }
}
