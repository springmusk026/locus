package dev.locus.feature.chat

import dev.locus.core.agent.tools.agentControlTools
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.ProjectRule
import dev.locus.core.model.RuleNote
import dev.locus.core.model.RuleProblem
import dev.locus.core.model.RuleTrigger
import dev.locus.core.model.Role
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import dev.locus.feature.chat.Fx.msg
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Project → Rules (docs/design/rules.md): only this screen writes rules, sealed with the permissions. */
class ProjectRulesViewModelTest {
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

    @Test
    fun `a rule is added, edited, switched off and deleted`() = runTest {
        val g = graph()
        val project = assertNotNull(g.projects.create("Garden"))
        val vm = host.add(ProjectRulesViewModel(project.id, g.projects, g.linux))
        vm.state.first { !it.loading }
        assertFalse(vm.state.value.linuxReady)

        vm.add()
        assertEquals(RuleProblem.NAME, vm.state.value.editing?.problem)
        vm.change { copy(name = "ruff", pattern = "*.py") }
        assertEquals(RuleProblem.COMMAND, vm.state.value.editing?.problem)
        vm.change { copy(command = "ruff check .", timeout = "2") }
        assertEquals(RuleProblem.TIMEOUT, vm.state.value.editing?.problem)
        vm.change { copy(timeout = "90") }
        assertNull(vm.state.value.editing?.problem)
        vm.save().join()
        val saved = g.settingsRepository.current().projectRules.getValue(project.id).single()
        assertEquals(listOf("ruff", "*.py", "ruff check .", "90"), listOf(saved.name, saved.pattern, saved.command, saved.timeoutSeconds.toString()))
        assertEquals(RuleTrigger.AFTER_EDIT, saved.trigger)
        assertNull(vm.state.value.editing)

        // A block rule keeps no command; a command rule keeps no message.
        vm.add()
        vm.change { copy(name = "no push", trigger = RuleTrigger.BEFORE_COMMAND, command = "ignored", pattern = "git push*main") }
        assertEquals(RuleProblem.MESSAGE, vm.state.value.editing?.problem)
        vm.change { copy(message = "never push to main") }
        vm.save().join()
        val block = g.settingsRepository.current().projectRules.getValue(project.id)[1]
        assertEquals("", block.command)
        assertEquals("never push to main", block.message)

        vm.setEnabled(saved.id, false).join()
        assertFalse(g.settingsRepository.current().projectRules.getValue(project.id).first().enabled)

        vm.edit(block)
        vm.change { copy(message = "use a pull request") }
        vm.save().join()
        assertEquals("use a pull request", g.settingsRepository.current().projectRules.getValue(project.id)[1].message)

        vm.delete(saved.id).join()
        assertEquals(listOf("no push"), g.settingsRepository.current().projectRules.getValue(project.id).map { it.name })
        vm.state.first { it.rules.size == 1 }
    }

    @Test
    fun `a suspended rule is resumed only by opening and saving it`() = runTest {
        val g = graph()
        val project = assertNotNull(g.projects.create("Garden"))
        val rule = ProjectRule("r", "ruff", RuleTrigger.AFTER_EDIT, "*.py", "ruff check .", suspended = true)
        g.settingsRepository.setProjectRules(project.id, listOf(rule))
        val vm = host.add(ProjectRulesViewModel(project.id, g.projects, g.linux))
        vm.state.first { !it.loading && it.rules.size == 1 }
        assertEquals(1, vm.state.value.suspended)
        // The switch doesn't resume it.
        vm.setEnabled("r", true).join()
        assertTrue(g.settingsRepository.current().projectRules.getValue(project.id).single().suspended)
        vm.edit(vm.state.value.rules.single())
        assertTrue(vm.state.value.editing!!.suspended)
        vm.save().join()
        assertFalse(g.settingsRepository.current().projectRules.getValue(project.id).single().suspended)
    }

    @Test
    fun `deleting a project drops its rules`() = runTest {
        val g = graph()
        val project = assertNotNull(g.projects.create("Garden"))
        g.settingsRepository.setProjectRules(project.id, listOf(ProjectRule("r", "ruff", RuleTrigger.AFTER_EDIT, command = "ruff check .")))
        g.projects.delete(project.id)
        assertTrue(project.id !in g.settingsRepository.current().projectRules)
    }

    @Test
    fun `a rule's note shows as a step of its turn, with its output`() {
        val rule = ProjectRule("r", "ruff", RuleTrigger.AFTER_EDIT, "*.py", "ruff check .")
        val run = dev.locus.core.agent.RuleRun(rule, listOf("a.py"), dev.locus.core.agent.RuleOutcome(1, "a.py:1:1: F401 unused import", 300), RuleNote.header(rule, 1, 300, listOf("a.py")))
        val items = transcriptItems(
            listOf(
                msg("u", Role.USER, Part.Text("tidy a.py")),
                msg("a", Role.ASSISTANT, Fx.call("c1", "edit_file", "path" to "a.py")),
                msg("t", Role.TOOL, Fx.result("c1", "edit_file", "Edited a.py")),
                msg("n", Role.USER, *run.parts.toTypedArray()),
                msg("a2", Role.ASSISTANT, Part.Text("Removed the unused import.")),
            ),
        )
        // The note doesn't start a turn of its own: one user message.
        assertEquals(1, items.count { it is ChatItem.User })
        val note = items.filterIsInstance<ChatItem.Note>().single()
        assertEquals("ruff", note.rule?.name)
        assertEquals("a.py:1:1: F401 unused import", note.ruleOutput)
        assertEquals("Rule “ruff” failed after an edit (exit code 1)", note.text)
        assertTrue(ModeNote.isAgentNote(run.let { msg("x", Role.USER, *it.parts.toTypedArray()) }))
        assertTrue(agentControlTools().none { "rule" in it.spec.name })
    }
}
