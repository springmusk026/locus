package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan mode's "Run with" (docs/design/plan-run.md): which pick counts, the first request's estimate, and when Approve asks. */
class PlanRunTest {
    private val opus = ModelRef("anthropic", "claude-opus-5-5")
    private val haiku = ModelRef("anthropic", "claude-haiku-5-5")
    private val gpt = ModelRef("openai", "gpt-5-mini")

    private val opusPrice = ModelPrice(input = 5.0, output = 25.0, cacheRead = 0.5, cacheWrite = 6.25)
    private val haikuPrice = ModelPrice(input = 1.0, output = 5.0, cacheRead = 0.1, cacheWrite = 1.25)

    @Test
    fun `nothing picked, the chat's own model, or a pick without a model all mean the chat's model`() {
        assertNull(PlanRun.runOn(opus, null))
        assertNull(PlanRun.runOn(opus, opus))
        assertNull(PlanRun.runOn(opus, ModelRef("anthropic", " ")))
        assertEquals(haiku, PlanRun.runOn(opus, haiku))
        // A chat without a provider still takes an explicit pick; the runner checks it can serve.
        assertEquals(gpt, PlanRun.runOn(null, gpt))
    }

    @Test
    fun `the run model's first request is a cache write of the whole prompt, the chat's model is priced warm`() {
        val e = PlanRun.estimate(contextTokens = 100_000, newTokens = 10, chatPrice = opusPrice, runPrice = haikuPrice)
        assertEquals(100_010, e.promptTokens)
        // 100,010 tokens written at $1.25 per million.
        assertEquals(100_010 * 1.25 / 1_000_000, assertNotNull(e.run), 1e-9)
        // 100,000 read at $0.50, 10 fresh at $5.
        assertEquals((100_000 * 0.5 + 10 * 5.0) / 1_000_000, assertNotNull(e.chatWarm), 1e-9)
    }

    @Test
    fun `on Anthropic the write is priced for an hour, as the chat's own runs ask`() {
        val hour = PlanRun.estimate(100_000, 0, null, haikuPrice, hourCache = true)
        // An hour's write costs 2x the input price.
        assertEquals(100_000 * 2.0 / 1_000_000, assertNotNull(hour.run), 1e-9)
        assertNull(hour.chatWarm, "no price for the chat's model: unknown, not zero")
    }

    @Test
    fun `a provider the chat doesn't use always asks, even after a confirmation and when cheap`() {
        val cheap = PlanRunEstimate(1_000, chatWarm = 0.001, run = 0.0001)
        assertTrue(PlanRun.needsConfirm(confirmedBefore = true, chat = opus, runOn = gpt, estimate = cheap))
        assertTrue(PlanRun.needsConfirm(confirmedBefore = true, chat = opus, runOn = gpt, estimate = PlanRunEstimate(1_000, null, null)))
    }

    @Test
    fun `the same provider asks the first time, then only above the threshold`() {
        val cheap = PlanRunEstimate(1_000, chatWarm = 0.001, run = 0.01)
        val dear = PlanRunEstimate(400_000, chatWarm = 0.2, run = 0.5)
        val unknown = PlanRunEstimate(400_000, chatWarm = null, run = null)
        assertTrue(PlanRun.needsConfirm(confirmedBefore = false, chat = opus, runOn = haiku, estimate = cheap))
        assertFalse(PlanRun.needsConfirm(confirmedBefore = true, chat = opus, runOn = haiku, estimate = cheap))
        assertTrue(PlanRun.needsConfirm(confirmedBefore = true, chat = opus, runOn = haiku, estimate = dear))
        // The chat model's figure doesn't count: it isn't the request being made.
        assertFalse(PlanRun.needsConfirm(true, opus, haiku, PlanRunEstimate(400_000, chatWarm = 9.0, run = 0.01)))
        assertFalse(PlanRun.needsConfirm(confirmedBefore = true, chat = opus, runOn = haiku, estimate = unknown))
    }

    @Test
    fun `a removed provider can't run a plan, and never falls back to the default one`() {
        val anthropic = ProviderConfig("anthropic", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
        val settings = AppSettings(providers = listOf(anthropic), defaultProviderId = "anthropic")
        assertEquals(anthropic, PlanRun.available(settings, haiku))
        assertNull(PlanRun.available(settings, gpt))
        assertNull(PlanRun.available(settings, ModelRef("anthropic", "")))
    }
}
