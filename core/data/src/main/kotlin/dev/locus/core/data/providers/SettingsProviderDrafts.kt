package dev.locus.core.data.providers

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.tools.ProviderDraftStore
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.model.ProviderDraft

/**
 * provider_draft's store (docs/design/provider-drafts.md): the settings file's `providerDrafts`, which
 * nothing routes requests to. There is deliberately no way here to approve one; that is
 * [SettingsRepository.approveProviderDraft], called only from the review sheet in Settings → Models.
 */
class SettingsProviderDrafts(
    private val settings: SettingsRepository,
    private val conversations: ConversationRepository?,
    private val vault: suspend () -> Collection<String>,
) : ProviderDraftStore {
    override suspend fun save(draft: ProviderDraft): ProviderDraft = try {
        settings.addProviderDraft(draft)
    } catch (e: IllegalStateException) {
        throw ToolException(e.message.orEmpty())
    }

    override suspend fun chatTitle(conversationId: String): String? = conversations?.get(conversationId)?.title?.takeIf { it.isNotBlank() }

    override suspend fun vaultValues(): Collection<String> = vault()
}
