package dev.locus.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.agent.ApprovalAnswer
import dev.locus.core.data.agent.ProposalApproval
import dev.locus.core.data.needsyou.NeedsYouFeed
import dev.locus.core.data.needsyou.ProposalItem
import dev.locus.core.data.needsyou.ProposalState
import dev.locus.core.data.needsyou.ResultItem
import dev.locus.core.data.needsyou.Waiting
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.WrappingStatusLine
import androidx.compose.foundation.layout.size
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.ui.ApprovalCard

/** What Needs you's buttons do (docs/design/needs-you.md); home wires them to [ConversationsViewModel]. */
internal class NeedsYouActions(
    /** A live approval answered in place, as in its chat. */
    val onAnswer: (callId: String, answer: ApprovalAnswer) -> Unit = { _, _ -> },
    val onApprove: (proposalId: String) -> Unit = {},
    val onDecline: (proposalId: String) -> Unit = {},
    /** Clears an expired, failed, interrupted or restored proposal. */
    val onDismiss: (proposalId: String) -> Unit = {},
    /** Opens a chat (a question, a budget pause, a locked chat's item, a settled proposal). */
    val onOpenChat: (chatId: String) -> Unit = {},
    /** Opens a result's chat and marks it read. */
    val onOpenResult: (ResultItem) -> Unit = {},
    /** Marks a result read without opening it. */
    val onMarkRead: (taskId: String) -> Unit = {},
    val onMarkAllRead: () -> Unit = {},
)

/** How many proposals and results show before "Show N more". */
private const val NEEDS_YOU_FOLD = 3

/** What a tap on Approve did, for the one feedback line. */
internal fun approvalMessage(result: ProposalApproval): Pair<UiText, Tone> = when (result) {
    ProposalApproval.Ran -> UiText.Res(R.string.chat_needs_you_ran) to Tone.Live
    is ProposalApproval.Failed -> UiText.Res(R.string.chat_needs_you_ran_failed) to Tone.Critical
    is ProposalApproval.Blocked -> UiText.Res(R.string.chat_needs_you_blocked) to Tone.Caution
    ProposalApproval.Expired -> UiText.Res(R.string.chat_needs_you_expired_message) to Tone.Caution
    ProposalApproval.Gone -> UiText.Res(R.string.chat_needs_you_gone) to Tone.Neutral
    ProposalApproval.Busy -> UiText.Res(R.string.chat_needs_you_busy) to Tone.Caution
}

/**
 * Home's Needs you section: what is waiting in a chat now (answered in place, or a tap away), what scheduled and
 * triggered runs proposed (approved or declined on the same card a live approval uses), and their results since the
 * user last looked. A locked chat's item says only that a locked chat needs the user; the tap opens it, which asks.
 */
internal fun LazyListScope.needsYouSection(feed: NeedsYouFeed, actions: NeedsYouActions, now: Long) {
    if (feed.isEmpty) return
    item(key = "needs-you-label") {
        SectionLabel(
            stringResource(R.string.chat_needs_you_title),
            action = stringResource(R.string.chat_needs_you_mark_all_read).takeIf { feed.results.isNotEmpty() },
            onAction = actions.onMarkAllRead.takeIf { feed.results.isNotEmpty() },
        )
    }
    // Live approvals are answered in place on the chat's own card; questions and budget pauses open their chat.
    val cards = feed.waiting.filterIsInstance<Waiting.Approval>().filterNot { it.redacted }
    cards.forEach { w ->
        item(key = w.key) { LiveApprovalCard(w, actions, Modifier.padding(bottom = Spacing.sm)) }
    }
    val rows = feed.waiting - cards.toSet()
    if (rows.isNotEmpty()) {
        item(key = "needs-you-waiting") {
            CardGroup(Modifier.padding(bottom = Spacing.sm)) {
                rows.forEachIndexed { i, w ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    WaitingRow(w, actions)
                }
            }
        }
    }
    if (feed.proposals.isNotEmpty()) {
        item(key = "needs-you-proposals") { ProposalList(feed.proposals, actions, now) }
    }
    if (feed.results.isNotEmpty()) {
        item(key = "needs-you-results") { ResultList(feed.results, actions) }
    }
}

@Composable
private fun LiveApprovalCard(w: Waiting.Approval, actions: NeedsYouActions, modifier: Modifier = Modifier) {
    ApprovalCard(
        w.request,
        onAnswer = { id, allowed -> actions.onAnswer(id, if (allowed) ApprovalAnswer.ALLOW else ApprovalAnswer.DENY) },
        onRemember = actions.onAnswer,
        modifier = modifier,
        byline = w.chatTitle.takeIf { it.isNotBlank() }?.let { stringResource(R.string.chat_needs_you_in_chat, it) },
        bringIntoView = false,
    )
}

/** A locked chat's item: its place and that it waits, nothing of what it is. */
@Composable
private fun LockedRow(status: String, tone: Tone, chatId: String, actions: NeedsYouActions) {
    ListRow(
        title = stringResource(R.string.chat_lock_hidden_title),
        onClick = { actions.onOpenChat(chatId) },
        leading = { IconTile(LocusIcons.Lock, size = IconSize.tile, contentDescription = stringResource(R.string.chat_lock_locked_description)) },
        meta = { WrappingStatusLine(status, tone) },
    )
}

@Composable
private fun WaitingRow(w: Waiting, actions: NeedsYouActions) {
    if (w.redacted) {
        LockedRow(stringResource(R.string.chat_needs_you_locked_waiting), Tone.Caution, w.chatId, actions)
        return
    }
    when (w) {
        is Waiting.Question -> ListRow(
            title = w.question.question,
            titleMaxLines = 2,
            onClick = { actions.onOpenChat(w.chatId) },
            leading = { IconTile(LocusIcons.Chats, size = IconSize.tile) },
            meta = {
                StatusLine(stringResource(R.string.chat_needs_you_question), Tone.Caution)
                if (w.chatTitle.isNotBlank()) SupportingText(w.chatTitle, maxLines = 1)
            },
        )
        is Waiting.Budget -> ListRow(
            title = w.chatTitle.ifBlank { stringResource(R.string.chat_needs_you_untitled) },
            onClick = { actions.onOpenChat(w.chatId) },
            leading = { IconTile(LocusIcons.Gauge, size = IconSize.tile) },
            meta = { StatusLine(stringResource(R.string.chat_needs_you_budget), Tone.Caution) },
        )
        // A live approval from a chat this session has opened shows as its card, above.
        is Waiting.Approval -> ListRow(
            title = w.chatTitle,
            onClick = { actions.onOpenChat(w.chatId) },
            meta = { StatusLine(stringResource(R.string.chat_home_needs_your_approval), Tone.Caution) },
        )
    }
}

@Composable
private fun ProposalList(items: List<ProposalItem>, actions: NeedsYouActions, now: Long) {
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) items else items.take(NEEDS_YOU_FOLD)
    androidx.compose.foundation.layout.Column {
        shown.forEach { item -> ProposalEntry(item, actions, now, Modifier.padding(bottom = Spacing.sm)) }
        FoldToggle(items.size, all) { all = !all }
    }
}

@Composable
private fun ProposalEntry(item: ProposalItem, actions: NeedsYouActions, now: Long, modifier: Modifier = Modifier) {
    val p = item.proposal
    if (item.redacted) {
        CardGroup(modifier) {
            LockedRow(
                stringResource(R.string.chat_needs_you_locked_proposal),
                if (item.state == ProposalState.WAITING) Tone.Caution else Tone.Neutral,
                p.conversationId,
                actions,
            )
        }
        return
    }
    when (item.state) {
        ProposalState.WAITING -> ApprovalCard(
            p.request(),
            onAnswer = { id, allowed -> if (allowed) actions.onApprove(id) else actions.onDecline(id) },
            // A proposal is approved once: its card offers nothing to remember.
            onRemember = { id, _ -> actions.onApprove(id) },
            modifier = modifier,
            title = stringResource(R.string.chat_needs_you_proposes, p.source),
            byline = proposedLine(p.createdAt, p.expiresAt, now),
            allowLabel = stringResource(R.string.chat_needs_you_approve),
            denyLabel = stringResource(R.string.chat_needs_you_decline),
            bringIntoView = false,
        )
        ProposalState.EXPIRED -> ApprovalCard(
            p.request(),
            onAnswer = { _, _ -> },
            onRemember = { _, _ -> },
            modifier = modifier,
            title = stringResource(R.string.chat_needs_you_proposed, p.source),
            byline = stringResource(R.string.chat_needs_you_expired, relativeTime(p.expiresAt, now)),
            answerable = false,
            outlined = false,
            bringIntoView = false,
            footer = { TextButton(onClick = { actions.onDismiss(p.id) }) { Text(stringResource(R.string.chat_needs_you_dismiss)) } },
        )
        else -> CardGroup(modifier) { SettledRow(item, actions) }
    }
}

/** "Proposed 2 hours ago · expires in 6 days". */
@Composable
private fun proposedLine(createdAt: Long, expiresAt: Long, now: Long): String {
    val proposed = relativeTime(createdAt, now)
    val days = ((expiresAt - now) / DAY_MS).toInt()
    return if (days < 1) stringResource(R.string.chat_needs_you_expires_today, proposed)
    else pluralStringResource(R.plurals.chat_needs_you_expires_in, days, days, proposed)
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** A proposal that was approved and is running, or that ended without running as planned. */
@Composable
private fun SettledRow(item: ProposalItem, actions: NeedsYouActions) {
    val p = item.proposal
    val (status, tone) = when (item.state) {
        ProposalState.RUNNING -> stringResource(R.string.chat_needs_you_running) to Tone.Live
        ProposalState.FAILED -> stringResource(R.string.chat_needs_you_failed) to Tone.Critical
        ProposalState.INTERRUPTED -> stringResource(R.string.chat_needs_you_interrupted) to Tone.Caution
        else -> stringResource(R.string.chat_needs_you_stale) to Tone.Neutral
    }
    ListRow(
        title = p.summary.lineSequence().first(),
        onClick = { actions.onOpenChat(p.conversationId) },
        leading = {
            when (item.state) {
                ProposalState.RUNNING -> Box(Modifier.size(IconSize.tile), contentAlignment = Alignment.Center) { InlineSpinner(size = IconSize.medium) }
                ProposalState.FAILED -> IconTile(LocusIcons.Error, size = IconSize.tile, tint = Tone.Critical.color())
                else -> IconTile(LocusIcons.Skip, size = IconSize.tile)
            }
        },
        meta = {
            WrappingStatusLine(status, tone)
            SupportingText(p.source, maxLines = 1)
        },
        trailing = if (item.state == ProposalState.RUNNING) null else {
            {
                IconButton(onClick = { actions.onDismiss(p.id) }) {
                    Icon(LocusIcons.Close, contentDescription = stringResource(R.string.chat_needs_you_dismiss), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
private fun ResultList(items: List<ResultItem>, actions: NeedsYouActions) {
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) items else items.take(NEEDS_YOU_FOLD)
    androidx.compose.foundation.layout.Column {
        CardGroup(Modifier.padding(bottom = Spacing.xs)) {
            shown.forEachIndexed { i, r ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                ResultRow(r, actions)
            }
        }
        FoldToggle(items.size, all) { all = !all }
    }
}

@Composable
private fun ResultRow(r: ResultItem, actions: NeedsYouActions) {
    if (r.redacted) {
        LockedRow(stringResource(R.string.chat_needs_you_result_new), Tone.Brand, r.chatId, actions)
        return
    }
    val resources = LocalResources.current
    val (icon, tone) = when (r.outcome) {
        TaskRunOutcome.FAILED -> LocusIcons.Error to Tone.Critical
        TaskRunOutcome.STOPPED -> LocusIcons.Skip to Tone.Caution
        else -> LocusIcons.Check to Tone.Live
    }
    val line = r.receipt?.let { receiptSummary(resources, it) } ?: stringResource(
        when (r.outcome) {
            TaskRunOutcome.FAILED -> R.string.chat_needs_you_result_failed
            TaskRunOutcome.STOPPED -> R.string.chat_needs_you_result_stopped
            else -> R.string.chat_needs_you_result_done
        },
    )
    ListRow(
        title = r.title,
        onClick = { actions.onOpenResult(r) },
        leading = { IconTile(if (r.eventTriggered) LocusIcons.Sparkle else icon, size = IconSize.tile, tint = tone.color()) },
        meta = {
            r.summary?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { SupportingText(it, maxLines = 2) }
            SupportingText("$line · ${relativeTime(r.at)}", maxLines = 1)
        },
        trailing = {
            IconButton(onClick = { actions.onMarkRead(r.taskId) }) {
                Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_needs_you_mark_read, r.title), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** "Show 4 more" under a folded list, "Show less" once it's open; nothing when everything fits. */
@Composable
private fun FoldToggle(total: Int, open: Boolean, onToggle: () -> Unit) {
    if (total <= NEEDS_YOU_FOLD) return
    TextButton(onClick = onToggle, modifier = Modifier.padding(start = Spacing.xs)) {
        Text(if (open) stringResource(R.string.chat_needs_you_show_less) else pluralStringResource(R.plurals.chat_needs_you_show_more, total - NEEDS_YOU_FOLD, total - NEEDS_YOU_FOLD))
    }
}
