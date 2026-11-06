package dev.locus.core.data.repository

import dev.locus.core.data.agent.HelperModel
import dev.locus.core.data.agent.compactionSetup
import dev.locus.core.database.ConversationEntity
import dev.locus.core.database.MessageStatRow
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.PriceTier
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.UsageSource
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Server-side summaries in Insights, the compaction ceiling per provider, and side jobs' effort (docs/design/cost.md). */
class CostAccountingReportTest {
    private val zone = ZoneOffset.UTC
    private val day = 24 * 60 * 60 * 1000L
    private val today = LocalDate.of(2026, 11, 6).atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = today + 12 * 60 * 60 * 1000L
    private val haiku = ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-haiku-5-5", contextWindow = 1_000_000)
    private val settings = AppSettings(providers = listOf(haiku))
    private val haikuPrice = ModelPrice(0.10, 0.50, cacheRead = 0.01, cacheWrite = 0.125, tiers = listOf(PriceTier(100_000, ModelPrice(0.50, 2.50, 0.05, 0.625))))

    @Test
    fun `a server-side summary counts as a compaction request with its own cost`() {
        val chat = ConversationEntity(id = "a", title = "a", providerId = "p", systemPrompt = "", createdAt = today, updatedAt = today)
        // A reply of 20K in and 1K out whose request also summarised 95K into 3K on the server.
        val usage = """{"input":20000,"output":1000,"serverCompaction":{"input":95000,"output":3000}}"""
        val messages = listOf(MessageStatRow("a", "ASSISTANT", today + 1_000, usage, providerId = "p", model = "claude-haiku-5-5"))
        val r = AnalyticsRepository.buildReport(
            AnalyticsPeriod.Week, today - 6 * day, now, messages, emptyList(), listOf(chat), settings, emptyList(), zone,
            price = { _, _ -> haikuPrice }, pricesOn = true,
        )
        assertEquals(2, r.requests)
        assertEquals(mapOf(UsageSource.CHAT to 21_000L, UsageSource.COMPACTION to 98_000L), r.sources.associate { it.source to it.tokens })
        val chatCost = (20_000 * 0.10 + 1_000 * 0.50) / 1e6
        val summaryCost = (95_000 * 0.10 + 3_000 * 0.50) / 1e6
        assertEquals(chatCost, r.cost.sources.single { it.source == UsageSource.CHAT }.cost.usd, 1e-12)
        assertEquals(summaryCost, r.cost.sources.single { it.source == UsageSource.COMPACTION }.cost.usd, 1e-12)
        assertEquals(chatCost + summaryCost, r.cost.today.usd, 1e-12)
        assertEquals(119_000L, r.tokens.total)
    }

    @Test
    fun `Locus compacts below a price step itself, except where the server does it`() {
        // Haiku 5.5 on Anthropic compacts on the server, whose request carries the same 90,000 as its trigger.
        val server = compactionSetup(haiku, settings, haikuPrice)!!
        assertNull(server.contextWindow)
        assertNull(server.ceiling)
        // A Responses model with a 272K step in the catalogue: below it, well before 80% of a 1M window.
        val gpt = ProviderConfig("o", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.6", contextWindow = 1_050_000)
        val gptPrice = ModelPrice(4.0, 20.0, tiers = listOf(PriceTier(272_000, ModelPrice(8.0, 30.0))))
        assertEquals(244_800, compactionSetup(gpt, settings, gptPrice)!!.limit)
        // No step known: 80% of the window, as before.
        assertEquals(840_000, compactionSetup(gpt, settings, null)!!.limit)
        // Haiku 5.5 through a gateway that can't compact on the server: the documented step still applies.
        val gateway = haiku.copy(model = "claude-haiku-5-5", format = ApiFormat.OPENAI_CHAT, baseUrl = "https://openrouter.ai/api/v1")
        assertEquals(90_000, compactionSetup(gateway, settings, null)!!.limit)
    }

    @Test
    fun `quick side jobs run at low effort`() {
        assertEquals("low", HelperModel.quick(haiku.copy(effort = "xhigh")).effort)
        assertEquals("low", HelperModel.quick(haiku).effort)
    }
}
