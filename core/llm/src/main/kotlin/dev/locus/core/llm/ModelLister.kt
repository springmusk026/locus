package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI

/**
 * One model of a provider's list, with what the provider itself reports about it. Every ability is
 * null when the provider doesn't say (see [ModelCapabilityResolver] for what fills the gaps):
 *
 * - [contextWindow]: Anthropic `max_input_tokens`, OpenRouter `context_length`, Gemini `inputTokenLimit`.
 * - [tools], [reasoning], [structuredOutput]: OpenRouter's `supported_parameters` (`tools`,
 *   `reasoning` / `include_reasoning` / `reasoning_effort`, `structured_outputs`); Anthropic's
 *   `capabilities.thinking` and `capabilities.structured_outputs`; Gemini's `thinking`.
 * - [vision]: OpenRouter's `architecture.input_modalities` containing `image`; Anthropic's
 *   `capabilities.image_input`.
 */
data class RemoteModel(
    val id: String,
    val displayName: String = id,
    val contextWindow: Int? = null,
    val tools: Boolean? = null,
    val vision: Boolean? = null,
    val reasoning: Boolean? = null,
    val structuredOutput: Boolean? = null,
)

/**
 * Lists the models a provider offers so users pick instead of typing IDs:
 * OpenAI-compatible servers (OpenAI, OpenRouter, Groq, Gemini, Ollama, LM Studio, vLLM) answer
 * `GET {base}/models` with `data[].id`; Anthropic answers `GET /v1/models` with `data[].id` and
 * `display_name`. ChatGPT sign-ins answer `GET /v1/models` with `models[]` (`slug`, `display_name`,
 * `visibility`), listed in the server's order (developers.openai.com/siwc/token-sharing-open-source/models-and-inference).
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
            ApiFormat.CHATGPT -> return chatGpt(http, config, apiKey)
            ApiFormat.ON_DEVICE -> return emptyList()
        }
        response.throwIfFailed(config.name)
        val data = (LlmJson.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.get("data") as? JsonArray
            ?: throw LlmException("${config.name} didn't return a model list.")
        val anthropicFirstParty = config.format == ApiFormat.ANTHROPIC_MESSAGES &&
            base.equals(AnthropicChatModel.DEFAULT_BASE_URL, ignoreCase = true)
        val listed = data.mapNotNull { it as? JsonObject }
            .onEach { if (anthropicFirstParty) AnthropicCapabilities.record(it) }
            .mapNotNull(::remoteModel)
            .filterNot { isNonChat(it.id) }
            .distinctBy { it.id }
            .sortedBy { it.id }
        // Gemini's OpenAI-compatible list has ids only; its native list has each model's limits.
        if (isGemini(base) && !apiKey.isNullOrBlank()) {
            val details = geminiDetails(http, apiKey)
            if (details.isNotEmpty()) {
                return listed.map { m ->
                    details[m.id]?.let { d -> m.copy(contextWindow = m.contextWindow ?: d.contextWindow, reasoning = m.reasoning ?: d.reasoning) } ?: m
                }
            }
        }
        return listed
    }

    /** One entry of an OpenAI-compatible or Anthropic `data` list, with whatever abilities it reports. */
    internal fun remoteModel(m: JsonObject): RemoteModel? {
        // Gemini's compatibility layer prefixes ids with "models/".
        val id = m.string("id")?.removePrefix("models/") ?: return null
        // OpenRouter (openrouter.ai/docs/guides/overview/models): the parameters a model accepts, and its input modalities.
        val params = (m["supported_parameters"] as? JsonArray)?.mapNotNull { it.stringOrNull() }?.toSet()
        val inputs = ((m["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray)?.mapNotNull { it.stringOrNull() }
        // Anthropic (platform.claude.com/docs/en/api/models/list): `capabilities.<name>.supported`.
        val caps = m["capabilities"] as? JsonObject
        fun cap(name: String): Boolean? = ((caps?.get(name) as? JsonObject)?.get("supported") as? JsonPrimitive)?.booleanOrNull
        return RemoteModel(
            id = id,
            displayName = m.string("display_name") ?: m.string("name") ?: id,
            contextWindow = m.int("max_input_tokens") ?: m.int("context_length"),
            tools = params?.let { "tools" in it },
            vision = inputs?.let { "image" in it } ?: cap("image_input"),
            reasoning = params?.let { p -> REASONING_PARAMS.any { it in p } } ?: cap("thinking"),
            structuredOutput = params?.let { "structured_outputs" in it } ?: cap("structured_outputs"),
        )
    }

    private val REASONING_PARAMS = setOf("reasoning", "include_reasoning", "reasoning_effort")

    private const val GEMINI_HOST = "generativelanguage.googleapis.com"

    private fun isGemini(base: String) = runCatching { URI(base).host }.getOrNull().equals(GEMINI_HOST, ignoreCase = true)

    /**
     * Gemini's native `models.list` (ai.google.dev/api/models): `inputTokenLimit` and `thinking` ("Whether
     * the model supports thinking") per model, by id. Best effort: empty when it fails or is slow.
     */
    internal suspend fun geminiDetails(http: HttpClient, apiKey: String): Map<String, RemoteModel> = try {
        withTimeoutOrNull(GEMINI_TIMEOUT_MS) {
            val response = http.get("https://$GEMINI_HOST/v1beta/models?pageSize=1000") { header("x-goog-api-key", apiKey) }
            if (response.status.value !in 200..299) return@withTimeoutOrNull emptyMap()
            val models = (LlmJson.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.get("models") as? JsonArray
            models.orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { m ->
                val id = m.string("name")?.removePrefix("models/") ?: return@mapNotNull null
                id to RemoteModel(id, contextWindow = m.int("inputTokenLimit"), reasoning = (m["thinking"] as? JsonPrimitive)?.booleanOrNull)
            }.toMap()
        }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    private const val GEMINI_TIMEOUT_MS = 5_000L

    /** [accessToken] is the ChatGPT sign-in's OAuth token. Only `visibility: "list"` entries are meant for pickers. */
    private suspend fun chatGpt(http: HttpClient, config: ProviderConfig, accessToken: String?): List<RemoteModel> {
        if (accessToken.isNullOrBlank()) throw LlmException("Continue with ChatGPT first to see your plan's models.")
        val response = http.get("${config.baseUrl.trimEnd('/')}/models") { header("Authorization", "Bearer $accessToken") }
        if (response.status.value !in 200..299) throw ChatGptErrors.fromHttp(response.status.value, response.bodyAsText().take(2000))
        val models = (LlmJson.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.get("models") as? JsonArray
            ?: throw LlmException("ChatGPT didn't return a model list.")
        return models.mapNotNull { it as? JsonObject }
            .filter { it.string("visibility") == "list" }
            .mapNotNull { m -> m.string("slug")?.let { slug -> RemoteModel(slug, m.string("display_name") ?: slug) } }
            .distinctBy { it.id }
    }

    /** Embedding, audio, image and moderation models can't drive an agent; hide them. */
    private fun isNonChat(id: String): Boolean {
        val l = id.lowercase()
        return listOf("embed", "whisper", "tts", "dall-e", "moderation", "transcribe", "image", "rerank", "guard").any { it in l }
    }
}
