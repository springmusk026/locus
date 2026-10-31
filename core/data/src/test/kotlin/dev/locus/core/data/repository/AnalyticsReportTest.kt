package dev.locus.core.data.repository

import dev.locus.core.database.AuditStatRow
import dev.locus.core.database.ConversationEntity
import dev.locus.core.database.MessageStatRow
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProviderConfig
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
}
