package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.ModelLogo
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.ModelNames
import dev.locus.core.model.ModelRef
import dev.locus.core.model.PlanRun
import dev.locus.core.model.PlanRunEstimate
import dev.locus.core.model.Role

/*
 * Plan mode's "Run with" (docs/design/plan-run.md): the plan is made on the chat's model and run on another one
 * picked on the plan card. The pure parts first, so they're tested without a screen; then the composables.
 */

/**
 * The model sheet's sections for picking the model a plan runs on: the chat's own model is marked as such and
 * stays pickable (it's the default); models known not to serve the chat can't be picked; [runOn] is the row
 * marked selected, or the chat's own model when it's null.
 */
internal fun List<PickerSection>.forPlanRun(runOn: ModelRef?): List<PickerSection> = map { s ->
    s.copy(
        rows = s.rows.map { r ->
            r.copy(chatModel = r.selected, selected = if (runOn == null) r.selected else r.ref == runOn, pickable = r.selected || r.fit.usable)
        },
    )
}

/** An approval on another model held for the user's OK: both models, the first request's estimate, and why it asks. */
data class PlanRunConfirm(
    val chat: CompareChoice,
    val run: CompareChoice,
    val estimate: PlanRunEstimate,
    /** First time: the sheet explains what running on another model means. */
    val first: Boolean,
    /** The run model's provider's name when it isn't the chat's provider: the conversation goes somewhere new. Else null. */
    val otherProvider: String?,
)

/**
 * The estimate for the first request after approving in a chat whose last request used [contextTokens], on [run]
 * (a cache write: that model hasn't seen the chat) and on [chat] for comparison (its cache warm), from models.dev's
 * prices in [catalog] (null when it's off). Anthropic writes for an hour on a chat's own runs (docs/design/cost.md).
 */
internal fun planRunEstimate(
    providers: List<ChatProvider>,
    catalog: ModelCatalogIndex?,
    contextTokens: Int,
    chat: ModelRef,
    run: ModelRef,
): PlanRunEstimate {
    fun provider(ref: ModelRef) = providers.firstOrNull { it.id == ref.providerId }
    fun price(ref: ModelRef) = provider(ref)?.let { p -> catalog?.lookup(p.baseUrl, p.format, ref.model)?.price }
    val newTokens = (AgentControlTools.PLAN_APPROVED.length + 3) / 4
    return PlanRun.estimate(contextTokens, newTokens, price(chat), price(run), hourCache = provider(run)?.format == ApiFormat.ANTHROPIC_MESSAGES)
}

/**
 * The model that ran the plan proposed in message [planMessageId], when the user approved it: the first reply after
 * the approval message. Null when it wasn't approved, nothing answered yet, or the reply names no model.
 */
internal fun planRanOn(messages: List<Message>, planMessageId: String): ModelRef? {
    val at = messages.indexOfFirst { it.id == planMessageId }.takeIf { it >= 0 } ?: return null
    val after = messages.drop(at + 1)
    val approval = after.indexOfFirst { it.role == Role.USER && !ModeNote.isAgentNote(it) }.takeIf { it >= 0 } ?: return null
    if (after[approval].userText.trim() != AgentControlTools.PLAN_APPROVED) return null
    val reply = after.drop(approval + 1).firstOrNull { it.role == Role.ASSISTANT }?.reply ?: return null
    val model = reply.model?.takeIf { it.isNotBlank() } ?: return null
    return ModelRef(reply.providerId.orEmpty(), model)
}

/**
 * Approved plans in [items] get the model that ran them ([planRanOn]) where it isn't the one that planned them, so
 * the card can say "ran on …". The same list when nothing changes.
 */
internal fun List<ChatItem>.withPlanRuns(messages: List<Message>): List<ChatItem> {
    if (none { it is ChatItem.Plan && it.status == PlanStatus.Approved }) return this
    return map { item ->
        if (item !is ChatItem.Plan || item.status != PlanStatus.Approved) return@map item
        val ran = planRanOn(messages, item.key.substringBefore(':'))?.takeIf { it != item.plannedBy } ?: return@map item
        item.copy(ranOn = ran)
    }
}

/** The plan card's status for an approved plan: "Approved", or "Approved · ran on claude-haiku-5-5" when another model ran it. */
@Composable
internal fun approvedStatus(item: ChatItem.Plan): String =
    item.ranOn?.let { stringResource(R.string.chat_plan_ran_on, ModelNames.short(it.model)) } ?: stringResource(R.string.chat_plan_approved)

/**
 * On a waiting plan card: which model runs it once approved. This chat's model by default; tapping opens the
 * model sheet in plan-run mode. [chatModel] is the chat's model id, [runWith] the pick (null: the chat's own).
 */
@Composable
internal fun PlanRunRow(chatModel: String, runWith: CompareChoice?, onChange: () -> Unit) {
    SupportingText(stringResource(R.string.chat_plan_run_label), Modifier.padding(top = Spacing.md), style = LocusType.label)
    LocusCard(Modifier.padding(top = Spacing.xs), contentPadding = PaddingValues(0.dp)) {
        ListRow(
            title = runWith?.label ?: ModelNames.short(chatModel),
            subtitle = if (runWith == null) {
                stringResource(R.string.chat_plan_run_default_hint)
            } else {
                stringResource(R.string.chat_plan_run_other_hint, ModelNames.short(chatModel))
            },
            subtitleMaxLines = 3,
            titleMaxLines = 2,
            onClick = onChange,
            leading = { ModelLogo(runWith?.ref?.model ?: chatModel, size = IconSize.tileSmall) },
            trailing = { Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.small)) },
        )
    }
}

/** Above the streaming text while an approved plan runs on another model: which one. */
@Composable
internal fun PlanRunLabel(model: String) {
    LocusChip(stringResource(R.string.chat_plan_run_streaming, model), tone = Tone.Live, icon = LocusIcons.Note)
}

/** The top of the model sheet in plan-run mode: what picking a model here does. */
@Composable
internal fun PlanRunSheetHeader() {
    dev.locus.core.designsystem.SectionNote(stringResource(R.string.chat_plan_run_rule), Modifier.padding(bottom = Spacing.sm))
}

/**
 * Before a plan runs on another model: where the conversation goes (a provider this chat hasn't used), the first
 * request's estimate on the run model (a cache write) beside this chat's model (warm), what that covers, and that the
 * chat goes back to its own model afterwards. Always for another provider; the first time; and over the threshold.
 */
@Composable
internal fun PlanRunConfirmSheet(confirm: PlanRunConfirm, onRun: () -> Unit, onCancel: () -> Unit) {
    val resources = LocalResources.current
    ConfirmSheet(
        title = stringResource(R.string.chat_plan_run_confirm_title, modelLabel(confirm.run.ref)),
        confirmLabel = stringResource(R.string.chat_plan_approve),
        onConfirm = onRun,
        onDismiss = onCancel,
        message = when {
            confirm.otherProvider != null -> stringResource(R.string.chat_plan_run_confirm_provider, confirm.otherProvider)
            confirm.first -> stringResource(R.string.chat_plan_run_confirm_first, confirm.run.label)
            else -> stringResource(R.string.chat_plan_run_confirm_over)
        },
    ) {
        LocusCard(contentPadding = PaddingValues(0.dp)) {
            PlanEstimateRow(confirm.run, stringResource(R.string.chat_plan_run_confirm_run_role), estimateLabel(resources, confirm.estimate.run))
            GroupDivider(inset = Spacing.lg)
            PlanEstimateRow(confirm.chat, stringResource(R.string.chat_plan_run_confirm_chat_role), estimateLabel(resources, confirm.estimate.chatWarm))
        }
        SupportingText(stringResource(R.string.chat_plan_run_confirm_covers, dev.locus.core.llm.Fit.tokensLabel(confirm.estimate.promptTokens)))
        SupportingText(stringResource(R.string.chat_plan_run_confirm_back, confirm.chat.label))
    }
}

@Composable
private fun PlanEstimateRow(choice: CompareChoice, role: String, cost: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        ModelLogo(choice.ref.model, size = IconSize.tileSmall)
        Column(Modifier.weight(1f).padding(start = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(choice.label, style = LocusType.rowTitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
            SupportingText(role, maxLines = Int.MAX_VALUE)
        }
        Text(cost, Modifier.padding(start = Spacing.sm), style = LocusType.mono)
    }
}
