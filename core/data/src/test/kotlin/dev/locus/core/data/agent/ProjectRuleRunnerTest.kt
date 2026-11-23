package dev.locus.core.data.agent

import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProjectRule
import dev.locus.core.model.RuleTrigger
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Running a project rule's command (docs/design/rules.md, "Running a command"), on the host instead of proot. */
class ProjectRuleRunnerTest {
    private val lint = ProjectRule("l", "lint", RuleTrigger.AFTER_EDIT, "*.py", "printf '%s|' \$LOCUS_RULE_FILES; echo; exit 3")

    @Test
    fun `only active rules of the project, and Linux must be ready`() = runTest {
        val g = TestGraph(backgroundScope, AppSettings())
        val rules = mapOf("p" to listOf(lint, lint.copy(id = "off", enabled = false), lint.copy(id = "s", suspended = true)), "other" to listOf(lint.copy(id = "o")))
        val runner = ProjectRuleRunner("p", { AppSettings(projectRules = rules) }, g.linux)
        assertEquals(listOf("l"), runner.rules().map { it.id })
        assertFalse(runner.canRun())
        g.linux.runOnHost { command, _ -> ProcessBuilder("sh", "-c", command) }
        assertTrue(runner.canRun())
    }

    @Test
    fun `the command gets the files, and its exit code and output come back`() = runTest {
        val g = TestGraph(backgroundScope, AppSettings())
        g.linux.runOnHost { command, _ -> ProcessBuilder("sh", "-c", command) }
        val runner = ProjectRuleRunner("p", { AppSettings() }, g.linux)
        val out = runner.run(lint, listOf("a.py", "it's here.py"))
        assertEquals(3, out.exitCode)
        assertTrue(out.output.startsWith("a.py|it's|here.py|"), out.output)
    }

    @Test
    fun `a command past its time limit is stopped and reported as timed out`() = runTest {
        val g = TestGraph(backgroundScope, AppSettings())
        g.linux.runOnHost { command, _ -> ProcessBuilder("sh", "-c", command) }
        val runner = ProjectRuleRunner("p", { AppSettings() }, g.linux)
        val out = runner.run(ProjectRule("s", "slow", RuleTrigger.BEFORE_FINISH, command = "sleep 30", timeoutSeconds = ProjectRule.MIN_TIMEOUT), emptyList())
        assertNull(out.exitCode)
        assertFalse(out.passed)
    }
}
