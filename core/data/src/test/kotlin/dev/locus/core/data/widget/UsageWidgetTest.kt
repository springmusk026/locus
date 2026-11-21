package dev.locus.core.data.widget

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.BudgetMeter
import dev.locus.core.data.agent.BudgetPause
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.repository.CostEstimate
import dev.locus.core.data.repository.UsageToday
import dev.locus.core.data.repository.dayStarts
import dev.locus.core.model.ToolRisk
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The usage widget's text (docs/design/android-bridge.md, "The usage widget"). */
class UsageWidgetTest {
    private fun meter(tokens: Long, tokenLimit: Long? = null, cost: Double? = null, costLimit: Double? = null, partial: Boolean = false, unavailable: String? = null) =
        BudgetMeter(tokens, tokenLimit, cost, costLimit, elapsedMs = 61_000, timeLimitMs = 600_000, costUnavailable = unavailable, costPartial = partial)

    // Today

    @Test
    fun `today's spend leads when requests had prices, with the tokens under it`() {
        val model = usageWidgetModel(UsageToday(tokens = 123_400, requests = 12, cost = CostEstimate(usd = 0.4213, priced = 12), pricesOn = true), task = null)
        assertEquals(UsageWidgetModel("$0.42", "Today · 123k tokens"), model)
    }

    @Test
    fun `some requests without a price make the spend a floor`() {
        val model = usageWidgetModel(UsageToday(tokens = 9_500, requests = 3, cost = CostEstimate(usd = 1.5, priced = 2, unpriced = 1), pricesOn = true), task = null)
        assertEquals("$1.50+", model.headline)
        assertEquals("Today · 9.5k tokens", model.detail)
        // Under a cent there's no floor to mark.
        assertEquals("<$0.01", usageWidgetModel(UsageToday(tokens = 10, requests = 2, cost = CostEstimate(usd = 0.004, priced = 1, unpriced = 1)), task = null).headline)
    }

    @Test
    fun `without any price it shows tokens and says why there's no amount`() {
        val unknown = usageWidgetModel(UsageToday(tokens = 1_200_000, requests = 4, cost = CostEstimate(unpriced = 4), pricesOn = true), task = null)
        assertEquals(UsageWidgetModel("1.2M tokens", "Today · cost unknown"), unknown)
        val off = usageWidgetModel(UsageToday(tokens = 950, requests = 1, cost = CostEstimate(unpriced = 1), pricesOn = false), task = null)
        assertEquals(UsageWidgetModel("950 tokens", "Today · prices off"), off)
    }

    @Test
    fun `nothing yet today`() {
        assertEquals(UsageWidgetModel("No usage yet", "Today"), usageWidgetModel(UsageToday(), task = null))
    }

    // The running task's budget

    @Test
    fun `no task line without a running task that has a budget`() {
        assertNull(taskBudgetLine(emptyMap()))
        assertNull(taskBudgetLine(mapOf("a" to RunState(running = true))))
        // A finished task's last meter isn't shown.
        assertNull(taskBudgetLine(mapOf("a" to RunState(running = false, budget = meter(5_000, 50_000)))))
    }

    @Test
    fun `the task line shows tokens and cost against their limits, and never time`() {
        val line = taskBudgetLine(mapOf("a" to RunState(running = true, budget = meter(12_000, 50_000, cost = 0.08, costLimit = 0.5))))
        assertEquals("Task: 12k of 50k tokens · $0.08 of $0.50", line)
        assertEquals("Task: 12k tokens", taskBudgetLine(mapOf("a" to RunState(running = true, budget = meter(12_000)))))
        // A cost limit that can't apply (prices unknown for the chat model) isn't shown as one.
        assertEquals(
            "Task: 12k of 50k tokens · $0.08+",
            taskBudgetLine(mapOf("a" to RunState(running = true, budget = meter(12_000, 50_000, cost = 0.08, costLimit = 0.5, partial = true, unavailable = "No prices")))),
        )
    }

    @Test
    fun `a task paused at its budget comes first and says so`() {
        val states = mapOf(
            "a" to RunState(running = true, budget = meter(1_000, 50_000)),
            "b" to RunState(running = true, budget = meter(50_000, 50_000), budgetPause = BudgetPause(BudgetLimit.TOKENS, "Paused")),
        )
        assertEquals("Task paused: 50k of 50k tokens", taskBudgetLine(states))
    }

    @Test
    fun `the usage widget names no chat and quotes no one, so Lock Locus doesn't change it`() {
        val states = mapOf(
            "Salary talk" to RunState(
                running = true,
                budget = meter(2_000, 10_000),
                approvals = listOf(ApprovalRequest("Salary talk", "c1", "send_email", ToolRisk.NETWORK, "Email boss@example.com", "{}")),
            ),
        )
        val model = usageWidgetModel(UsageToday(tokens = 2_000, requests = 1, cost = CostEstimate(usd = 0.01, priced = 1), pricesOn = true), taskBudgetLine(states))
        val text = listOfNotNull(model.headline, model.detail, model.task).joinToString(" ")
        assertFalse("Salary" in text || "boss" in text || "Email" in text, text)
        // The model has no lock input: with Lock Locus on or off it reads the same (amounts aren't a chat's content).
        assertEquals(model, usageWidgetModel(UsageToday(tokens = 2_000, requests = 1, cost = CostEstimate(usd = 0.01, priced = 1), pricesOn = true), taskBudgetLine(states)))
    }

    // Starting over at midnight

    @Test
    fun `today starts over at local midnight without polling in between`() = runTest {
        val zone = ZoneId.of("Europe/Lisbon")
        val start = LocalDateTime.of(2026, 11, 21, 23, 59, 0).atZone(zone).toInstant().toEpochMilli()
        val days = dayStarts(zone) { start + currentTime }.take(3).toList()
        assertEquals(
            listOf(21, 22, 23).map { LocalDateTime.of(2026, 11, it, 0, 0).atZone(zone).toInstant().toEpochMilli() },
            days,
        )
        // One wake-up per day: a minute to the first midnight, then a day to the next.
        assertEquals(60_000L + 24 * 3_600_000L, currentTime)
    }
}
