package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one model-name formatter every screen uses: never "gguf", never a raw `gguf:` id. */
class ModelNamesTest {
    @Test
    fun `a GGUF id is shortened to its tidied name, not to gguf`() {
        assertEquals("Qwen3.5 0.8B", ModelNames.short("gguf:qwen3.5-0.8b-q4_k_m"))
        assertEquals("Gemma 3 1B it", ModelNames.short("gguf:gemma-3-1b-it-q8_0"))
        assertEquals("Llama 3.2 3B instruct", ModelNames.short(" gguf:llama-3.2-3b-instruct-q4_k_m "))
        // A slug the import couldn't name still reads as itself.
        assertEquals("Model", ModelNames.short("gguf:model"))
    }

    @Test
    fun `hosted ids are shortened as before`() {
        assertEquals("claude-sonnet-5-5", ModelNames.short("anthropic/claude-sonnet-5-5-20260301"))
        assertEquals("llama3.2", ModelNames.short("llama3.2:latest"), "an Ollama tag is still dropped")
        assertEquals("gpt-5", ModelNames.short("openai/gpt-5"))
        assertEquals("gemma-4-e2b", ModelNames.short("gemma-4-e2b"))
    }

    @Test
    fun `the exact id is offered only when the short name differs`() {
        // A GGUF id is a local slug: never on screen, only in a copied report.
        assertNull(ModelNames.idIfDifferent("gguf:qwen3.5-0.8b-q4_k_m"))
        assertEquals("gguf:qwen3.5-0.8b-q4_k_m", ModelNames.idIfDifferent("gguf:qwen3.5-0.8b-q4_k_m", forReport = true))
        assertEquals("anthropic/claude-sonnet-5-5-20260301", ModelNames.idIfDifferent("anthropic/claude-sonnet-5-5-20260301"))
        assertNull(ModelNames.idIfDifferent("claude-opus-5-5"))
        assertNull(ModelNames.idIfDifferent(""))
    }

    @Test
    fun `GGUF header and file names are tidied`() {
        assertEquals("Qwen3.5 0.8B", ModelNames.gguf("Qwen3.5-0.8B-Q4_K_M.gguf"))
        assertEquals("Gemma 3n E2B it", ModelNames.gguf("gemma-3n-e2b-it"))
        assertEquals("Q4_K_M", ModelNames.quantInFileName("qwen2.5-1.5b-instruct-q4_k_m.gguf"))
        assertNull(ModelNames.quantInFileName("gemma-3-1b-it"))
    }

    @Test
    fun `fallback notes name a model the same way`() {
        val phone = ProviderConfig("d", "On this phone", ApiFormat.ON_DEVICE, "", "gguf:qwen3.5-0.8b-q4_k_m", requiresKey = false)
        assertEquals("Qwen3.5 0.8B (On this phone)", FallbackChain.label(phone, phone.model))
        val openAi = ProviderConfig("o", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-x")
        assertEquals("gpt-x (OpenAI)", FallbackChain.label(openAi, "gpt-x"))
    }
}
