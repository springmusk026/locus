package dev.locus.core.data.repository

import dev.locus.core.database.AuditStatRow
import dev.locus.core.database.ConversationEntity
import dev.locus.core.database.MessageStatRow
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.TASK_BUDGET_ENTRY
import dev.locus.core.database.UsageEventEntity
import dev.locus.core.model.UsageSource
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsReportTest {
    private val zone = ZoneOffset.UTC
    private val day = 24 * 60 * 60 * 1000L
    private val today = LocalDate.of(2026, 10, 31).atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = today + 12 * 60 * 60 * 1000L
    private val settings = AppSettings(providers = listOf(ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-x")))

    private fun chat(id: String, title: String = id, override: String? = null) =
        ConversationEntity(id = id, title = title, providerId = "p", systemPrompt = "", createdAt = today, updatedAt = today, modelOverride = override)

    private fun usage(input: Int, output: Int, cached: Int = 0) = """{"input":$input,"output":$output,"cacheRead":$cached}"""

    @Test
    fun `tokens, requests and reply times are bucketed by day`() {
        val since = today - 6 * day
        val messages = listOf(
            MessageStatRow("a", "USER", today - 2 * day, null),
            MessageStatRow("a", "ASSISTANT", today - 2 * day + 2_000, usage(100, 10, cached = 50)),
            MessageStatRow("a", "TOOL", today + 1_000, null),
            MessageStatRow("a", "ASSISTANT", today + 5_000, usage(300, 30)),
        )
        val audit = listOf(
            AuditStatRow("a", "run_command", "AUTO_ALLOWED", false, today, today + 400),
            AuditStatRow("a", "run_command", "AUTO_ALLOWED", true, today, today + 1_000),
            AuditStatRow("a", "write_file", "USER_APPROVED", false, today, today + 60_000),
            AuditStatRow("a", "delete_path", "USER_DENIED", true, today, today + 5),
        )
        val r = AnalyticsRepository.buildReport(AnalyticsPeriod.Week, since, now, messages, audit, listOf(chat("a", override = "gpt-y")), settings, emptyList(), zone)

        assertEquals(7, r.buckets.size)
        assertEquals(440, r.tokens.total)
        assertEquals(50f / 400f, r.tokens.cachedShare)
        assertEquals(2, r.requests)
        assertEquals(110L, r.buckets[4].tokens.total)
        assertEquals(330L, r.buckets[6].tokens.total)
        assertEquals(4_000L, r.replyP95Ms)
        assertEquals("gpt-y", r.models.single().model)
        assertEquals(4, r.tools.calls)
        assertEquals(1, r.tools.failures)
        assertEquals(1, r.tools.denied)
        assertEquals(2f / 3f, r.tools.successRate!!, 1e-5f)
        // Only auto-allowed calls are timed, so write_file's approval wait doesn't count.
        assertEquals(listOf("run_command"), r.tools.slowest.map { it.name })
        assertEquals(1_000L, r.tools.slowest.single().p95Ms)
    }

    @Test
    fun `task budget entries are not tool calls`() {
        val audit = listOf(
            AuditStatRow("a", "run_command", "AUTO_ALLOWED", false, today, today + 400),
            AuditStatRow("a", TASK_BUDGET_ENTRY, "USER_APPROVED", false, today, today),
            AuditStatRow("a", TASK_BUDGET_ENTRY, "USER_DENIED", false, today, today),
            AuditStatRow("a", TASK_BUDGET_ENTRY, "AUTO_ALLOWED", false, today, today),
        )
        val r = AnalyticsRepository.buildReport(AnalyticsPeriod.Week, today - 6 * day, now, emptyList(), audit, listOf(chat("a")), settings, emptyList(), zone)
        assertEquals(1, r.tools.calls)
        assertEquals(1, r.tools.autoAllowed)
        assertEquals(0, r.tools.userApproved)
        assertEquals(0, r.tools.denied)
        assertEquals(listOf("run_command"), r.tools.slowest.map { it.name })
        assertEquals(1, r.buckets.last().toolCalls)
        assertEquals(1, r.chats.single().toolCalls)
    }

    @Test
    fun `reasoning tokens are counted in output and summed apart, and old rows still read`() {
        val messages = listOf(
            MessageStatRow("a", "ASSISTANT", today + 1_000, """{"input":100,"output":300,"reasoning":250}"""),
            MessageStatRow("a", "ASSISTANT", today + 2_000, usage(100, 20)),
        )
        val r = AnalyticsRepository.buildReport(AnalyticsPeriod.Week, today - 6 * day, now, messages, emptyList(), listOf(chat("a")), settings, emptyList(), zone)
        assertEquals(320L, r.tokens.output)
        assertEquals(250L, r.tokens.reasoning)
        assertEquals(520L, r.tokens.total)
    }

    @Test
    fun `scheduled runs are counted per task`() {
        val chats = listOf(chat("s1", "Scheduled: News · 10/30/26, 9:00 AM"), chat("s2", "Scheduled: News · 10/31/26, 9:00 AM"))
        val r = AnalyticsRepository.buildReport(AnalyticsPeriod.All, 0, now, emptyList(), emptyList(), chats, settings, emptyList(), zone)
        assertEquals(2, r.taskRuns)
        assertEquals("News", r.tasks.single().title)
        assertEquals(2, r.tasks.single().runs)
    }

    @Test
    fun `percentile uses nearest rank`() {
        assertEquals(2L, AnalyticsRepository.percentile(listOf(1L, 2L, 3L, 4L), 0.5))
        assertEquals(3L, AnalyticsRepository.percentile(listOf(1L, 2L, 3L, 4L), 0.75))
        assertEquals(10L, AnalyticsRepository.percentile((1L..10L).toList(), 0.95))
    }

    private val twoProviders = AppSettings(
        providers = listOf(
            ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-x"),
            ProviderConfig("q", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "gpt-y"),
        ),
    )

    @Test
    fun `replies are grouped by the model stored with them, older ones by the chat's current model`() {
        // The chat now uses q/gpt-y; its first reply was answered by p/claude-x and says so.
        val c = chat("a").copy(providerId = "q", modelOverride = null)
        val messages = listOf(
            MessageStatRow("a", "ASSISTANT", today + 1_000, usage(100, 10), providerId = "p", model = "claude-x", totalMs = 900),
            MessageStatRow("a", "ASSISTANT", today + 2_000, usage(50, 5), providerId = "q", model = "gpt-y-mini", totalMs = 400),
            // Written before v9: nothing stored, so it goes to what the chat uses now.
            MessageStatRow("a", "ASSISTANT", today + 3_000, usage(10, 1)),
            // Stored by a provider that has since been removed.
            MessageStatRow("a", "ASSISTANT", today + 4_000, usage(1, 1), providerId = "gone", model = "old-model", totalMs = 100),
        )
        val r = AnalyticsRepository.buildReport(AnalyticsPeriod.Week, today - 6 * day, now, messages, emptyList(), listOf(c), twoProviders, emptyList(), zone)

        val byModel = r.models.associate { (it.providerId to it.model) to it.tokens }
        assertEquals(mapOf<Pair<String?, String>, Long>(("p" to "claude-x") to 110L, ("q" to "gpt-y-mini") to 55L, ("q" to "gpt-y") to 11L, ("gone" to "old-model") to 2L), byModel)
        assertEquals("Anthropic", r.models.first().providerName)
        assertEquals("a removed provider", r.models.single { it.providerId == "gone" }.providerName)
    }

    @Test
    fun `reply speed uses the stored latency, and first token where it was stored`() {
        val messages = listOf(
            MessageStatRow("a", "USER", today, null),
            // Stored: 3s to finish, 0.5s to the first token. The 60s since the user's message don't count.
            MessageStatRow("a", "ASSISTANT", today + 60_000, null, parentCreatedAt = today, firstTokenMs = 500, totalMs = 3_000),
            MessageStatRow("a", "ASSISTANT", today + 70_000, null, parentCreatedAt = today, firstTokenMs = 1_500, totalMs = 9_000),
            // Older reply: inferred from its parent, 2s; it has no first-token time.
            MessageStatRow("a", "ASSISTANT", today + 72_000, null, parentCreatedAt = today + 70_000),
        )
        val r = AnalyticsRepository.buildReport(AnalyticsPeriod.Week, today - 6 * day, now, messages, emptyList(), listOf(chat("a")), settings, emptyList(), zone)
        assertEquals(3_000L, r.replyMedianMs)
        assertEquals(9_000L, r.replyP95Ms)
        assertEquals(500L, r.firstTokenMedianMs)
        assertEquals(1_500L, r.firstTokenP95Ms)

        val old = AnalyticsRepository.buildReport(AnalyticsPeriod.Week, today - 6 * day, now, messages.takeLast(1), emptyList(), listOf(chat("a")), settings, emptyList(), zone)
        kotlin.test.assertNull(old.firstTokenMedianMs)
    }

    @Test
    fun `side requests count toward totals, their chat and model, and show where tokens went`() {
        val helperChat = chat("h").copy(parentConversationId = "a", hidden = true, modelOverride = "claude-x")
        val messages = listOf(
            MessageStatRow("a", "ASSISTANT", today + 1_000, usage(1_000, 100), providerId = "p", model = "claude-x", totalMs = 1),
            MessageStatRow("h", "ASSISTANT", today + 2_000, usage(400, 40), providerId = "p", model = "claude-x", totalMs = 1),
        )
        fun event(source: UsageSource, chat: String?, tokens: String?, provider: String = "q", model: String = "gpt-y") =
            UsageEventEntity(conversationId = chat, source = source.wire, createdAt = today + 3_000, providerId = provider, model = model, usageJson = tokens, totalMs = 200)
        val events = listOf(
            event(UsageSource.TITLE, "a", usage(50, 5)),
            event(UsageSource.SUGGESTIONS, "a", usage(80, 20)),
            event(UsageSource.COMPACTION, "a", usage(900, 300), provider = "p", model = "claude-x"),
            event(UsageSource.REVIEWER, "a", null),
            event(UsageSource.OTHER, null, usage(10, 10)),
        )
        val r = AnalyticsRepository.buildReport(
            AnalyticsPeriod.Week, today - 6 * day, now, messages, emptyList(), listOf(chat("a"), helperChat), twoProviders, emptyList(), zone, events,
        )

        assertEquals(1_100L + 440 + 55 + 100 + 1_200 + 20, r.tokens.total)
        assertEquals(7, r.requests)
        assertEquals(7, r.buckets.last().requests)
        assertEquals(
            listOf(
                SourceUsage(UsageSource.COMPACTION, 1_200, 1),
                SourceUsage(UsageSource.CHAT, 1_100, 1),
                SourceUsage(UsageSource.HELPER, 440, 1),
                SourceUsage(UsageSource.SUGGESTIONS, 100, 1),
                SourceUsage(UsageSource.TITLE, 55, 1),
                SourceUsage(UsageSource.OTHER, 20, 1),
                SourceUsage(UsageSource.REVIEWER, 0, 1),
            ),
            r.sources,
        )
        // The chat's own figures include its helper and its side requests; the one outside a chat counts only in totals.
        assertEquals(ChatUsage("a", "a", 1_100L + 440 + 55 + 100 + 1_200, 6, 0), r.chats.single())
        assertEquals(1_100L + 440 + 1_200, r.models.single { it.model == "claude-x" }.tokens)
        assertEquals(4, r.models.single { it.model == "gpt-y" }.requests)
        // Reply speed is about replies only; side requests' timing doesn't mix in.
        assertEquals(1L, r.replyP95Ms)
    }

    // ---- Cost ------------------------------------------------------------------------------------

    /** claude-x is priced; gpt-y isn't (models.dev doesn't list it). */
    private val prices: PriceLookup = { _, model -> if (model == "claude-x") ModelPrice(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75) else null }

    @Test
    fun `spend is summed per day, week and month, by model and by source`() {
        val messages = listOf(
            // 20 days ago, outside the 7-day period: only the month window counts it. $3.
            MessageStatRow("a", "ASSISTANT", today - 20 * day, usage(1_000_000, 0), providerId = "p", model = "claude-x"),
            // 3 days ago: 1M in × $3 + 100k out × $15 = $4.50.
            MessageStatRow("a", "ASSISTANT", today - 3 * day, usage(1_000_000, 100_000), providerId = "p", model = "claude-x"),
            // Today a backup model answered: the reply stores the model that answered, and it has no price.
            MessageStatRow("a", "ASSISTANT", today + 1_000, usage(500, 50), providerId = "q", model = "gpt-y"),
        )
        // A title written with a 1-hour cache write of 1M tokens: 2 × $3 = $6 (not the 5-minute $3.75).
        val events = listOf(
            UsageEventEntity(
                conversationId = "a", source = UsageSource.TITLE.wire, createdAt = today + 2_000, providerId = "p", model = "claude-x",
                usageJson = """{"input":1000000,"output":0,"cacheWrite":1000000,"cacheWrite1h":1000000}""", totalMs = 1,
            ),
        )
        val r = AnalyticsRepository.buildReport(
            AnalyticsPeriod.Week, today - 6 * day, now, messages, emptyList(), listOf(chat("a")), twoProviders, emptyList(), zone, events, prices, pricesOn = true,
        )
        val cost = r.cost

        assertTrue(cost.pricesOn)
        assertEquals(6.0, cost.today.usd, 1e-9)
        assertEquals(1 to 1, cost.today.priced to cost.today.unpriced)
        assertTrue(cost.today.partial, "gpt-y's reply has no price, so today's sum is a floor")
        assertEquals(10.5, cost.week.usd, 1e-9)
        assertEquals(13.5, cost.month.usd, 1e-9)
        assertEquals(4, cost.month.requests)
        // The selected period (7 days) leaves the 20-day-old reply out, and so do the other figures.
        assertEquals(10.5, cost.period.usd, 1e-9)
        assertEquals(3, r.requests)
        assertEquals(7, cost.buckets.size)
        assertEquals(4.5, cost.buckets[3].usd, 1e-9)
        assertEquals(6.0, cost.buckets[6].usd, 1e-9)
        assertEquals(CostEstimate(), cost.buckets[0])

        assertEquals(listOf("claude-x", "gpt-y"), cost.models.map { it.model })
        assertEquals(10.5, cost.models[0].cost.usd, 1e-9)
        assertEquals("Anthropic", cost.models[0].providerName)
        assertTrue(cost.models[1].cost.unknown)
        assertEquals(listOf(UsageSource.TITLE, UsageSource.CHAT), cost.sources.map { it.source })
        assertEquals(6.0, cost.sources[0].cost.usd, 1e-9)
        assertEquals(4.5, cost.sources[1].cost.usd, 1e-9)
        assertTrue(cost.sources[1].cost.partial)
    }

    @Test
    fun `missing prices make the spend unknown, never zero`() {
        val messages = listOf(MessageStatRow("a", "ASSISTANT", today + 1_000, usage(1_000, 100), providerId = "q", model = "gpt-y"))
        // Prices off: nothing is priced.
        val off = AnalyticsRepository.buildReport(AnalyticsPeriod.Week, today - 6 * day, now, messages, emptyList(), listOf(chat("a")), twoProviders, emptyList(), zone).cost
        assertFalse(off.pricesOn)
        assertTrue(off.today.unknown)
        assertTrue(off.week.unknown && off.month.unknown && off.period.unknown)
        assertTrue(off.models.single().cost.unknown)

        // A priced model's request without token counts can't be estimated either.
        val noCounts = listOf(MessageStatRow("a", "ASSISTANT", today + 1_000, null, providerId = "p", model = "claude-x"))
        val r = AnalyticsRepository.buildReport(AnalyticsPeriod.Week, today - 6 * day, now, noCounts, emptyList(), listOf(chat("a")), twoProviders, emptyList(), zone, price = prices, pricesOn = true).cost
        assertTrue(r.today.unknown)
        assertEquals(0.0, r.today.usd)

        // No requests at all: nothing spent, which isn't "unknown".
        val none = AnalyticsRepository.buildReport(AnalyticsPeriod.Week, today - 6 * day, now, emptyList(), emptyList(), listOf(chat("a")), twoProviders, emptyList(), zone, price = prices, pricesOn = true).cost
        assertEquals(0, none.month.requests)
        assertFalse(none.month.unknown)
        assertFalse(none.month.partial)
    }

    @Test
    fun `models on the phone cost nothing, others need the catalogue`() {
        val s = AppSettings(
            providers = listOf(
                ProviderConfig("d", "On this phone", ApiFormat.ON_DEVICE, "", "gemma"),
                ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-x"),
            ),
        )
        val lookup = priceLookup(s, null)
        assertEquals(0.0, lookup("d", "gemma")!!.cost(dev.locus.core.model.TokenUsage(1_000, 1_000)))
        kotlin.test.assertNull(lookup("p", "claude-x"))
        kotlin.test.assertNull(lookup("removed", "claude-x"))
    }
}
