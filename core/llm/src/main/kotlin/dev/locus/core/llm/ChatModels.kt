package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import io.ktor.client.HttpClient

object ChatModels {
    /** [bearer] supplies the token per request for providers that sign in ([ApiFormat.CHATGPT]) instead of using [apiKey]. */
    fun create(http: HttpClient, config: ProviderConfig, apiKey: String?, bearer: BearerSource? = null): ChatModel = when (config.format) {
        ApiFormat.ANTHROPIC_MESSAGES -> AnthropicChatModel(
            http,
            config,
            apiKey ?: throw LlmException("${config.name} needs an API key. Add one in Settings → Providers."),
        )
        ApiFormat.OPENAI_CHAT -> OpenAiChatModel(http, config, apiKey)
        ApiFormat.OPENAI_RESPONSES -> OpenAiResponsesModel(http, config, apiKey)
        ApiFormat.CHATGPT -> OpenAiResponsesModel(
            http,
            config,
            apiKey = null,
            bearer = bearer ?: throw LlmException("${config.name} isn't signed in. Continue with ChatGPT in Settings → Models."),
        )
        ApiFormat.ON_DEVICE -> throw LlmException("On-device models are created by the app, not over HTTP.")
    }
}
