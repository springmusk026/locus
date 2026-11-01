package dev.locus.bench

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsTest {
    private fun close(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test
    fun `pass at k matches the closed form`() {
        // 1 - C(n-c,k)/C(n,k)
        close(0.5, Metrics.passAtK(4, 2, 1))
        close(1 - 1.0 / 6, Metrics.passAtK(4, 2, 2))
        close(1.0, Metrics.passAtK(4, 2, 3))
        close(0.0, Metrics.passAtK(5, 0, 3))
        close(1.0, Metrics.passAtK(5, 5, 1))
        // n=10, c=3, k=5: 1 - C(7,5)/C(10,5) = 1 - 21/252
        close(1 - 21.0 / 252, Metrics.passAtK(10, 3, 5))
    }

    @Test
    fun `pass hat k is the chance all k pass`() {
        close(0.5, Metrics.passHatK(4, 2, 1))
        // C(2,2)/C(4,2)
        close(1.0 / 6, Metrics.passHatK(4, 2, 2))
        close(0.0, Metrics.passHatK(4, 2, 3))
        close(1.0, Metrics.passHatK(3, 3, 3))
        // C(4,3)/C(5,3)
        close(4.0 / 10, Metrics.passHatK(5, 4, 3))
    }

    @Test
    fun `pass hat k never exceeds pass at 1, which never exceeds pass at k`() {
        for (n in 1..8) for (c in 0..n) for (k in 1..n) {
            val hat = Metrics.passHatK(n, c, k)
            val one = Metrics.passAtK(n, c, 1)
            val at = Metrics.passAtK(n, c, k)
            assertTrue(hat <= one + 1e-12 && one <= at + 1e-12, "n=$n c=$c k=$k: $hat $one $at")
        }
    }

    @Test
    fun `bad arguments are refused`() {
        assertFailsWith<IllegalArgumentException> { Metrics.passAtK(3, 4, 1) }
        assertFailsWith<IllegalArgumentException> { Metrics.passHatK(3, 1, 4) }
        assertFailsWith<IllegalArgumentException> { Metrics.passAtK(0, 0, 1) }
    }

    private fun trial(task: String, category: String, passed: Boolean, cost: Double? = 0.01, security: Boolean? = null, attempted: Boolean? = null) = TrialResult(
        task = task, category = category, trial = 1, passed = passed, utility = passed, security = security, attackAttempted = attempted,
        outcome = "completed", failure = if (passed) null else "check failed: x", steps = 4, inputTokens = 1000, outputTokens = 100,
        cacheReadTokens = 0, budgetTokens = 1100, costUsd = cost, wallMs = 2000, toolCalls = emptyMap(), approvals = 0,
        taintPrompts = 0, deniedByUser = 0, questionsAsked = 0, checks = emptyList(),
    )

    @Test
    fun `summaries average over tasks, not trials`() {
        val trials = listOf(
            trial("c/a", "c", true), trial("c/a", "c", true), trial("c/a", "c", true),
            trial("c/b", "c", false), trial("c/b", "c", true), trial("c/b", "c", false),
        )
        val (categories, tasks) = Metrics.summarize(trials, k = 3)
        val c = categories.single()
        close((1.0 + 1.0 / 3) / 2, c.passAt1)
        close((1.0 + 0.0) / 2, c.passHatK)
        close(1.0, c.passAtK)
        close(0.06, c.totalCostUsd!!)
        assertEquals(listOf("check failed: x"), tasks.single { it.task == "c/b" }.failures)
    }

    @Test
    fun `an unknown price makes the total unknown rather than smaller`() {
        val (categories, _) = Metrics.summarize(listOf(trial("c/a", "c", true), trial("c/a", "c", true, cost = null)), k = 2)
        assertNull(categories.single().totalCostUsd)
    }

    @Test
    fun `injection summaries report attack success and attempts`() {
        val trials = listOf(
            trial("i/a", "injection", true, security = true, attempted = true),
            trial("i/a", "injection", false, security = false, attempted = true),
            trial("i/b", "injection", true, security = true, attempted = false),
            trial("i/b", "injection", true, security = true, attempted = false),
        )
        val c = Metrics.summarize(trials, k = 2).first.single()
        close(0.25, c.attackSuccessRate!!)
        close(0.5, c.attackAttemptRate!!)
    }
}
