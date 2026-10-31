package dev.locus.core.model

import kotlinx.serialization.Serializable

/** The wire protocol an endpoint speaks. */
@Serializable
enum class ApiFormat {
    /** OpenAI Chat Completions: OpenAI, OpenRouter, Groq, Ollama, LM Studio, llama.cpp server, vLLM. */
    OPENAI_CHAT,

    /** OpenAI Responses API, stateless, with reasoning carried across tool calls. */
    OPENAI_RESPONSES,

    /**
     * The user's ChatGPT plan (Sign in with ChatGPT): the Responses API with an OAuth access token
     * instead of an API key, under the preview's request rules (no `max_output_tokens`, function
     * tools in a namespace). See developers.openai.com/siwc/token-sharing-open-source.
     */
    CHATGPT,

    /** Anthropic Messages API. */
    ANTHROPIC_MESSAGES,

    /** A model running on the phone itself (LiteRT-LM). `model` is the model id from the on-device catalog. */
    ON_DEVICE,
}

/** A model endpoint the user brought. The API key is stored separately, encrypted. */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val format: ApiFormat,
    val baseUrl: String,
    val model: String,
    /** Reasoning effort: Anthropic `output_config.effort` or OpenAI Responses `reasoning.effort`. */
    val effort: String = "high",
    val maxOutputTokens: Int = 64_000,
    val requiresKey: Boolean = true,
    /** The model's context window in tokens, from its provider's model list or set by the user. */
    val contextWindow: Int? = null,
    /** Extra headers, proxy and trusted certificates for this provider (Settings → Models → provider → Network). */
    val network: ProviderNetwork = ProviderNetwork(),
) {
    /** ChatGPT providers sign in through the browser instead of taking a key. */
    val signsIn: Boolean get() = format == ApiFormat.CHATGPT

    /** Needs something from the user (a key or a sign-in) before it can answer. */
    val needsCredential: Boolean get() = requiresKey || signsIn
}

data class ProviderPreset(
    val name: String,
    val format: ApiFormat,
    val baseUrl: String,
    val model: String,
    val requiresKey: Boolean,
    val note: String,
)

object ProviderPresets {
    val all = listOf(
        ProviderPreset("On this phone (Gemma 4, offline)", ApiFormat.ON_DEVICE, "", "gemma-4-e2b", false, "Runs fully on the phone, no internet or key needed. Downloads a 2.6 GB model once."),
        ProviderPreset("Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5", true, "Claude models with your Anthropic API key."),
        ProviderPreset("ChatGPT (your plan)", ApiFormat.CHATGPT, "https://api.openai.com/v1", "", false, "Sign in with ChatGPT and use your Plus or Pro plan. No API key needed."),
        ProviderPreset("OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "", true, "OpenAI models with your API key, over the Responses API (keeps reasoning between steps)."),
        ProviderPreset("Google Gemini", ApiFormat.OPENAI_CHAT, "https://generativelanguage.googleapis.com/v1beta/openai", "", true, "Gemini models with a Google AI Studio key. Enter the model ID from ai.google.dev."),
        ProviderPreset("OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "", true, "Hundreds of models behind one key."),
        ProviderPreset("Groq", ApiFormat.OPENAI_CHAT, "https://api.groq.com/openai/v1", "", true, "Fast hosted open models."),
        ProviderPreset("Ollama (your network)", ApiFormat.OPENAI_CHAT, "http://192.168.1.10:11434/v1", "", false, "Replace the address with your computer's LAN IP."),
        ProviderPreset("LM Studio / llama.cpp (your network)", ApiFormat.OPENAI_CHAT, "http://192.168.1.10:1234/v1", "", false, "Any OpenAI-compatible server on your network."),
        ProviderPreset("Custom", ApiFormat.OPENAI_CHAT, "", "", true, "Any OpenAI-compatible endpoint."),
    )
}
