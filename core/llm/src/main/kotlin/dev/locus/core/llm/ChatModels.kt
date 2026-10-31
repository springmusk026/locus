package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import io.ktor.client.HttpClient

object ChatModels {
    fun create(http: HttpClient, config: ProviderConfig, apiKey: String?): ChatModel = when (config.format) {
        ApiFormat.ANTHROPIC_MESSAGES -> AnthropicChatModel(
            http,
            config,
            apiKey ?: throw LlmException("${config.name} needs an API key. Add one in Settings → Providers."),
        )
        ApiFormat.OPENAI_CHAT -> OpenAiChatModel(http, config, apiKey)
        ApiFormat.OPENAI_RESPONSES -> OpenAiResponsesModel(http, config, apiKey)
        ApiFormat.ON_DEVICE -> throw LlmException("On-device models are created by the app, not over HTTP.")
    }
}
