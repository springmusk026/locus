package dev.locus.feature.chat

import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The model pill's name: never the "gguf" of an on-device model's id. */
class ComposerModelNameTest {
    private val phone = chatProvider(ProviderConfig("p", "On this phone", ApiFormat.ON_DEVICE, "", "gguf:qwen3.5-0.8b-q4_k_m", requiresKey = false))
    private val anthropic = chatProvider(ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"))
    private val lists = mapOf(
        "p" to ModelList.Loaded(
            listOf(
                RemoteModel("gguf:qwen3.5-0.8b-q4_k_m", "Qwen3.5 0.8B", detail = "Q4_K_M · 0.5 GB · llama.cpp"),
                RemoteModel("gguf:gemma-3-1b-it-q8_0", "Gemma 3 1B", detail = "Q8_0 · 1.1 GB · llama.cpp"),
            ),
        ),
        "a" to ModelList.Loaded(listOf(RemoteModel("claude-opus-5-5", "Claude Opus 5.5"))),
    )

    @Test
    fun `an on-device model takes the name the model sheet gives it`() {
        assertEquals("Gemma 3 1B", onDeviceModelName(phone, "gguf:gemma-3-1b-it-q8_0", lists))
        assertEquals("Qwen3.5 0.8B", onDeviceModelName(phone, "gguf:qwen3.5-0.8b-q4_k_m", lists))
        // Not in the list (yet): null, and the pill falls back to the id's tidied name.
        assertNull(onDeviceModelName(phone, "gguf:other-q4_k_m", lists))
        assertNull(onDeviceModelName(phone, "gguf:qwen3.5-0.8b-q4_k_m", mapOf("p" to ModelList.Loading)))
        assertNull(onDeviceModelName(phone, "", lists))
        // A hosted provider keeps its short id in the pill, even when its list has a longer name.
        assertNull(onDeviceModelName(anthropic, "claude-opus-5-5", lists))
        assertNull(onDeviceModelName(null, "gguf:qwen3.5-0.8b-q4_k_m", lists))
    }

    @Test
    fun `the pill uses the listed name, then the shortened id, then the provider`() {
        val gemma = ChatControls(model = "gguf:gemma-3-1b-it-q8_0", modelName = onDeviceModelName(phone, "gguf:gemma-3-1b-it-q8_0", lists))
        assertEquals("Gemma 3 1B", composerModelName(gemma, phone))
        assertEquals("Qwen3.5 0.8B", composerModelName(ChatControls(model = "gguf:qwen3.5-0.8b-q4_k_m"), phone))
        assertEquals("claude-opus-5-5", composerModelName(ChatControls(model = "claude-opus-5-5"), anthropic))
        assertEquals("Anthropic", composerModelName(ChatControls(model = ""), anthropic))
    }
}
