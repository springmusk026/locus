package dev.locus.core.data.repository

import dev.locus.core.database.AuditStatRow
import dev.locus.core.database.ConversationEntity
import dev.locus.core.database.MessageStatRow
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProviderConfig
import dev.locus.core.database.UsageEventEntity
import dev.locus.core.model.UsageSource
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

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
}
