package dev.locus.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.agent.TaskReceipts
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
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
    TaskOutcome.BUDGET, TaskOutcome.REFUSED, TaskOutcome.STEP_LIMIT, TaskOutcome.INTERRUPTED -> Tone.Caution
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
 * A finished task at a glance, under its last turn: one line ("Done · 4m 12s · 3 files · tests passed ·
 * 2 approvals · ≈$0.04"); a tap opens the rest. Files and checks scroll to their cards; each approval
 * count opens the matching Activity entries. See docs/design/task-receipt.md.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReceiptCard(item: ChatItem.Receipt, actions: ReceiptActions, modifier: Modifier = Modifier) {
    val r = item.receipt
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val tone = receiptTone(r.outcome)
    val summary = TaskReceipts.summary(r)

    LocusCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide details" else "Show details") { open = !open }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .semantics {
                    contentDescription = "Task receipt: $summary"
                    stateDescription = if (open) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (r.outcome) {
                    TaskOutcome.DONE -> LocusIcons.Check
                    TaskOutcome.FAILED -> LocusIcons.Close
                    TaskOutcome.BUDGET -> LocusIcons.Gauge
                    else -> LocusIcons.Stop
                },
                contentDescription = null,
                tint = tone.color(),
                modifier = Modifier.size(16.dp),
            )
            Text(
                summary,
                Modifier.weight(1f).padding(horizontal = Spacing.sm),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                LocusIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(if (open) 90f else 0f),
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xs)) {
                GroupDivider(inset = Spacing.md)
                Field("Outcome", TaskReceipts.outcomeLine(r), color = if (tone == Tone.Neutral) null else tone)
                Field("Time", TaskReceipts.duration(r) + if (r.steps > 0) " · ${r.steps} ${if (r.steps == 1) "step" else "steps"}" else "")
                TaskReceipts.modelsLabel(r)?.let { Field("Models", it) }
                if (r.tokens.requests > 0) Field("Tokens", TaskReceipts.tokensLabel(r))
                TaskReceipts.costLabel(r)?.let { cost ->
                    Field("Cost", if (r.cost == null) "Unknown: models.dev lists no price for these models" else "$cost · estimate from models.dev prices")
                }
                if (r.files > 0) {
                    Field("Files", "${r.files} changed", onClick = item.changesKey?.let { key -> { actions.showCard(key) } })
                }
                if (r.checks.isNotEmpty()) {
                    Field(
                        "Checks", TaskReceipts.checksDetail(r),
                        color = if (r.checks.any { it.passed == false }) Tone.Critical else null,
                        onClick = item.checkKey?.let { key -> { actions.showCard(key) } },
                    )
                }
                val links = approvalLinks(r)
                if (links.isNotEmpty()) {
                    FieldRow("Approvals") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            links.forEach { link -> LinkChip(link.text) { actions.openActivity(link.filter) } }
                        }
                    }
                }
                if (r.tainted) {
                    Field(
                        "Outside content",
                        r.outsideContent.joinToString(", ") + if (r.taintedFromStart) " · read earlier in this chat too" else "",
                        color = Tone.Caution,
                    )
                }
                if (r.helpers.isNotEmpty()) Field("Helpers", r.helpers.joinToString(", "))
                r.workflow?.let { w ->
                    Field("Workflow", w.name)
                    w.checks.forEach { c ->
                        val mark = when (c.passed) { true -> "Passed"; false -> "Failed"; null -> "Not checked" }
                        Field(
                            c.check.label, "$mark · ${c.note}",
                            color = when (c.passed) { true -> Tone.Live; false -> Tone.Critical; null -> Tone.Caution },
                        )
                    }
                }
                actions.saveWorkflow?.takeIf { r.outcome == TaskOutcome.DONE }?.let { save ->
                    androidx.compose.material3.TextButton(onClick = { save(r.taskId) }, modifier = Modifier.padding(horizontal = Spacing.xs)) {
                        Icon(LocusIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(if (r.workflow != null) "Save as a new workflow" else "Save as workflow", Modifier.padding(start = Spacing.xs))
                    }
                }
            }
        }
    }
}

/** A label and its value; tappable (with a chevron) when it links somewhere. */
@Composable
private fun Field(label: String, value: String, color: Tone? = null, onClick: (() -> Unit)? = null) {
    FieldRow(label, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = color?.color() ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (onClick != null) {
                Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            Modifier.weight(0.32f).padding(end = Spacing.sm),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(Modifier.weight(0.68f)) { content() }
    }
}

/** A small tappable count, in the brand colour, that opens Activity. */
@Composable
private fun LinkChip(text: String, onClick: () -> Unit) {
    val brand = MaterialTheme.colorScheme.primary
    Text(
        text,
        Modifier.clip(RoundedCornerShape(Radius.chip))
            .background(brand.copy(alpha = 0.10f))
            .clickable(role = Role.Button, onClickLabel = "Show in Activity", onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        style = MaterialTheme.typography.labelMedium,
        color = brand,
        maxLines = 1,
    )
}
