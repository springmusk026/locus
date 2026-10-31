package dev.locus.feature.chat

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.effectiveModel
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Issue #1: the sheet's tick must mark the model the chat's requests use, which is also what the
 * header shows. The runner resolves it with [effectiveModel]; the sheet must agree in every case.
 */
class ModelPickTest {
    private val config = ProviderConfig("p", "Proxy", ApiFormat.OPENAI_CHAT, "https://proxy.example/v1", model = "gemini-3.8-flash-high")
    private val listed = listOf("claude-opus-4-6-thinking", "gemini-3.8-flash-high", "gpt-x")

    /** What the chat screen builds from a conversation: composer controls, then the sheet's ticked rows. */
    private fun ticked(config: ProviderConfig, override: String?, listed: List<String>?): List<String> {
        val provider = chatProvider(config)
        val controls = ChatControls(model = config.effectiveModel(override), modelOverridden = override != null)
        return providerRows(provider, listed, provider.id, controls).filter { it.selected }.map { it.id }
    }

    @Test
    fun `the tick follows a chosen model`() {
        assertEquals(listOf(config.effectiveModel("claude-opus-4-6-thinking")), ticked(config, "claude-opus-4-6-thinking", listed))
    }

    @Test
    fun `the tick follows the provider default`() {
        assertEquals(listOf(config.effectiveModel(null)), ticked(config, null, listed))
    }

    @Test
    fun `a chosen model that later became the provider default is still ticked`() {
        // The chat chose claude; afterwards Settings made claude the provider's default too.
        val changed = config.copy(model = "claude-opus-4-6-thinking")
        assertEquals(listOf("claude-opus-4-6-thinking"), ticked(changed, "claude-opus-4-6-thinking", listed))
    }

    @Test
    fun `a chosen model missing from the provider's list is shown and ticked`() {
        assertEquals(listOf("claude-opus-4-6-thinking"), ticked(config, "claude-opus-4-6-thinking", listOf("gemini-3.8-flash-high")))
        assertEquals(listOf("claude-opus-4-6-thinking"), ticked(config, "claude-opus-4-6-thinking", null))
    }

    @Test
    fun `an override with stray spaces resolves the same for the runner and the sheet`() {
        assertEquals(listOf(config.effectiveModel(" claude-opus-4-6-thinking ")), ticked(config, " claude-opus-4-6-thinking ", listed))
    }

    @Test
    fun `other providers never show a tick`() {
        val other = chatProvider(config.copy(id = "q"))
        val controls = ChatControls(model = "gemini-3.8-flash-high", modelOverridden = false)
        assertEquals(emptyList(), providerRows(other, listed, "p", controls).filter { it.selected })
    }
}
