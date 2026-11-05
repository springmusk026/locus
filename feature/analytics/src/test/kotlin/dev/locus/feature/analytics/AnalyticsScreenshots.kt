package dev.locus.feature.analytics

import dev.locus.core.data.repository.AnalyticsPeriod
import dev.locus.core.data.repository.AnalyticsReport
import dev.locus.core.data.repository.ChatUsage
import dev.locus.core.data.repository.CostEstimate
import dev.locus.core.data.repository.CostReport
import dev.locus.core.data.repository.ModelCost
import dev.locus.core.data.repository.ModelUsage
import dev.locus.core.data.repository.SourceCost
import dev.locus.core.data.repository.SourceUsage
import dev.locus.core.data.repository.TaskRuns
import dev.locus.core.data.repository.TokenTotals
import dev.locus.core.data.repository.ToolStats
import dev.locus.core.data.repository.ToolTiming
import dev.locus.core.data.repository.UsageBucket
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.UsageSource
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.UtcRule
import org.junit.Test

/** Insights: loading, empty, populated with and without prices (docs/design/design-system.md, "Review gate"). */
class AnalyticsScreenshots : ScreenshotTest() {

    private val day = 24 * 3_600_000L
    @get:org.junit.Rule
    val utc = UtcRule()

    /** A fixed clock: dates and clock times on the screen don't change with the day the test runs. */
    private val now = 1_793_000_000_000L
    private val start = now - 7 * day

    private val buckets = listOf(182_000L, 64_000L, 0L, 410_000L, 233_000L, 96_000L, 351_000L).mapIndexed { i, t ->
        UsageBucket(start + i * day, TokenTotals(input = t * 4 / 5, output = t / 5, cacheRead = t / 2), requests = (t / 9_000).toInt(), toolCalls = (t / 6_000).toInt(), toolFailures = if (i == 3) 4 else 0)
    }

    private val report = AnalyticsReport(
        period = AnalyticsPeriod.Week,
        from = start,
        to = now,
        bucketDays = 1,
        buckets = buckets,
        tokens = TokenTotals(input = 1_065_000, output = 271_000, cacheRead = 668_000, cacheWrite = 120_000, reasoning = 84_000),
        requests = 148,
        replyMedianMs = 6_400,
        replyP95Ms = 41_000,
        firstTokenMedianMs = 1_100,
        firstTokenP95Ms = 3_900,
        models = listOf(
            ModelUsage("anthropic", "Anthropic", "https://api.anthropic.com", "claude-sonnet-4-5", onDevice = false, format = ApiFormat.ANTHROPIC_MESSAGES, tokens = 912_000, requests = 96),
            ModelUsage("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat-v3.1", onDevice = false, format = ApiFormat.OPENAI_CHAT, tokens = 301_000, requests = 38),
            ModelUsage("local", "On this phone", "", "gemma-3n-e2b", onDevice = true, format = ApiFormat.ON_DEVICE, tokens = 123_000, requests = 14),
        ),
        sources = listOf(
            SourceUsage(UsageSource.CHAT, 1_104_000, 112),
            SourceUsage(UsageSource.HELPER, 141_000, 9),
            SourceUsage(UsageSource.REVIEWER, 52_000, 17),
            SourceUsage(UsageSource.TITLE, 39_000, 10),
        ),
        tools = ToolStats(
            calls = 412, failures = 19, autoAllowed = 318, userApproved = 71, denied = 6, invalid = 2,
            slowest = listOf(
                ToolTiming("run_command", 88, 7, 8_200, 46_000),
                ToolTiming("browser_open", 41, 3, 5_100, 19_000),
                ToolTiming("web_search", 63, 1, 2_300, 6_800),
            ),
        ),
        chats = listOf(
            ChatUsage("c1", "Refactor the invoice parser to streaming", 486_000, 41, 133),
            ChatUsage("c2", "Plan the Lisbon trip in March", 212_000, 22, 48),
            ChatUsage("c3", "Why is the Gradle build slow?", 167_000, 18, 61),
        ),
        tasks = listOf(
            TaskRuns("Morning news digest", 7, now - 6 * 3_600_000L, enabled = true),
            TaskRuns("Back up photos to the NAS", 2, now - 3 * day, enabled = false),
        ),
        taskRuns = 9,
        cost = CostReport(
            pricesOn = true,
            today = CostEstimate(0.84, priced = 21),
            week = CostEstimate(6.12, priced = 134, unpriced = 14),
            month = CostEstimate(19.40, priced = 512, unpriced = 30),
            period = CostEstimate(6.12, priced = 134, unpriced = 14),
            buckets = buckets.map { CostEstimate(it.tokens.total * 0.0000046, priced = it.requests) },
            models = listOf(
                ModelCost("anthropic", "Anthropic", "claude-sonnet-4-5", CostEstimate(5.48, priced = 96)),
                ModelCost("openrouter", "OpenRouter", "deepseek/deepseek-chat-v3.1", CostEstimate(0.64, priced = 38)),
                ModelCost("local", "On this phone", "gemma-3n-e2b", CostEstimate(0.0, priced = 14)),
            ),
            sources = listOf(
                SourceCost(UsageSource.CHAT, CostEstimate(5.21, priced = 112)),
                SourceCost(UsageSource.HELPER, CostEstimate(0.52, priced = 9)),
                SourceCost(UsageSource.REVIEWER, CostEstimate(0.39, priced = 17)),
            ),
        ),
    )

    private val empty = report.copy(buckets = report.buckets.map { UsageBucket(it.start) }, tokens = TokenTotals(), requests = 0, replyMedianMs = null, replyP95Ms = null,
        models = emptyList(), sources = emptyList(), tools = ToolStats(), chats = emptyList(), tasks = emptyList(), taskRuns = 0, cost = CostReport())

    private fun screen(state: AnalyticsUiState) = @androidx.compose.runtime.Composable {
        AnalyticsScreen(state, onBack = {}, onPeriod = {}, onOpenConversation = {})
    }

    @Test fun populated() = snap("analytics", heightDp = 2600, content = screen(AnalyticsUiState(report = report)))

    @Test
    fun pricesOff() = snap(
        "analytics-prices-off",
        heightDp = 1400,
        content = screen(AnalyticsUiState(report = report.copy(cost = CostReport(period = CostEstimate(unpriced = 148), week = CostEstimate(unpriced = 148)), models = report.models.take(1)))),
    )

    @Test
    fun pricesFetching() = snap(
        "analytics-prices-fetching",
        heightDp = 1000,
        content = screen(AnalyticsUiState(report = report.copy(cost = CostReport()), prices = PricesSetup.Fetching)),
    )

    @Test
    fun pricesFailed() = snap(
        "analytics-prices-failed",
        heightDp = 1000,
        content = screen(AnalyticsUiState(report = report.copy(cost = CostReport(pricesOn = true, period = CostEstimate(unpriced = 148))), prices = PricesSetup.Failed)),
    )

    @Test
    fun allTime() = snap(
        "analytics-all-time",
        heightDp = 1000,
        content = screen(AnalyticsUiState(period = AnalyticsPeriod.All, report = report.copy(period = AnalyticsPeriod.All, bucketDays = 7))),
    )

    @Test fun empty() = snap("analytics-empty", content = screen(AnalyticsUiState(report = empty)))

    @Test fun loading() = snap("analytics-loading", content = screen(AnalyticsUiState()))
}
