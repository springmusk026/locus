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
internal fun toolChangesBadge(pending: List<PendingTool>): String? = when {
    pending.isEmpty() -> null
    pending.all { it.change == PendingTool.Change.NEW } -> if (pending.size == 1) "New tool" else "New tools"
    else -> "Tools changed"
}

/** One line for the connector's row: what waits and that chats don't get it yet. */
internal fun toolChangesSummary(pending: List<PendingTool>): String {
    val new = pending.count { it.change == PendingTool.Change.NEW }
    val changed = pending.size - new
    val parts = listOfNotNull(
        changed.takeIf { it > 0 }?.let { "$it ${if (it == 1) "tool changed" else "tools changed"}" },
        new.takeIf { it > 0 }?.let { "$it new ${if (it == 1) "tool" else "tools"}" },
    )
    return parts.joinToString(" and ").replaceFirstChar { it.uppercase() } +
        " since you approved this server. Chats don't get ${if (pending.size == 1) "it" else "them"} until you review and approve."
}

/** The connector row's banner with Review changes. */
@Composable
internal fun ToolChangesBanner(pending: List<PendingTool>, onReview: () -> Unit) {
    Banner(toolChangesSummary(pending), Tone.Caution, title = toolChangesBadge(pending)) {
        TextButton(onClick = onReview) { Text("Review changes") }
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
        title = "Review tool changes",
        message = "“$server” describes these tools differently from when you approved it, or added them. " +
            "The description is what the AI reads, so a change can steer it. Approve only what you expect; it applies to new chats.",
        confirmLabel = if (pending.size == 1) "Approve" else "Approve all",
        dismissLabel = "Not now",
        tone = Tone.Caution,
        onConfirm = onApprove,
        onDismiss = onDismiss,
    ) {
        pending.forEach { tool -> ToolChange(tool) }
        SupportingText("Until you approve, chats can't call these tools. Removing the server under Connectors stops it entirely.")
    }
}

@Composable
private fun ToolChange(tool: PendingTool) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(tool.name, Modifier.weight(1f, fill = false), style = LocusType.code)
            LocusChip(
                when (tool.change) {
                    PendingTool.Change.NEW -> "New"
                    PendingTool.Change.CHANGED -> if (tool.onlyHintsChanged) "Hints changed" else "Changed"
                    PendingTool.Change.REVIEW -> "Check again"
                },
                tone = if (tool.change == PendingTool.Change.NEW) Tone.Brand else Tone.Caution,
            )
        }
        if (tool.change == PendingTool.Change.REVIEW) {
            SupportingText("Locus's record of this tool was changed outside Locus, so it can't vouch for it.")
        }
        if (tool.onlyHintsChanged) {
            SupportingText("Only its hints changed: whether it says it reads, writes or deletes. That decides whether calls ask you first.")
        }
        tool.hiddenInDescription.takeIf { it > 0 }?.let { n ->
            Text(
                "Its description has $n hidden ${if (n == 1) "character" else "characters"} (text a person can't see). Locus removes them before the AI reads it.",
                style = LocusType.supporting,
                color = Locus.colors.onCautionContainer,
            )
        }
        val description = compactDiff(tool.descriptionDiff())
        if (description.isNotEmpty()) DiffBlock("Description", description)
        val schema = compactDiff(tool.schemaDiff())
        if (schema.isNotEmpty()) DiffBlock("Arguments", schema)
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
 * and at most [max] lines in all. A new tool (every line added) is shown whole up to [max].
 */
internal fun compactDiff(lines: List<String>, context: Int = 1, max: Int = 60): List<String> {
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
    return out.take(max) + "  … ${out.size - max} more lines"
}
