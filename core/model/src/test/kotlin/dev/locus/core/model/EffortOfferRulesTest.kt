package dev.locus.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which providers are offered the one-tap move from the old "high" to the model's own effort, and what each answer stores. */
class EffortOfferRulesTest {
    private fun provider(format: ApiFormat, model: String, effort: String = "high", baseUrl: String = "https://api.example.test", id: String = "p") =
        ProviderConfig(id, id, format, baseUrl, model, effort = effort)

    private fun offered(p: ProviderConfig, settings: AppSettings = AppSettings(providers = listOf(p))) = EffortDefaults.offersModelDefault(settings, p)

    @Test
    fun `offered where the stored high is above the model's own documented default`() {
        assertTrue(offered(provider(ApiFormat.ANTHROPIC_MESSAGES, "claude-opus-5-5")))
        assertTrue(offered(provider(ApiFormat.ANTHROPIC_MESSAGES, "claude-haiku-5-5-20261001")))
        assertTrue(offered(provider(ApiFormat.OPENAI_RESPONSES, "gpt-6")))
        assertTrue(offered(provider(ApiFormat.CHATGPT, "gpt-6")))
    }

    @Test
    fun `not offered where the move would change nothing or the level is a choice`() {
        // Other Claude models default to high: the move would send the same thing.
        assertFalse(offered(provider(ApiFormat.ANTHROPIC_MESSAGES, "claude-sonnet-4-5")))
        // Any other stored level, or none, was picked (or is already the default).
        assertFalse(offered(provider(ApiFormat.ANTHROPIC_MESSAGES, "claude-opus-5-5", effort = "medium")))
        assertFalse(offered(provider(ApiFormat.ANTHROPIC_MESSAGES, "claude-opus-5-5", effort = "xhigh")))
        assertFalse(offered(provider(ApiFormat.ANTHROPIC_MESSAGES, "claude-opus-5-5", effort = "")))
        // Chat Completions lost its unseen "high" in the migration, so a "high" there was chosen.
        assertFalse(offered(provider(ApiFormat.OPENAI_CHAT, "gpt-6", baseUrl = "https://api.openai.com/v1")))
        assertFalse(offered(provider(ApiFormat.ON_DEVICE, "gemma")))
        // Answered before.
        val p = provider(ApiFormat.ANTHROPIC_MESSAGES, "claude-opus-5-5")
        assertFalse(offered(p, AppSettings(providers = listOf(p), effortChosen = setOf(p.id))))
    }

    @Test
    fun `the tap clears the level and records the answer, keep records it alone`() {
        val a = provider(ApiFormat.ANTHROPIC_MESSAGES, "claude-opus-5-5", id = "a")
        val b = provider(ApiFormat.OPENAI_RESPONSES, "gpt-6", id = "b")
        val settings = AppSettings(providers = listOf(a, b))
        val moved = EffortDefaults.useModelDefault(settings, "a")
        assertEquals(listOf("", "high"), moved.providers.map { it.effort })
        assertEquals(setOf("a"), moved.effortChosen)
        val kept = EffortDefaults.keep(settings, "b")
        assertEquals(listOf("high", "high"), kept.providers.map { it.effort })
        assertEquals(setOf("b"), kept.effortChosen)
    }

    @Test
    fun `a save records a changed or new level as chosen, an unchanged one keeps the offer`() {
        val a = provider(ApiFormat.ANTHROPIC_MESSAGES, "claude-opus-5-5")
        val settings = AppSettings(providers = listOf(a))
        assertEquals(emptySet(), EffortDefaults.saved(settings, previous = a, saved = a.copy(name = "Claude")).effortChosen)
        assertEquals(setOf("p"), EffortDefaults.saved(settings, previous = a.copy(effort = "low"), saved = a).effortChosen)
        assertEquals(setOf("p"), EffortDefaults.saved(settings, previous = null, saved = a).effortChosen)
        // Saving still marks the settings as past the first migration, as before.
        assertEquals(EffortDefaults.CURRENT, EffortDefaults.saved(settings, previous = a, saved = a).effortDefaults)
    }
}
