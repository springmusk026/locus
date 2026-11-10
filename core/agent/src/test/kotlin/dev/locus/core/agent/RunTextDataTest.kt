package dev.locus.core.agent

import dev.locus.core.model.TaskBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The run's words that the chat translates come with their data, and the data says the same English. */
class RunTextDataTest {
    @Test
    fun budgetReachedSaysTheNotice() {
        val budget = RunBudget(TaskBudget(tokens = 200_000, costUsd = 2.0, minutes = 30))
        assertEquals("Paused: reached the 200k-token budget for this task.", budget.pauseMessage(BudgetLimit.TOKENS))
        assertEquals("Stopped: reached the $2.00 cost budget for this task.", budget.stopMessage(BudgetLimit.COST))
        assertEquals("30-minute", budget.describe(BudgetLimit.TIME))
        BudgetLimit.entries.forEach { limit ->
            assertEquals(budget.pauseMessage(limit), budget.reached(limit, paused = true).english)
            assertEquals(budget.stopMessage(limit), budget.reached(limit, paused = false).english)
        }
    }

    @Test
    fun overflowSaysTheRunsMessage() {
        assertEquals(
            "This task no longer fits in gpt-5's context window. Start a new chat for the rest (the agent there can read this one with " +
                "chat_history_read), or switch to a model with a larger window.\n\nThe provider said: too long",
            ContextOverflowText("gpt-5", summarised = false, providerSaid = "too long").english,
        )
    }

    @Test
    fun outsideSourcesKeepTheirLabels() {
        assertEquals("web pages", TaskReceipts.sourceLabel("browser_open"))
        assertEquals("helpers' reports", TaskReceipts.sourceLabel("a helper's report"))
        assertEquals("odd_tool", TaskReceipts.sourceLabel("odd_tool"))
        TaskReceipts.OutsideSource.entries.forEach { assertEquals(it, TaskReceipts.OutsideSource.ofLabel(it.label)) }
        assertNull(TaskReceipts.OutsideSource.ofLabel("odd_tool"))
    }
}
