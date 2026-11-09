package dev.locus.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.data.mcp.PendingTool
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.DiffLine
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone

/** The badge on a connector whose server changed or added tools since the user approved it. */
@Composable
internal fun toolChangesBadge(pending: List<PendingTool>): String? = when {
    pending.isEmpty() -> null
    pending.all { it.change == PendingTool.Change.NEW } -> pluralStringResource(R.plurals.library_toolchanges_badge_new, pending.size)
    else -> stringResource(R.string.library_toolchanges_badge_changed)
}

/** One line for the connector's row: what waits and that chats don't get it yet. */
@Composable
internal fun toolChangesSummary(pending: List<PendingTool>): String {
    val new = pending.count { it.change == PendingTool.Change.NEW }
    val changed = pending.size - new
    return when {
        new == 0 -> pluralStringResource(R.plurals.library_toolchanges_summary_changed, changed, changed)
        changed == 0 -> pluralStringResource(R.plurals.library_toolchanges_summary_new, new, new)
        else -> stringResource(
            R.string.library_toolchanges_summary_both,
            pluralStringResource(R.plurals.library_toolchanges_count_changed, changed, changed),
            pluralStringResource(R.plurals.library_toolchanges_count_new, new, new),
        )
    }
}

/** The connector row's banner with Review changes. */
@Composable
internal fun ToolChangesBanner(pending: List<PendingTool>, onReview: () -> Unit) {
    Banner(toolChangesSummary(pending), Tone.Caution, title = toolChangesBadge(pending)) {
        TextButton(onClick = onReview) { Text(stringResource(R.string.library_toolchanges_review)) }
    }
}

/**
 * What changed in [server]'s tools since the user approved them, as diffs: each tool's description and
 * arguments, the old lines red, the new green. Approve lets new chats use them; chats already open keep
 * what they had (docs/design/mcp-connectors.md, "Tool pinning").
 */
@Composable
internal fun McpToolChangesSheet(server: String, pending: List<PendingTool>, onApprove: () -> Unit, onDismiss: () -> Unit) {
    // Approve sits under the diffs, not beside the title: it's read first, then decided.
    ConfirmSheet(
        title = stringResource(R.string.library_toolchanges_sheet_title),
        message = stringResource(R.string.library_toolchanges_sheet_body, server),
        confirmLabel = pluralStringResource(R.plurals.library_toolchanges_approve, pending.size),
        dismissLabel = stringResource(R.string.library_action_not_now),
        tone = Tone.Caution,
        onConfirm = onApprove,
        onDismiss = onDismiss,
    ) {
        pending.forEach { tool -> ToolChange(tool) }
        SupportingText(stringResource(R.string.library_toolchanges_until_approved))
    }
}

@Composable
private fun ToolChange(tool: PendingTool) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(tool.name, Modifier.weight(1f, fill = false), style = LocusType.code)
            LocusChip(
                when (tool.change) {
                    PendingTool.Change.NEW -> stringResource(R.string.library_toolchanges_chip_new)
                    PendingTool.Change.CHANGED -> stringResource(if (tool.onlyHintsChanged) R.string.library_toolchanges_chip_hints_changed else R.string.library_toolchanges_chip_changed)
                    PendingTool.Change.REVIEW -> stringResource(R.string.library_toolchanges_chip_check_again)
                },
                tone = if (tool.change == PendingTool.Change.NEW) Tone.Brand else Tone.Caution,
            )
        }
        if (tool.change == PendingTool.Change.REVIEW) {
            SupportingText(stringResource(R.string.library_toolchanges_record_changed))
        }
        if (tool.onlyHintsChanged) {
            SupportingText(stringResource(R.string.library_toolchanges_only_hints))
        }
        tool.hiddenInDescription.takeIf { it > 0 }?.let { n ->
            Text(
                pluralStringResource(R.plurals.library_toolchanges_hidden_characters, n, n),
                style = LocusType.supporting,
                color = Locus.colors.onCautionContainer,
            )
        }
        val resources = LocalResources.current
        val moreLines = { n: Int -> resources.getQuantityString(R.plurals.library_toolchanges_more_lines, n, n) }
        val description = compactDiff(tool.descriptionDiff(), more = moreLines)
        if (description.isNotEmpty()) DiffBlock(stringResource(R.string.library_toolchanges_diff_description), description)
        val schema = compactDiff(tool.schemaDiff(), more = moreLines)
        if (schema.isNotEmpty()) DiffBlock(stringResource(R.string.library_toolchanges_diff_arguments), schema)
    }
}

@Composable
private fun DiffBlock(label: String, lines: List<String>) {
    SupportingText(label)
    CodeSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = Spacing.xs)) {
            lines.forEach { DiffLine(it) }
        }
    }
}

/**
 * [lines] ("+ ", "- ", "  " diff lines) with unchanged runs cut to [context] lines around each change,
 * and at most [max] lines in all. A new tool (every line added) is shown whole up to [max]. [more] words
 * the "… N more lines" line for the N lines cut.
 */
internal fun compactDiff(lines: List<String>, context: Int = 1, max: Int = 60, more: (Int) -> String): List<String> {
    if (lines.none { it.startsWith("+") || it.startsWith("-") }) return emptyList()
    val changed = lines.indices.filter { lines[it][0] == '+' || lines[it][0] == '-' }
    val keep = changed.flatMap { (it - context)..(it + context) }.filter { it in lines.indices }.toSortedSet()
    val out = mutableListOf<String>()
    var last = -1
    for (i in keep) {
        if (last >= 0 && i > last + 1) out += "  …"
        out += lines[i]
        last = i
    }
    if (out.size <= max) return out
    return out.take(max) + "  ${more(out.size - max)}"
}
