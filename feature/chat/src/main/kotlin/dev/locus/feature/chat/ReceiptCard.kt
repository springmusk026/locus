package dev.locus.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.locus.core.agent.TaskReceipts
import androidx.compose.ui.draw.clip
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.compactCountEnglish
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.ReceiptApprovals
import dev.locus.core.model.TaskActivityFilter
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskReceipt

/** Where a receipt's links go; supplied by the chat screen. */
class ReceiptActions(
    /** Scrolls the chat to the card with this key (the changes card, a verification card). */
    val showCard: (key: String) -> Unit = {},
    /** Opens Activity on this task's matching entries. */
    val openActivity: (TaskActivityFilter) -> Unit = {},
    /** Drafts a workflow from this task (its id) for review; null where that isn't offered. */
    val saveWorkflow: ((taskId: String) -> Unit)? = null,
)

/** The tone of a receipt's outcome: done is calm, a failure is critical, anything cut short asks for a look. */
internal fun receiptTone(outcome: TaskOutcome): Tone = when (outcome) {
    TaskOutcome.DONE -> Tone.Live
    TaskOutcome.FAILED -> Tone.Critical
    TaskOutcome.STOPPED -> Tone.Neutral
    TaskOutcome.BUDGET, TaskOutcome.REFUSED, TaskOutcome.STEP_LIMIT, TaskOutcome.INTERRUPTED, TaskOutcome.TOOL_LOOP -> Tone.Caution
}

/**
 * The receipt line's tone: its outcome's, except a task that finished while a check failed, which
 * asks for a look (caution) rather than reading as all well.
 */
internal fun receiptLineTone(r: TaskReceipt): Tone =
    if (r.outcome == TaskOutcome.DONE && r.checks.any { it.passed == false }) Tone.Caution else receiptTone(r.outcome)

/** The receipt's Outcome row, with a provider's raw failure ("HTTP 529: Overloaded") said in words. */
internal fun receiptOutcomeText(r: TaskReceipt): String {
    val detail = r.detail?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    return if (r.outcome == TaskOutcome.FAILED && detail != null) {
        "${TaskReceipts.outcomeLabel(r)}: ${providerFailureInWords(detail).removeSuffix(".")}"
    } else {
        TaskReceipts.outcomeLine(r)
    }
}

/** A provider's failure in words, with what to do: the status codes people meet most, else the message without ids. */
internal fun providerFailureInWords(message: String): String = when {
    Regex("""\bHTTP 529\b|\boverloaded\b""", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
        "The provider was overloaded. Try again later, or switch model."
    Regex("""\bHTTP 429\b""").containsMatchIn(message) && !message.contains("quota", ignoreCase = true) ->
        "The provider's rate limit was reached. Wait a minute and try again, or switch model."
    else -> failureMessageInWords(message)
}

/** One approval line of a receipt: what it says, and the Activity entries it opens. */
internal data class ApprovalLink(val text: String, val filter: TaskActivityFilter)

/** "3 asked", "2 you approved", "1 you declined", "1 blocked", each opening its entries in Activity; only those above zero. */
internal fun approvalLinks(r: TaskReceipt): List<ApprovalLink> {
    fun link(n: Int, text: String, label: String, decisions: Set<ApprovalDecision>) =
        if (n > 0) ApprovalLink("$n $text", TaskActivityFilter(r.conversationId, r.startedAt, r.endedAt, decisions, label)) else null
    val a = r.approvals
    return listOfNotNull(
        link(a.asked, "asked", "Asked you", ReceiptApprovals.ASKED),
        link(a.approved, "you approved", "You approved", ReceiptApprovals.APPROVED),
        link(a.declined, "you declined", "You declined", ReceiptApprovals.DECLINED),
        link(a.blocked, "blocked", "Blocked", ReceiptApprovals.BLOCKED),
    )
}

/**
 * A reply's meta line: the finished task's time and cost ([receipt]), then this turn's tokens
 * ("8.7k in · 15 out · 64% cached") and an on-device reply's [speed]. One line instead of a line and a
 * receipt card; null when there's nothing to say.
 */
internal fun replyMeta(usage: TurnUsage?, speed: String?, receipt: TaskReceipt?): String? {
    val parts = buildList {
        receipt?.let { r ->
            add(TaskReceipts.duration(r))
            TaskReceipts.costLabel(r)?.let(::add)
        }
        usage?.let { u ->
            add("${compactCountEnglish(u.lastInput.toLong())} in")
            add("${compactCountEnglish(u.output)} out")
            if (u.cacheRead > 0 && u.input > 0) add("${u.cacheRead * 100 / u.input}% cached")
        }
        speed?.let(::add)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * A task that didn't simply finish with a reply (failed, stopped, paused at its budget, cut short, or
 * ended without a reply), as one quiet status line under its turn: "Failed · 12s · ≈$0.03". A tap opens
 * its receipt. A task that finished with a reply has no line: its reply's meta line carries it.
 * See docs/design/task-receipt.md.
 */
@Composable
internal fun ReceiptLine(item: ChatItem.Receipt, actions: ReceiptActions, modifier: Modifier = Modifier) {
    val r = item.receipt
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val tone = receiptLineTone(r)
    val summary = TaskReceipts.summary(r)
    val taskLabel = stringResource(R.string.chat_receipt_task, summary)
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = Sizes.touchTarget)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_receipt_show_details)) { open = true }
            .semantics { contentDescription = taskLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                tone == Tone.Caution && r.outcome == TaskOutcome.DONE -> LocusIcons.Alert
                else -> when (r.outcome) {
                TaskOutcome.DONE -> LocusIcons.Check
                TaskOutcome.FAILED -> LocusIcons.Close
                TaskOutcome.BUDGET -> LocusIcons.Gauge
                else -> LocusIcons.Stop
                }
            },
            contentDescription = null,
            tint = tone.color(),
            modifier = Modifier.size(IconSize.small),
        )
        // A sentence, not an id: the supporting role, and room for its approvals at a large font.
        SupportingText(summary, Modifier.weight(1f).padding(horizontal = Spacing.sm), maxLines = 3)
        Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(IconSize.small))
    }
    if (open) ReceiptSheet(r, actions, onDismiss = { open = false })
}

/**
 * A task's receipt in full: outcome, time and steps, models, tokens, cost, files, checks, approvals,
 * outside content, helpers and workflow checks. Files and checks close the sheet and scroll to their
 * cards; each approval count opens the matching Activity entries.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReceiptSheet(r: TaskReceipt, actions: ReceiptActions, onDismiss: () -> Unit) {
    val tone = receiptTone(r.outcome)
    val item = ChatItem.Receipt("receipt:${r.taskId}", r)
    LocusSheet(title = stringResource(R.string.chat_receipt_title), subtitle = TaskReceipts.summary(r), onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
                Field(stringResource(R.string.chat_receipt_outcome), receiptOutcomeText(r), color = if (tone == Tone.Neutral) null else tone)
                Field(stringResource(R.string.chat_receipt_time), TaskReceipts.duration(r) + if (r.steps > 0) " · " + pluralStringResource(R.plurals.chat_receipt_steps, r.steps, r.steps) else "")
                TaskReceipts.modelsLabel(r)?.let { Field(stringResource(R.string.chat_receipt_models), it) }
                if (r.tokens.requests > 0) Field(stringResource(R.string.chat_receipt_tokens), TaskReceipts.tokensLabel(r))
                TaskReceipts.costLabel(r)?.let { cost ->
                    Field(stringResource(R.string.chat_receipt_cost), stringResource(if (r.cost == null) R.string.chat_receipt_cost_unknown else R.string.chat_receipt_cost_estimate, cost))
                }
                if (r.files > 0) {
                    Field(stringResource(R.string.chat_receipt_files), stringResource(R.string.chat_receipt_files_changed, r.files), onClick = item.changesKey?.let { key -> { onDismiss(); actions.showCard(key) } })
                }
                if (r.checks.isNotEmpty()) {
                    Field(
                        stringResource(R.string.chat_receipt_checks), TaskReceipts.checksDetail(r),
                        // All failed: critical. Some passed: caution, so the passed count doesn't read as failed.
                        color = when {
                            r.checks.none { it.passed == false } -> null
                            r.checks.all { it.passed == false } -> Tone.Critical
                            else -> Tone.Caution
                        },
                        onClick = item.checkKey?.let { key -> { onDismiss(); actions.showCard(key) } },
                    )
                }
                val links = approvalLinks(r)
                if (links.isNotEmpty()) {
                    FieldRow(stringResource(R.string.chat_receipt_approvals)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            links.forEach { link -> LinkChip(link.text) { actions.openActivity(link.filter) } }
                        }
                    }
                }
                if (r.tainted) {
                    Field(
                        stringResource(R.string.chat_receipt_outside),
                        r.outsideContent.joinToString(", ") + if (r.taintedFromStart) " · " + stringResource(R.string.chat_receipt_outside_earlier) else "",
                        color = Tone.Caution,
                    )
                }
                // One helper a line: its model and what it cost.
                TaskReceipts.helpersLabel(r, separator = "\n")?.let { Field(stringResource(R.string.chat_receipt_helpers), it) }
                TaskReceipts.completionCheckLabel(r)?.let { Field(stringResource(R.string.chat_receipt_completion), it) }
                r.workflow?.let { w ->
                    Field(stringResource(R.string.chat_receipt_workflow), w.name)
                    w.checks.forEach { c ->
                        val mark = stringResource(when (c.passed) { true -> R.string.chat_receipt_passed; false -> R.string.chat_receipt_failed; null -> R.string.chat_receipt_not_checked })
                        Field(
                            c.check.label, "$mark · ${c.note}",
                            color = when (c.passed) { true -> Tone.Live; false -> Tone.Critical; null -> Tone.Caution },
                        )
                    }
                }
                actions.saveWorkflow?.takeIf { r.outcome == TaskOutcome.DONE }?.let { save ->
                    androidx.compose.material3.TextButton(onClick = { onDismiss(); save(r.taskId) }, modifier = Modifier.padding(horizontal = Spacing.xs)) {
                        Icon(LocusIcons.Play, contentDescription = null, modifier = Modifier.size(IconSize.small))
                        Text(stringResource(if (r.workflow != null) R.string.chat_receipt_save_new_workflow else R.string.chat_receipt_save_workflow), Modifier.padding(start = Spacing.xs))
                    }
                }
        }
    }
}

/** A label and its value; tappable (with a chevron) when it links somewhere. */
@Composable
private fun Field(label: String, value: String, color: Tone? = null, onClick: (() -> Unit)? = null) {
    // Values read whole: an outcome or a list of sources is cut nowhere.
    KeyValueRow(label, value, tone = color, dense = true, onClick = onClick, valueMaxLines = Int.MAX_VALUE)
}

@Composable
private fun FieldRow(label: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Spacing.xs),
    ) {
        // The same columns as a dense KeyValueRow, and on the first chip's baseline.
        SupportingText(label, Modifier.weight(0.32f).padding(end = Spacing.sm).alignByBaseline(), maxLines = 2)
        Column(Modifier.weight(0.68f).alignByBaseline()) { content() }
    }
}

/** A small tappable count, in the brand colour, that opens Activity. */
@Composable
private fun LinkChip(text: String, onClick: () -> Unit) {
    LocusChip(text, tone = Tone.Brand, onClick = onClick, contentDescription = stringResource(R.string.chat_receipt_link_description, text))
}
