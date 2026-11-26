package dev.locus.feature.chat

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.usdAmount
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.ModelLogo
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.ModelRef

/** The compact label above an answer of a turn with alternatives: whose answer it is ("gpt-5.2", "… · read-only"). */
@Composable
internal fun AnswerModelLabel(model: ModelRef, readOnly: Boolean) {
    val name = modelLabel(model)
    LocusChip(
        if (readOnly) stringResource(R.string.chat_compare_label_read_only, name) else name,
        modifier = Modifier.padding(bottom = Spacing.xs),
        icon = LocusIcons.Layers.takeIf { readOnly },
    )
}

/**
 * Under an answer of a Compare turn: "Keep this one" makes it the one you keep (the shown branch is already the
 * chat's continuation; this counts the pick), or "Kept" once it is. The other answer stays one switch away.
 */
@Composable
internal fun KeepAnswerRow(pick: ComparePick, enabled: Boolean, onKeep: () -> Unit) {
    if (pick.kept) {
        LocusChip(stringResource(R.string.chat_compare_kept), modifier = Modifier.padding(top = Spacing.xs), tone = Tone.Brand, icon = LocusIcons.Check)
    } else {
        // Pulled back so its icon starts on the text's edge, as the turn's Regenerate does.
        TextButton(onClick = onKeep, enabled = enabled, modifier = Modifier.offset(x = -Spacing.md)) {
            Icon(LocusIcons.Check, contentDescription = null, modifier = Modifier.size(IconSize.small))
            Text(stringResource(R.string.chat_compare_keep), Modifier.padding(start = Spacing.xs))
        }
    }
}

/** Above the streaming answer while the second model writes: whose it is, and that it only reads. */
@Composable
internal fun ComparingLabel(model: String) {
    LocusChip(stringResource(R.string.chat_compare_streaming, model), tone = Tone.Live, icon = LocusIcons.Layers)
}

/** The top of the model sheet when it picks the second model: what Compare does, and Stop comparing. */
@Composable
internal fun CompareSheetHeader(onClear: (() -> Unit)?) {
    SectionNote(stringResource(R.string.chat_compare_rule), Modifier.padding(bottom = Spacing.sm))
    if (onClear != null) {
        LocusCard(Modifier.padding(bottom = Spacing.sm), contentPadding = PaddingValues(0.dp)) {
            dev.locus.core.designsystem.ListRow(
                title = stringResource(R.string.chat_compare_stop),
                subtitle = stringResource(R.string.chat_compare_stop_hint),
                subtitleMaxLines = 3,
                onClick = onClear,
                leading = { Icon(LocusIcons.Close, contentDescription = null, modifier = Modifier.size(IconSize.medium)) },
            )
        }
    }
}

/** "$0.012", "< $0.001" or "unknown": one model's estimate, in the confirm sheet. */
internal fun estimateLabel(resources: Resources, usd: Double?): String = when {
    usd == null -> resources.getString(R.string.chat_compare_price_unknown)
    usd < 0.001 -> resources.getString(R.string.chat_compare_price_tiny, usdAmount(resources.configuration.locales[0], 0.001, fractionDigits = 3))
    // Money the language's way ("0,012 US$" in Spanish); the estimate itself stays in dollars.
    else -> usdAmount(resources.configuration.locales[0], usd, fractionDigits = if (usd < 0.1) 3 else 2)
}

/**
 * Before a Compare send: both models with their estimated input cost, what that covers, that the second model's
 * provider writes the whole prompt to its cache, and that the second model can't change anything. Shown the first
 * time, and later when the estimate is over the threshold.
 */
@Composable
internal fun CompareConfirmSheet(confirm: CompareConfirm, onSend: () -> Unit, onCancel: () -> Unit) {
    val resources = LocalResources.current
    ConfirmSheet(
        title = stringResource(R.string.chat_compare_confirm_title),
        confirmLabel = stringResource(R.string.chat_compare_confirm_send),
        onConfirm = onSend,
        onDismiss = onCancel,
        message = stringResource(if (confirm.first) R.string.chat_compare_confirm_first else R.string.chat_compare_confirm_over),
    ) {
        LocusCard(contentPadding = PaddingValues(0.dp)) {
            EstimateRow(confirm.chat, stringResource(R.string.chat_compare_confirm_chat_role), estimateLabel(resources, confirm.estimate.chat))
            GroupDivider(inset = Spacing.lg)
            EstimateRow(confirm.other, stringResource(R.string.chat_compare_confirm_other_role), estimateLabel(resources, confirm.estimate.other))
        }
        SupportingText(
            stringResource(
                R.string.chat_compare_confirm_covers,
                dev.locus.core.llm.Fit.tokensLabel(confirm.estimate.promptTokens),
            ),
        )
        SupportingText(stringResource(R.string.chat_compare_confirm_cache, confirm.other.label))
        SupportingText(stringResource(R.string.chat_compare_rule))
    }
}

@Composable
private fun EstimateRow(choice: CompareChoice, role: String, cost: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        ModelLogo(choice.ref.model, size = IconSize.tileSmall)
        Column(Modifier.weight(1f).padding(start = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(choice.label, style = LocusType.rowTitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
            SupportingText(role, maxLines = Int.MAX_VALUE)
        }
        Text(cost, Modifier.padding(start = Spacing.sm), style = LocusType.mono)
    }
}
