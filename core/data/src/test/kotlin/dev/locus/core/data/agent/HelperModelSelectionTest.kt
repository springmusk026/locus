package dev.locus.core.data.agent

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProviderConfig
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Which model does helper work (docs/design/cost.md, "The helper model"): "Automatic" by default, the
 * chat's own when the user says so or nothing cheaper is known, and the user's or the project's choice
 * over both. The safety reviewer never gets the automatic pick.
 */
class HelperModelSelectionTest {
    private val anthropic = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
    private val openRouter = ProviderConfig("o", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-5.5")
    private val groq = ProviderConfig("g", "Groq", ApiFormat.OPENAI_CHAT, "https://api.groq.com/openai/v1", "llama-5-70b")
    private val settings = AppSettings(providers = listOf(anthropic, openRouter, groq), defaultProviderId = "a")

    @Test
    fun `automatic is the default and picks the cheaper model of the chat's provider`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        val helper = g.helper.providerFor(anthropic)!!
        assertEquals("a", helper.id)
        assertEquals("claude-haiku-5-5", helper.model)
        assertEquals("anthropic/claude-haiku-5.5", g.helper.providerFor(openRouter)!!.model)
    }

    @Test
    fun `an unknown provider, or a chat already on a cheap model, keeps the chat's model`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        assertEquals("llama-5-70b", g.helper.providerFor(groq)!!.model)
        assertEquals("claude-haiku-5-5", g.helper.providerFor(anthropic.copy(model = "claude-haiku-5-5"))!!.model)
    }

    @Test
    fun `same as each chat turns it off`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.settingsRepository.setHelperModel(null, "", automatic = false)
        assertEquals("claude-opus-5-5", g.helper.providerFor(anthropic)!!.model)
        g.settingsRepository.setHelperModel(null, "", automatic = true)
        assertEquals("claude-haiku-5-5", g.helper.providerFor(anthropic)!!.model)
    }

    @Test
    fun `the user's own choice wins over automatic`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.settingsRepository.setHelperModel("o", "google/gemini-3-flash")
        val helper = g.helper.providerFor(anthropic)!!
        assertEquals("o", helper.id)
        assertEquals("google/gemini-3-flash", helper.model)
        // A provider chosen without a model: that provider's own model.
        g.settingsRepository.setHelperModel("g", "")
        assertEquals("llama-5-70b", g.helper.providerFor(anthropic)!!.model)
        // A removed helper provider falls back to automatic on the chat's provider.
        g.settingsRepository.setHelperModel("gone", "")
        assertEquals("claude-haiku-5-5", g.helper.providerFor(anthropic)!!.model)
    }

    @Test
    fun `the safety reviewer never gets the automatic pick`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        assertEquals("claude-opus-5-5", g.helper.providerFor(anthropic, automatic = false)!!.model)
        // A model the user chose is used, as before.
        g.settingsRepository.setHelperModel("a", "claude-sonnet-5-5")
        assertEquals("claude-sonnet-5-5", g.helper.providerFor(anthropic, automatic = false)!!.model)
    }
}
