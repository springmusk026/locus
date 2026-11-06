package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.ChatSizes
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.onContainer
import dev.locus.core.model.AppSettings
import dev.locus.core.model.LocalNetwork
import dev.locus.core.model.SettingsDestination

/**
 * Where a chat's provider drafts stand now: the ones still waiting (by host), and the hosts of the
 * providers the user has (an approved draft is one of them). Read from settings, never from the transcript.
 */
data class ProviderDraftStatus(
    /** Pending drafts this chat made: host to draft id. */
    val pending: Map<String, String> = emptyMap(),
    /** Hosts of the user's providers. */
    val providerHosts: Set<String> = emptySet(),
) {
    sealed interface State {
        data class Waiting(val draftId: String) : State
        data object Added : State
        data object Gone : State
    }

    fun of(host: String): State = pending[host]?.let(State::Waiting) ?: if (host in providerHosts) State.Added else State.Gone

    companion object {
        fun of(s: AppSettings, conversationId: String) = ProviderDraftStatus(
            pending = s.providerDrafts.filter { it.conversationId == conversationId }.associate { it.host to it.id },
            providerHosts = s.providers.mapNotNull { LocalNetwork.hostOf(it.baseUrl) }.toSet(),
        )
    }
}

/** This chat's [ProviderDraftStatus]; empty in previews, where every card reads as waiting on nothing. */
internal val LocalProviderDrafts = compositionLocalOf { ProviderDraftStatus() }

/**
 * The card under a turn that drafted a provider (docs/design/provider-drafts.md): its name and where it
 * would send requests, whether it still needs review, and Review, which opens the review sheet in Settings →
 * Models. Approving happens there only; nothing on this card makes the provider usable.
 */
@Composable
internal fun ProviderDraftCard(item: ChatItem.ProviderDraft, modifier: Modifier = Modifier, status: ProviderDraftStatus.State? = null) {
    val state = status ?: LocalProviderDrafts.current.of(item.host)
    val links = Locus.settings
    val large = LocalDensity.current.fontScale >= 1.5f
    val (pill, tone) = when (state) {
        is ProviderDraftStatus.State.Waiting -> "Needs review" to Tone.Caution
        ProviderDraftStatus.State.Added -> "Added" to Tone.Live
        ProviderDraftStatus.State.Gone -> "Discarded" to Tone.Neutral
    }
    LocusCard(modifier.fillMaxWidth().widthIn(max = ChatSizes.mediaCard), contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                LocusIcons.Server,
                tint = if (state is ProviderDraftStatus.State.Waiting) Tone.Caution.onContainer() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(start = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(item.name, style = LocusType.rowTitle, maxLines = 2)
                SupportingText("Provider draft · ${item.host}", maxLines = 2)
                if (large) Box(Modifier.padding(top = Spacing.xxs)) { StatusPill(pill, tone) }
            }
            if (!large) StatusPill(pill, tone)
        }
        SupportingText(
            when (state) {
                is ProviderDraftStatus.State.Waiting -> "Not used by any chat until you add the key and approve it in Settings → Models."
                ProviderDraftStatus.State.Added -> "You approved it; it's in Settings → Models."
                ProviderDraftStatus.State.Gone -> "Nothing was sent to ${item.host}."
            },
            Modifier.padding(horizontal = Spacing.md).padding(top = Spacing.sm),
        )
        if (state == ProviderDraftStatus.State.Gone) Box(Modifier.padding(bottom = Spacing.md))
        else Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is ProviderDraftStatus.State.Waiting -> TextButton(
                    enabled = links != null,
                    onClick = {
                        dev.locus.core.data.providers.ProviderDraftLinks.open(state.draftId)
                        links?.open(SettingsDestination.Models)
                    },
                ) { Text("Review") }
                ProviderDraftStatus.State.Added -> TextButton(enabled = links != null, onClick = { links?.open(SettingsDestination.Models) }) { Text("Open Models") }
                ProviderDraftStatus.State.Gone -> Unit
            }
        }
    }
}
