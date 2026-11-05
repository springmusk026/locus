package dev.locus.core.data.repository

import dev.locus.core.llm.Support
import dev.locus.core.model.effectiveModel
import javax.inject.Inject

/**
 * Whether a chat that starts on a model declares no tools ([dev.locus.core.model.Conversation.chatOnly]).
 *
 * A chat's tool list is part of its cached prompt prefix, so it's fixed once the first message is sent.
 * Before that it can still follow the model: a chat about to start on a model known not to call tools
 * (an on-device model too small to use them, [dev.locus.core.model.SmallModels], or one whose template
 * has none, or a cloud model its provider lists without tool calling) starts chat only, rather than
 * declaring tools it would call and be refused (docs/design/model-capabilities.md, "Small models and tools").
 * Unknown never counts as no.
 */
fun interface ChatOnlyRule {
    /** For [model] of [providerId] (null: the provider's default). */
    suspend fun startsChatOnly(providerId: String?, model: String?): Boolean

    companion object {
        /** Every chat declares its tools (tests that don't look at this). */
        val Never = ChatOnlyRule { _, _ -> false }
    }
}

/** [ChatOnlyRule] from what [ModelDirectory] knows of the model, without waiting on the network. */
class ModelChatOnlyRule @Inject constructor(
    private val settings: SettingsRepository,
    private val directory: ModelDirectory,
) : ChatOnlyRule {
    override suspend fun startsChatOnly(providerId: String?, model: String?): Boolean {
        val provider = settings.current().provider(providerId) ?: return false
        val id = provider.effectiveModel(model).ifBlank { return false }
        return directory.capabilities(provider, id).tools == Support.NO
    }
}
