package dev.locus.core.data.auth

import dev.locus.core.agent.auth.ChatGptAuthException
import dev.locus.core.datastore.SecretStore
import dev.locus.core.llm.BearerSource
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatModels
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.ModelLister
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ProviderConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place model requests get their credentials: the saved API key, or for ChatGPT providers
 * a fresh access token from [ChatGptSignIn] on every request (runs can outlast the hour a token
 * lives). Agent runs, helpers, the reviewer, sub-agents and model lists all come through here.
 */
@Singleton
class ProviderAccess @Inject constructor(
    private val http: HttpClient,
    private val secrets: SecretStore,
    private val chatGpt: ChatGptSignIn,
) {
    suspend fun apiKey(providerId: String): String? = secrets.get(SecretStore.providerKey(providerId))

    /** Why [provider] can't answer yet (no key, not signed in), or null when it can. */
    suspend fun missing(provider: ProviderConfig): String? = when {
        provider.signsIn -> if (provider.id in chatGpt.signedIn.first()) null else "${provider.name} isn't signed in. Continue with ChatGPT in Settings → Models."
        provider.requiresKey && apiKey(provider.id).isNullOrBlank() -> "${provider.name} has no API key. Add it in Settings → Models."
        else -> null
    }

    /** A cloud model for [provider]. On-device models come from the on-device engines instead. */
    suspend fun chatModel(provider: ProviderConfig): ChatModel =
        if (provider.signsIn) ChatModels.create(http, provider, apiKey = null, bearer = bearer(provider.id)) else ChatModels.create(http, provider, apiKey(provider.id))

    /** The provider's models; [typedKey] is a key typed in the editor but not saved yet. */
    suspend fun listModels(config: ProviderConfig, typedKey: String?): List<RemoteModel> {
        val credential = if (config.signsIn) bearer(config.id).token() else typedKey ?: apiKey(config.id)
        return ModelLister.list(http, config, credential)
    }

    private fun bearer(providerId: String) = BearerSource {
        try {
            chatGpt.accessToken(providerId)
        } catch (e: ChatGptAuthException) {
            throw LlmException(e.message ?: "The ChatGPT sign-in failed.", httpStatus = 401, cause = e)
        }
    }
}
