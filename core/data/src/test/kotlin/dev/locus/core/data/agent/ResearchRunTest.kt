package dev.locus.core.data.agent

import dev.locus.core.agent.Taint
import dev.locus.core.agent.workflow.ResearchReport
import dev.locus.core.agent.workflow.ResearchWorkflow
import dev.locus.core.agent.workflow.WorkflowRender
import dev.locus.core.data.repository.ChatOnlyRule
import dev.locus.core.data.repository.WorkflowRepository
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The built-in research workflow end to end (docs/design/deep-research.md), on the real runner, helper
 * engine and workspace with a scripted model: plan → a researcher helper's findings → verify → the
 * report file, written with citations and an unverified section, its budget, taint and notification.
 */
class ResearchRunTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
        toolPolicies = mapOf("write_file" to ToolPolicy.ALLOW, "agent_spawn" to ToolPolicy.ALLOW, "send_file" to ToolPolicy.ALLOW),
    )

    private fun TestScope.graph() = TestGraph(backgroundScope, settings)

    private fun repository(g: TestGraph, chatOnly: ChatOnlyRule = ChatOnlyRule.Never) = WorkflowRepository(
        g.home, g.helper, g.conversations, g.receipts, g.changes, g.settingsRepository, g.runner, g.tasks, g.io, chatOnly,
    )

    private val report = """
        # Heat pumps in cold climates

        2026-11-21

        ## Summary

        Cold-climate heat pumps keep most of their capacity down to about -15 °C [1], and a Nordic field trial measured a seasonal COP of 2.8 [2].

        ## How do they perform below freezing?

        Rated capacity at -15 °C is typically 70–100% of nominal [1].

        ## Unverified claims

        - Full capacity at -30 °C: only the maker's own page says so.

        ## Sources

        1. Cold-climate heat pump specification — https://example.org/ccashp-spec (accessed 2026-11-21)
        2. Nordic heat pump field trial — https://example.org/nordic-trial.pdf (accessed 2026-11-21)
    """.trimIndent()

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> if (v is Boolean) put(k, v) else put(k, v.toString()) }
    }.toString()

    @Test
    fun `a research run plans, sends a researcher, verifies and writes a cited report`() = runTest {
        val g = graph()
        val repo = repository(g)
        val notified = kotlinx.coroutines.CompletableDeferred<Pair<String, String>>()
        g.runner.onResearchFinished = { _, title, text -> notified.complete(title to text) }
        val research = WorkflowRepository.BUILT_IN.single()
        assertTrue(research.builtIn)
        repo.refresh()
        assertEquals(ResearchWorkflow.ID, repo.all.value.first().workflow.id)

        val rendered = repo.render(research, mapOf("question" to "Do heat pumps work in cold climates?", "depth" to "quick"))
        val path = rendered.plan.inputs["report_path"]!!
        assertEquals("research/heat-pumps-work-cold-climates-${LocalDate.now()}.md", path)
        assertEquals(ResearchWorkflow.Depth.QUICK.defaultBudget, rendered.plan.budget)

        g.script(
            // Research: one researcher helper (quick), in the foreground.
            ScriptedProvider.toolCall("s1", "agent_spawn", args("type" to "researcher", "task" to "Find how cold-climate heat pumps perform below freezing. Report numbered findings with URL and quote.")),
            // The helper's own request: its findings, with sources and quotes.
            ScriptedProvider.text("1. Capacity holds to -15 °C. https://example.org/ccashp-spec \"70–100% of nominal at -15 °C\"\n2. Seasonal COP 2.8. https://example.org/nordic-trial.pdf \"average seasonal COP of 2.8\""),
            // Report: the file, then the file card.
            ScriptedProvider.toolCall("w1", "write_file", args("path" to path, "content" to report)),
            ScriptedProvider.toolCall("f1", "send_file", args("path" to path, "note" to "2 sources · 1 unverified")),
            ScriptedProvider.text("They do, down to about -15 °C.\nCheck: Every key claim was checked against its source — passed"),
        )
        val budgets = mutableListOf<Long?>()
        val watcher = backgroundScope.launch { g.runner.states.collect { s -> s.values.mapNotNull { it.budget?.tokenLimit }.forEach { budgets += it } } }
        val chat = repo.runInNewChat(rendered, projectId = null)
        runCurrent()
        g.conversations.observeMessages(chat).first { m -> m.any { it.role == Role.ASSISTANT && "Check:" in it.text } }
        g.runner.states.first { it[chat]?.running == false }
        watcher.cancel()

        // The report is in the workspace, well formed.
        val file = File(g.workspace.base, path)
        assertTrue(file.exists(), "no report at $path")
        val check = ResearchReport.check(file.readText())
        assertTrue(check.ok, check.problems.toString())
        assertEquals(2, check.sources)
        assertEquals(1, check.unverified)

        val history = g.conversations.history(chat)
        // The visible message is the question; the plan rides on it and is what the run read back.
        assertEquals("Do heat pumps work in cold climates?", history.first().userText)
        assertEquals(ResearchWorkflow.ID, WorkflowRender.active(history)?.id)
        // The helper ran and its report came back as a tool result.
        val spawn = history.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().single { it.callId == "s1" }
        assertFalse(spawn.isError, spawn.content)
        assertTrue("COP 2.8" in spawn.content, spawn.content)
        // The run is tainted from the helper's report on: later outward calls would ask.
        assertTrue("agent_spawn" in Taint.sourcesIn(history), Taint.sourcesIn(history).toString())
        // The file card.
        assertFalse(history.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().single { it.callId == "f1" }.isError)
        // It ran with the plan's budget, not the chat's (none here).
        assertTrue(budgets.isNotEmpty() && budgets.all { it == ResearchWorkflow.Depth.QUICK.defaultTokens }, budgets.toString())
        // Finished: the notification hook heard it (posted only when Locus isn't on screen).
        // It's sent just after the run's slot is freed.
        assertEquals("Research finished" to "Do heat pumps work in cold climates?", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(10_000) { notified.await() } })
    }

    @Test
    fun `a model without tool calling can't run it, and the sheet is told why`() = runTest {
        val g = graph()
        assertFalse(repository(g).lacksTools(null))
        val chatOnly = ChatOnlyRule { _, _ -> true }
        assertTrue(repository(g, chatOnly).lacksTools(null))
        g.chatOnlyRule = chatOnly
        val chat = g.conversations.create(ScriptedProvider.config.id)
        assertTrue(chat.chatOnly)
        assertTrue(repository(g, chatOnly).lacksTools(chat.id))
    }

    @Test
    fun `the built-in workflow can't be removed, and a copy gets its own id`() = runTest {
        val g = graph()
        val repo = repository(g)
        val research = WorkflowRepository.BUILT_IN.single()
        val failure = runCatching { repo.delete(research) }.exceptionOrNull()
        assertNotNull(failure)
        val copy = repo.save(research.workflow.copy(name = "Research (copy)"), projectId = null)
        assertEquals("research-2", copy.id)
        assertEquals(2, repo.all.value.size)
        assertFalse(repo.all.value.last().builtIn)
    }

    @Test
    fun `a scheduled research digest is named after its question`() = runTest {
        val g = graph()
        val repo = repository(g)
        repo.schedule(WorkflowRepository.BUILT_IN.single(), mapOf("question" to "Weekly: what's new in heat pumps?"), intervalMinutes = 24 * 60)
        val task = g.database.scheduledTaskDao.observeAll().first().single()
        assertEquals("Weekly: what's new in heat pumps?", task.title)
        val (text, note) = WorkflowRender.split(task.prompt)
        assertEquals("Weekly: what's new in heat pumps?", text)
        // Read-only, so the plan tells it where the report goes when writing is refused.
        assertTrue("If write_file is refused" in note.orEmpty())
        assertEquals(ResearchWorkflow.Depth.STANDARD.defaultBudget, WorkflowRender.planInNote(note!!)?.budget)
    }
}
