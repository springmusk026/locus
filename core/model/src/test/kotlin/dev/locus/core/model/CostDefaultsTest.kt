package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Price steps, the effort default and its migration (docs/design/cost.md). */
class CostDefaultsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- Price steps ---------------------------------------------------------------------------

    @Test
    fun `Haiku 5_5's documented step is 100,000 and Locus summarises at 90,000`() {
        assertEquals(100_000, PriceSteps.documented("claude-haiku-5-5"))
        assertEquals(100_000, PriceSteps.documented("claude-haiku-5-5-20261001"))
        assertEquals(100_000, PriceSteps.documented("anthropic/claude-haiku-5-5"))
        assertEquals(90_000, PriceSteps.ceiling("claude-haiku-5-5", null))
        // Claude 4.6 and later (except Haiku 5.5) price the whole 1M window the same.
        assertNull(PriceSteps.documented("claude-opus-5-5"))
        assertNull(PriceSteps.documented("claude-haiku-4-5"))
        assertNull(PriceSteps.ceiling("claude-sonnet-5-5", null))
    }

    @Test
    fun `the catalogue's step wins, and the margin is a tenth of the step, at least 10,000, never under 50,000`() {
        val gpt = ModelPrice(4.0, 20.0, tiers = listOf(PriceTier(272_000, ModelPrice(8.0, 30.0))))
        assertEquals(244_800, PriceSteps.ceiling("gpt-5.6", gpt))
        assertEquals(90_000, PriceSteps.compactBelow(100_000))
        assertEquals(54_000, PriceSteps.compactBelow(64_000))
        assertEquals(50_000, PriceSteps.compactBelow(55_000))
        assertEquals(50_000, PriceSteps.compactBelow(32_000))
    }

    // ---- Cost of one Haiku request across the step ------------------------------------------------

    @Test
    fun `a long Haiku 5_5 request costs five times as much past the step, compaction included`() {
        val haiku = ModelPrice(0.10, 0.50, cacheRead = 0.01, cacheWrite = 0.125, tiers = listOf(PriceTier(100_000, ModelPrice(0.50, 2.50, 0.05, 0.625))))
        // A 140K-token step that read 135K from the cache and wrote 5K with an hour's lifetime (2x input).
        val step = TokenUsage(140_000, 2_000, cacheRead = 135_000, cacheWrite = 5_000, cacheWrite1h = 5_000)
        assertEquals((135_000 * 0.05 + 5_000 * 1.0 + 2_000 * 2.5) / 1e6, haiku.cost(step), 1e-12)
        // The same step at 85K tokens, under the step.
        val under = TokenUsage(85_000, 2_000, cacheRead = 80_000, cacheWrite = 5_000, cacheWrite1h = 5_000)
        assertEquals((80_000 * 0.01 + 5_000 * 0.2 + 2_000 * 0.5) / 1e6, haiku.cost(under), 1e-12)
        // A compaction iteration of 90K in and 4K out, billed with the request at its own (lower) step.
        val withSummary = under.copy(serverCompaction = TokenUsage(90_000, 4_000))
        assertEquals(haiku.cost(under) + (90_000 * 0.1 + 4_000 * 0.5) / 1e6, haiku.cost(withSummary), 1e-12)
        assertEquals(TaskBudget.budgetTokens(under) + 94_000, TaskBudget.budgetTokens(withSummary))
    }

    @Test
    fun `usage stored before this change still reads, and a server-side summary round-trips`() {
        val old = json.decodeFromString(TokenUsage.serializer(), """{"input":10,"output":2,"cacheRead":0,"cacheWrite":0,"reasoning":0,"cacheWrite1h":0}""")
        assertEquals(TokenUsage(10, 2), old)
        assertNull(old.serverCompaction)
        val withSummary = TokenUsage(10, 2, serverCompaction = TokenUsage(150_000, 3_000))
        assertEquals(withSummary, json.decodeFromString(TokenUsage.serializer(), json.encodeToString(TokenUsage.serializer(), withSummary)))
        assertEquals(listOf(TokenUsage(10, 2), TokenUsage(150_000, 3_000)), withSummary.billedParts)
        assertEquals(12, withSummary.context)
    }

    // ---- Effort: the model's own default -------------------------------------------------------

    @Test
    fun `a new provider sends no effort, and each model's default is the documented one`() {
        assertEquals("", ProviderConfig("p", "A", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5").effort)
        assertEquals(Effort.MEDIUM, Effort.modelDefault(ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"))
        assertEquals(Effort.MEDIUM, Effort.modelDefault(ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-haiku-5-5"))
        assertEquals(Effort.HIGH, Effort.modelDefault(ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-sonnet-5-5"))
        assertEquals(Effort.HIGH, Effort.modelDefault(ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5"))
        assertEquals(Effort.MEDIUM, Effort.modelDefault(ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.6"))
        assertNull(Effort.modelDefault(ApiFormat.OPENAI_CHAT, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3-flash"))
        assertEquals(Effort.MEDIUM, Effort.ofProvider("", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"))
        assertEquals(Effort.HIGH, Effort.ofProvider("high", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"))
        assertEquals("Medium, the model's own default.", Effort.describeProviderDefault("", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"))
        assertEquals("High, the provider's own setting.", Effort.describeProviderDefault("high", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"))
    }

    @Test
    fun `Chat Completions takes an effort only on the hosts that document one`() {
        assertTrue(Effort.supports(ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1"))
        assertTrue(Effort.supports(ApiFormat.OPENAI_CHAT, "https://generativelanguage.googleapis.com/v1beta/openai"))
        assertTrue(Effort.supports(ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1"))
        assertFalse(Effort.supports(ApiFormat.OPENAI_CHAT, "https://api.groq.com/openai/v1"))
        assertFalse(Effort.supports(ApiFormat.OPENAI_CHAT, "http://192.168.1.10:11434/v1"))
        assertFalse(Effort.supports(ApiFormat.OPENAI_CHAT, "https://evil.example/openrouter.ai"))
        assertTrue(Effort.supports(ApiFormat.ANTHROPIC_MESSAGES))
        assertFalse(Effort.supports(ApiFormat.ON_DEVICE))
    }

    // ---- Migration -----------------------------------------------------------------------------

    private fun provider(id: String, format: ApiFormat, effort: String) = ProviderConfig(id, id, format, "https://x", "m", effort = effort)

    @Test
    fun `settings saved before keep every effort a user could have chosen, and drop the Chat Completions default nobody saw`() {
        // As the previous release wrote them: every default spelled out, so each provider has "effort":"high".
        val legacy = AppSettings(
            providers = listOf(
                provider("anthropic", ApiFormat.ANTHROPIC_MESSAGES, "high"),
                provider("openai", ApiFormat.OPENAI_RESPONSES, "low"),
                provider("gemini", ApiFormat.OPENAI_CHAT, "high"),
            ),
        )
        val stored = json.encodeToString(AppSettings.serializer(), legacy).replace(",\"effortDefaults\":0", "")
        val read = json.decodeFromString(AppSettings.serializer(), stored)
        assertEquals(0, read.effortDefaults)
        assertTrue(EffortDefaults.needed(read))
        val migrated = EffortDefaults.migrate(read)
        assertEquals(listOf("high", "low", ""), migrated.providers.map { it.effort })
        assertEquals(EffortDefaults.CURRENT, migrated.effortDefaults)
        // Once only: a "high" chosen afterwards stays.
        val chosen = migrated.copy(providers = migrated.providers.map { if (it.id == "gemini") it.copy(effort = "high") else it })
        assertFalse(EffortDefaults.needed(chosen))
    }

    @Test
    fun `settings without a provider it would change aren't rewritten, and saving a provider marks them migrated`() {
        assertFalse(EffortDefaults.needed(AppSettings()))
        assertFalse(EffortDefaults.needed(AppSettings(providers = listOf(provider("a", ApiFormat.ANTHROPIC_MESSAGES, "high")))))
        // A user who adds a Chat Completions provider now and picks High keeps it, migration or not.
        val saved = EffortDefaults.current(AppSettings(providers = listOf(provider("g", ApiFormat.OPENAI_CHAT, "high"))))
        assertEquals(EffortDefaults.CURRENT, saved.effortDefaults)
        assertFalse(EffortDefaults.needed(saved))
    }
}
