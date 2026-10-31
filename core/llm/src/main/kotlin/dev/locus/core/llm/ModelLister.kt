package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** [contextWindow] when the provider reports it: Anthropic `max_input_tokens`, OpenRouter `context_length`. */
data class RemoteModel(val id: String, val displayName: String = id, val contextWindow: Int? = null)

/**
 * Lists the models a provider offers so users pick instead of typing IDs:
 * OpenAI-compatible servers (OpenAI, OpenRouter, Groq, Gemini, Ollama, LM Studio, vLLM) answer
 * `GET {base}/models` with `data[].id`; Anthropic answers `GET /v1/models` with `data[].id` and
 * `display_name`.
 */
object ModelLister {
    suspend fun list(http: HttpClient, config: ProviderConfig, apiKey: String?): List<RemoteModel> {
        val base = config.baseUrl.trimEnd('/')
        val response = when (config.format) {
            ApiFormat.ANTHROPIC_MESSAGES -> http.get("$base/v1/models?limit=100") {
                header("x-api-key", apiKey.orEmpty())
                header("anthropic-version", AnthropicChatModel.API_VERSION)
            }
            ApiFormat.OPENAI_CHAT, ApiFormat.OPENAI_RESPONSES -> http.get("$base/models") {
                if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
            }
            ApiFormat.ON_DEVICE -> return emptyList()
        }
        response.throwIfFailed(config.name)
        val data = (LlmJson.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.get("data") as? JsonArray
            ?: throw LlmException("${config.name} didn't return a model list.")
        return data.mapNotNull { it as? JsonObject }
            .mapNotNull { m ->
                // Gemini's compatibility layer prefixes ids with "models/".
                val id = m.string("id")?.removePrefix("models/") ?: return@mapNotNull null
                RemoteModel(id, m.string("display_name") ?: m.string("name") ?: id, m.int("max_input_tokens") ?: m.int("context_length"))
            }
            .filterNot { isNonChat(it.id) }
            .distinctBy { it.id }
            .sortedBy { it.id }
    }

    /** Embedding, audio, image and moderation models can't drive an agent; hide them. */
    private fun isNonChat(id: String): Boolean {
        val l = id.lowercase()
        return listOf("embed", "whisper", "tts", "dall-e", "moderation", "transcribe", "image", "rerank", "guard").any { it in l }
    }
}
