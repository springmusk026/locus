package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskBudgetTest {
    @Test
    fun `budget tokens count fresh input and output in full and cache reads at a tenth`() {
        // input is the whole prompt: 90k of it read from the cache.
        assertEquals(10_000L + 2_000 + 9_000, TaskBudget.budgetTokens(TokenUsage(input = 100_000, output = 2_000, cacheRead = 90_000)))
        // Cache writes are new input and count in full.
        assertEquals(52_000L, TaskBudget.budgetTokens(TokenUsage(input = 50_000, output = 2_000, cacheWrite = 50_000)))
        assertEquals(1_500L, TaskBudget.budgetTokens(TokenUsage(input = 1_000, output = 500)))
        // A provider reporting more cached than total never goes negative.
        assertEquals(1L, TaskBudget.budgetTokens(TokenUsage(input = 0, output = 0, cacheRead = 5)))
    }

    @Test
    fun `a chat's own budget round-trips through its restriction set beside tool switches`() {
        val tools = setOf("group:web", "connector:github")
        assertNull(TaskBudget.overrideIn(tools))
        val own = TaskBudget(tokens = 200_000, costUsd = 2.5, minutes = 30)
        val stored = TaskBudget.withOverride(tools, own)
        assertTrue(tools.all { it in stored })
        assertEquals(own, TaskBudget.overrideIn(stored))
        // Explicitly off is not the same as "use the default".
        val off = TaskBudget.withOverride(stored, TaskBudget.OFF)
        assertEquals(TaskBudget.OFF, TaskBudget.overrideIn(off))
        assertEquals(TaskBudget.OFF, TaskBudget.effective(own, off))
        // Back to the default leaves only the tool switches.
        assertEquals(tools, TaskBudget.withOverride(off, null))
        assertEquals(own, TaskBudget.effective(own, tools))
        assertEquals(TaskBudget(tokens = 100_000), TaskBudget.overrideIn(TaskBudget.withOverride(emptySet(), TaskBudget(tokens = 100_000))))
    }

    @Test
    fun `settings saved before budgets existed load with budgets off`() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), """{"providers":[],"maxStepsPerTurn":40}""")
        assertTrue(old.taskBudget.isOff)
    }
}
