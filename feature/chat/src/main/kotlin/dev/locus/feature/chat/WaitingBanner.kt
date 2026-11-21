package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.agent.WaitingChat
import dev.locus.core.data.agent.WaitingChats
import dev.locus.core.data.agent.WaitingKind
import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.model.AppLockStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * A chat waiting for the user, as the banner may name it: [title] is null for a locked chat (counted,
 * never named, whether or not this session unlocked it: the banner is read at a glance, over another
 * chat) and while the lock's settings aren't read yet. Blank for a chat with no title yet.
 */
@Immutable
data class WaitingEntry(val chatId: String, val kind: WaitingKind, val title: String?)

/** The banner: how many chats wait, and the one that has waited longest, which a tap opens. */
@Immutable
data class WaitingBannerState(
    val count: Int,
    val oldest: WaitingEntry,
    /** Home only: these are beyond the chat its featured card shows waiting, so the title says "more". */
    val more: Boolean = false,
)

/** [chats] as the banner may show them under [lock], titles from [titles]. Pure. */
internal fun waitingEntries(chats: List<WaitingChat>, titles: Map<String, String>, lock: AppLockStatus?): List<WaitingEntry> =
    chats.map { c ->
        val hidden = lock == null || lock.isLockedChat(c.chatId)
        WaitingEntry(c.chatId, c.kind, if (hidden) null else titles[c.chatId].orEmpty())
    }

/**
 * The banner for a screen that already shows [exclude] (the chat you're in, or home's featured card for a
 * chat waiting on an approval); null when no other chat waits. [entries] are oldest first. With [sayMore]
 * (home, under its featured card), the title says "more" when a chat was left out for being shown already. Pure.
 */
internal fun waitingBanner(entries: List<WaitingEntry>, exclude: Set<String>, sayMore: Boolean = false): WaitingBannerState? {
    val others = entries.filterNot { it.chatId in exclude }
    return others.firstOrNull()?.let { WaitingBannerState(others.size, it, more = sayMore && others.size < entries.size) }
}

/** Chats waiting for the user, for the banner on home and in a chat (docs/design/approvals-waiting.md). */
@HiltViewModel
class WaitingChatsViewModel @Inject constructor(waiting: WaitingChats, conversations: ConversationRepository, appLock: AppLock) : ViewModel() {
    val entries: StateFlow<List<WaitingEntry>> = combine(
        waiting.chats,
        // Helpers' asks land in their parent chat, so the list of every chat with its helpers is enough.
        conversations.allWithHelpers.map { all -> all.associate { it.id to it.title } }.distinctUntilChanged(),
        appLock.status,
        ::waitingEntries,
    ).distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * "2 chats are waiting for you": a compact caution card at the top of home and of a chat, for chats
 * other than the one on screen that wait on an approval, a question or a budget pause. A tap opens the
 * one that has waited longest; nothing is answered from here. A locked chat is counted but never named.
 */
@Composable
internal fun WaitingChatsBanner(state: WaitingBannerState, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val caution = Tone.Caution.color()
    LocusCard(
        modifier.fillMaxWidth(),
        onClick = { onOpen(state.oldest.chatId) },
        outline = caution.copy(alpha = Emphasis.toneOutline),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            StatusDot(Tone.Caution)
            Column(Modifier.weight(1f)) {
                Text(pluralStringResource(if (state.more) R.plurals.chat_waiting_more_title else R.plurals.chat_waiting_title, state.count, state.count), style = LocusType.cardTitle)
                SupportingText(waitingDetail(state), maxLines = 2)
            }
            Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.medium))
        }
    }
}

/** "“Trip plan” · Needs your approval", or "Oldest: a locked chat · Has a question" when there are more. */
@Composable
private fun waitingDetail(state: WaitingBannerState): String {
    val oldest = state.oldest
    val title = oldest.title
    val one = state.count == 1
    val name = when {
        title == null -> stringResource(if (one) R.string.chat_waiting_locked else R.string.chat_waiting_oldest_locked)
        title.isBlank() -> stringResource(if (one) R.string.chat_waiting_untitled else R.string.chat_waiting_oldest_untitled)
        else -> stringResource(if (one) R.string.chat_waiting_named else R.string.chat_waiting_oldest, title)
    }
    val why = stringResource(
        when (oldest.kind) {
            WaitingKind.APPROVAL -> R.string.chat_waiting_kind_approval
            WaitingKind.QUESTION -> R.string.chat_waiting_kind_question
            WaitingKind.BUDGET -> R.string.chat_waiting_kind_budget
        },
    )
    return stringResource(R.string.chat_waiting_detail, name, why)
}

/** The banner sliding in and out as chats start and stop waiting; [modifier] spaces it when shown. */
@Composable
internal fun WaitingChatsSlot(state: WaitingBannerState?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    // The last state is kept while the banner animates out, so its text doesn't go blank on the way.
    val last = androidx.compose.runtime.remember { arrayOfNulls<WaitingBannerState>(1) }
    if (state != null) last[0] = state
    androidx.compose.animation.AnimatedVisibility(
        state != null,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
    ) {
        (state ?: last[0])?.let { WaitingChatsBanner(it, onOpen, modifier) }
    }
}
