package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class FallbackChainTest {
    private val anthropic = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-x")
    private val openAi = ProviderConfig("o", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-x")
    private val phone = ProviderConfig("d", "Phone", ApiFormat.ON_DEVICE, "", "gemma", requiresKey = false)

    @Test
    fun `settings saved before fallbacks existed load with an empty chain`() {
        // As written by the previous release (Json with encodeDefaults, no fallbackModels key).
        val old = """{"providers":[{"id":"a","name":"Anthropic","format":"ANTHROPIC_MESSAGES","baseUrl":"https://api.anthropic.com","model":"claude-x"}],""" +
            """"defaultProviderId":"a","favoriteModels":[{"providerId":"a","model":"claude-x"}],"taskBudget":{}}"""
        val settings = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), old)
        assertEquals(emptyList(), settings.fallbackModels)
        assertEquals("claude-x", settings.providers.single().model)
        assertEquals(listOf(ModelRef("a", "claude-x")), settings.favoriteModels)
    }

    @Test
    fun `the chain round-trips through the stored json`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val settings = AppSettings(providers = listOf(anthropic, openAi), fallbackModels = listOf(ModelRef("o", "gpt-x"), ModelRef("a", "claude-y")))
        assertEquals(settings, json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings)))
    }

    @Test
    fun `resolve keeps order and drops the chat's own model, missing and on-device providers and repeats`() {
        val settings = AppSettings(
            providers = listOf(anthropic, openAi, phone),
            fallbackModels = listOf(
                ModelRef("a", "claude-x"), // the chat's own model
                ModelRef("o", "gpt-x"),
                ModelRef("gone", "m"),
                ModelRef("d", "gemma"),
                ModelRef("a", "claude-y"),
                ModelRef("o", ""), // the provider's default: same as gpt-x
            ),
        )
        val chain = FallbackChain.resolve(settings, anthropic, "claude-x")
        assertEquals(listOf("o" to "gpt-x", "a" to "claude-y"), chain.map { (p, m) -> p.id to m })
        // Another model of the same provider is a valid backup for a chat on claude-y.
        assertEquals(listOf("a" to "claude-x", "o" to "gpt-x"), FallbackChain.resolve(settings, anthropic, "claude-y").map { (p, m) -> p.id to m })
    }

    @Test
    fun `add, move up and the cap`() {
        val a = ModelRef("a", "1")
        val b = ModelRef("b", "2")
        val c = ModelRef("c", "3")
        assertEquals(listOf(a, b), FallbackChain.add(listOf(a, b), a))
        assertEquals(listOf(b, a, c), FallbackChain.moveUp(listOf(a, b, c), 1))
        assertEquals(listOf(a, b, c), FallbackChain.moveUp(listOf(a, b, c), 0))
        val full = (1..FallbackChain.MAX).map { ModelRef("p", "$it") }
        assertEquals(full, FallbackChain.add(full, ModelRef("p", "extra")))
        assertEquals("gpt-x (OpenAI)", FallbackChain.label(openAi, "gpt-x"))
    }
}
