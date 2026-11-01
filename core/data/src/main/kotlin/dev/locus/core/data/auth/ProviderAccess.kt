package dev.locus.core.data.auth

import dev.locus.core.agent.auth.ChatGptAuthException
import dev.locus.core.data.network.ProviderHttpClients
import dev.locus.core.data.network.ScrubbingChatModel
import dev.locus.core.datastore.SecretStore
import dev.locus.core.llm.BearerSource
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatModels
import dev.locus.core.llm.KeyPool
import dev.locus.core.llm.KeyPoolChatModel
import dev.locus.core.llm.parseApiKeys
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
    private val secrets: SecretStore,
    private val chatGpt: ChatGptSignIn,
    /** Each provider's HTTP client, with its extra headers, proxy and trusted CAs (Settings → Network). */
    private val clients: ProviderHttpClients,
) {
    suspend fun apiKey(providerId: String): String? = secrets.get(SecretStore.providerKey(providerId))

    /** The provider's backup keys, tried after its own key runs out (see [KeyPool]). Empty for none. */
    suspend fun backupKeys(providerId: String): List<String> =
        secrets.get(SecretStore.providerBackupKeys(providerId))?.let(::parseApiKeys).orEmpty()

    /** Which key each provider is on and which are resting; process-wide, so every chat shares it. */
    private val keyPool = KeyPool()

    /** Why [provider] can't answer yet (no key, not signed in), or null when it can. */
    suspend fun missing(provider: ProviderConfig): String? = when {
        // Saved keys locked (an unusable Keystore key, or before the first unlock): say so, not "no key".
        (provider.signsIn || provider.requiresKey) && secrets.unavailableReason() != null -> secrets.unavailableReason()
        provider.signsIn -> if (provider.id in chatGpt.signedIn.first()) null else "${provider.name} isn't signed in. Continue with ChatGPT in Settings → Models."
        provider.requiresKey && apiKey(provider.id).isNullOrBlank() -> "${provider.name} has no API key. Add it in Settings → Models."
        else -> null
    }

    /** A cloud model for [provider]. On-device models come from the on-device engines instead. */
    suspend fun chatModel(provider: ProviderConfig): ChatModel {
        val client = clients.clientFor(provider)
        val model = ProviderCredentials(client.http, ::apiKey, ::bearer, ::backupKeys, keyPool).chatModel(provider)
        return if (client.secretValues.isEmpty()) model else ScrubbingChatModel(model, client.secretValues)
    }

    /** The provider's models; [typedKey] is a key typed in the editor but not saved yet. */
    suspend fun listModels(config: ProviderConfig, typedKey: String?): List<RemoteModel> {
        val credential = if (config.signsIn) bearer(config.id).token() else typedKey ?: apiKey(config.id)
        val client = clients.clientFor(config)
        return try {
            ModelLister.list(client.http, config, credential)
        } catch (e: LlmException) {
            if (client.secretValues.isEmpty()) throw e
            throw LlmException(dev.locus.core.data.network.scrubSecrets(e.message.orEmpty(), client.secretValues), e.httpStatus, e.retryable)
        }
    }

    private fun bearer(providerId: String) = BearerSource {
        try {
            chatGpt.accessToken(providerId)
        } catch (e: ChatGptAuthException) {
            throw LlmException(e.message ?: "The ChatGPT sign-in failed.", httpStatus = 401, cause = e)
        }
    }
}

/**
 * Builds a provider's model with that provider's own credential, looked up by its id on every call:
 * the key saved for it, or its ChatGPT sign-in. Nothing is cached across providers, so a chat that
 * switches provider can't carry one provider's key to another. Split out of [ProviderAccess] so the
 * isolation is testable without Android.
 */
internal class ProviderCredentials(
    private val http: HttpClient,
    private val apiKey: suspend (providerId: String) -> String?,
    private val bearer: (providerId: String) -> BearerSource,
    /** The provider's backup keys; with any, requests go through a [KeyPoolChatModel]. */
    private val backupKeys: suspend (providerId: String) -> List<String> = { emptyList() },
    private val pool: KeyPool = KeyPool(),
) {
    suspend fun chatModel(provider: ProviderConfig): ChatModel {
        if (provider.signsIn) return ChatModels.create(http, provider, apiKey = null, bearer = bearer(provider.id))
        val own = apiKey(provider.id)
        val keys = (listOfNotNull(own?.takeIf { it.isNotBlank() }) + if (provider.requiresKey) backupKeys(provider.id) else emptyList()).distinct()
        if (keys.size < 2) return ChatModels.create(http, provider, own)
        return KeyPoolChatModel(provider.format, provider.id, keys, pool) { key -> ChatModels.create(http, provider, key) }
    }
}
