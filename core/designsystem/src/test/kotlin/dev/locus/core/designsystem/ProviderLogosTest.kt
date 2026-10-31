package dev.locus.core.designsystem

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderLogosTest {
    @Test
    fun modelFamilies() {
        assertEquals(BrandLogo.Claude, modelLogo("claude-opus-4-6-thinking"))
        assertEquals(BrandLogo.Claude, modelLogo("anthropic/claude-sonnet-4.5"))
        assertEquals(BrandLogo.OpenAI, modelLogo("gpt-4o-mini"))
        assertEquals(BrandLogo.OpenAI, modelLogo("o3-mini"))
        assertEquals(BrandLogo.OpenAI, modelLogo("openai/o1"))
        assertEquals(BrandLogo.OpenAI, modelLogo("openai/gpt-oss-120b"))
        assertEquals(BrandLogo.Gemini, modelLogo("models/gemini-2.5-flash"))
        assertEquals(BrandLogo.Gemma, modelLogo("gemma-4-e2b"))
        assertEquals(BrandLogo.Meta, modelLogo("meta-llama/llama-4-scout-17b-16e-instruct"))
        assertEquals(BrandLogo.Qwen, modelLogo("qwen/qwen3-32b"))
        assertEquals(BrandLogo.DeepSeek, modelLogo("deepseek-r1-distill-llama-70b"))
        assertEquals(BrandLogo.Mistral, modelLogo("mixtral-8x7b"))
        assertEquals(BrandLogo.Grok, modelLogo("grok-4"))
        assertNull(modelLogo("moonshotai/kimi-k2-instruct"))
        assertNull(modelLogo(""))
    }

    @Test
    fun providers() {
        assertEquals(BrandLogo.Gemini, providerLogo("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/", false))
        assertEquals(BrandLogo.Groq, providerLogo("Groq", "https://api.groq.com/openai/v1", false))
        assertEquals(BrandLogo.OpenAI, providerLogo("OpenAI", "https://api.openai.com/v1", false))
        assertEquals(BrandLogo.Anthropic, providerLogo("Anthropic", "https://api.anthropic.com", false))
        assertEquals(BrandLogo.OpenRouter, providerLogo("OpenRouter", "https://openrouter.ai/api/v1", false))
        assertEquals(BrandLogo.Ollama, providerLogo("Local", "http://192.168.1.5:11434/v1", false))
        assertEquals(BrandLogo.LmStudio, providerLogo("LM Studio", "http://localhost:1234/v1", false))
        assertNull(providerLogo("My box", "https://example.com/v1", false))
        assertNull(providerLogo("Gemma", "", true))
    }
}
