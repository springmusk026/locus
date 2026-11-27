package dev.locus.core.data.widget

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.BudgetMeter
import dev.locus.core.data.agent.BudgetPause
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.repository.CostEstimate
import dev.locus.core.data.repository.UsageDay
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
import kotlin.test.assertTrue

/** The usage widget's text (docs/design/android-bridge.md, "The usage widget"). */
class UsageWidgetTest {
    private fun meter(tokens: Long, tokenLimit: Long? = null, cost: Double? = null, costLimit: Double? = null, partial: Boolean = false, unavailable: String? = null) =
        BudgetMeter(tokens, tokenLimit, cost, costLimit, elapsedMs = 61_000, timeLimitMs = 600_000, costUnavailable = unavailable, costPartial = partial)

    // Today (worded by the widget: "$0.42", "Today · cost unknown"…, see the app's WidgetWordsTest)

    @Test
    fun `today's spend leads when requests had prices, with the tokens under it`() {
        val model = usageWidgetModel(UsageToday(tokens = 123_400, requests = 12, cost = CostEstimate(usd = 0.4213, priced = 12), pricesOn = true), task = null)
        assertEquals(UsageFigure.Spend(0.4213), model.figure)
        assertEquals(UsageNote.TODAY, model.note)
        assertEquals(123_400L, model.tokens)
        assertEquals(12, model.requests)
    }

    @Test
    fun `some requests without a price make the spend a floor`() {
        val model = usageWidgetModel(UsageToday(tokens = 9_500, requests = 3, cost = CostEstimate(usd = 1.5, priced = 2, unpriced = 1), pricesOn = true), task = null)
        assertEquals(UsageFigure.Spend(1.5, floor = true), model.figure)
        assertEquals(9_500L, model.tokens)
        // Under a cent there's no floor to mark ("<$0.01" already says "at most").
        assertEquals(UsageFigure.Spend(0.004), usageWidgetModel(UsageToday(tokens = 10, requests = 2, cost = CostEstimate(usd = 0.004, priced = 1, unpriced = 1)), task = null).figure)
    }

    @Test
    fun `without any price it shows tokens and says why there's no amount`() {
        val unknown = usageWidgetModel(UsageToday(tokens = 1_200_000, requests = 4, cost = CostEstimate(unpriced = 4), pricesOn = true), task = null)
        assertEquals(UsageFigure.Tokens(1_200_000), unknown.figure)
        assertEquals(UsageNote.COST_UNKNOWN, unknown.note)
        // The figure already is the tokens: no second tokens line.
        assertNull(unknown.tokens)
        val off = usageWidgetModel(UsageToday(tokens = 950, requests = 1, cost = CostEstimate(unpriced = 1), pricesOn = false), task = null)
        assertEquals(UsageFigure.Tokens(950), off.figure)
        assertEquals(UsageNote.PRICES_OFF, off.note)
    }

    @Test
    fun `nothing yet today`() {
        val model = usageWidgetModel(UsageToday(), task = null)
        assertEquals(UsageFigure.Nothing, model.figure)
        assertEquals(UsageNote.TODAY, model.note)
        assertTrue(model.bars.isEmpty())
    }

    // The week

    private fun day(tokens: Long, usd: Double? = null, unpriced: Int = 0) =
        UsageDay(tokens, requests = if (tokens > 0) 1 + unpriced else 0, cost = if (usd != null) CostEstimate(usd, priced = 1, unpriced = unpriced) else CostEstimate(unpriced = if (tokens > 0) 1 else 0))

    @Test
    fun `the week's bars are each day against the busiest, in dollars when every day had prices`() {
        val week = listOf(day(0), day(100, 1.0), day(0), day(400, 4.0), day(0), day(200, 2.0), day(50, 0.5))
        val (bars, total) = weekBars(week, CostEstimate(7.5, priced = 4))
        assertEquals(listOf(0f, 0.25f, 0f, 1f, 0f, 0.5f, 0.125f), bars)
        assertEquals(UsageFigure.Spend(7.5), total)
    }

    @Test
    fun `a day without prices makes the week's bars tokens, never $0`() {
        val week = listOf(day(100, 1.0), day(300), day(0), day(0), day(0), day(0), day(150, 0.2))
        val (bars, total) = weekBars(week, CostEstimate(1.2, priced = 2, unpriced = 1))
        assertEquals(listOf(1f / 3, 1f, 0f, 0f, 0f, 0f, 0.5f), bars)
        assertEquals(UsageFigure.Tokens(550), total)
        // A partial day is still priced: its amount is a floor, and the total says so.
        val partial = weekBars(listOf(day(100, 1.0, unpriced = 1), day(100, 1.0)), CostEstimate(2.0, priced = 2, unpriced = 1))
        assertEquals(UsageFigure.Spend(2.0, floor = true), partial.second)
    }

    @Test
    fun `a week without use draws no bars`() {
        assertEquals(emptyList<Float>() to UsageFigure.Nothing, weekBars(List(7) { day(0) }, CostEstimate()))
        assertEquals(emptyList<Float>() to UsageFigure.Nothing, weekBars(emptyList(), CostEstimate()))
    }

    // The running task's budget

    @Test
    fun `no task budget without a running task that has a budget`() {
        assertNull(taskBudget(emptyMap()))
        assertNull(taskBudget(mapOf("a" to RunState(running = true))))
        // A finished task's last meter isn't shown.
        assertNull(taskBudget(mapOf("a" to RunState(running = false, budget = meter(5_000, 50_000)))))
    }

    @Test
    fun `the task budget has tokens and cost against their limits, and never time`() {
        assertEquals(
            TaskBudgetFigures(paused = false, tokens = 12_000, tokenLimit = 50_000, usd = 0.08, usdLimit = 0.5),
            taskBudget(mapOf("a" to RunState(running = true, budget = meter(12_000, 50_000, cost = 0.08, costLimit = 0.5)))),
        )
        assertEquals(TaskBudgetFigures(paused = false, tokens = 12_000), taskBudget(mapOf("a" to RunState(running = true, budget = meter(12_000)))))
        // A cost limit that can't apply (prices unknown for the chat model) isn't shown as one.
        assertEquals(
            TaskBudgetFigures(paused = false, tokens = 12_000, tokenLimit = 50_000, usd = 0.08, usdFloor = true),
            taskBudget(mapOf("a" to RunState(running = true, budget = meter(12_000, 50_000, cost = 0.08, costLimit = 0.5, partial = true, unavailable = "No prices")))),
        )
    }

    @Test
    fun `a task paused at its budget comes first and says so`() {
        val states = mapOf(
            "a" to RunState(running = true, budget = meter(1_000, 50_000)),
            "b" to RunState(running = true, budget = meter(50_000, 50_000), budgetPause = BudgetPause(BudgetLimit.TOKENS, "Paused")),
        )
        assertEquals(TaskBudgetFigures(paused = true, tokens = 50_000, tokenLimit = 50_000), taskBudget(states))
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
        val model = usageWidgetModel(UsageToday(tokens = 2_000, requests = 1, cost = CostEstimate(usd = 0.01, priced = 1), pricesOn = true), taskBudget(states))
        val text = model.toString()
        assertFalse("Salary" in text || "boss" in text || "Email" in text, text)
        // The model has no lock input: with Lock Locus on or off it reads the same (amounts aren't a chat's content).
        assertEquals(model, usageWidgetModel(UsageToday(tokens = 2_000, requests = 1, cost = CostEstimate(usd = 0.01, priced = 1), pricesOn = true), taskBudget(states)))
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
