package dev.locus.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.data.repository.UndoCheck
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.DiffLine
import dev.locus.core.designsystem.FileTypeIcon
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.diff
import kotlinx.coroutines.launch

/** What the changes card can do; supplied by the chat screen's view model. */
class ChangeActions(
    val check: suspend (Long) -> UndoCheck = { UndoCheck(false) },
    val checkTurn: suspend (String) -> UndoCheck = { UndoCheck(false) },
    /** Undo the changes with these ids. */
    val undo: (List<Long>) -> Unit = {},
    /** Undo every change of the turn still standing. */
    val undoTurn: (String) -> Unit = {},
)

/** "Changed 3 files" / "Changed 1 file", counting what still stands. */
internal fun changesTitle(files: List<TrackedChange>): String {
    val standing = files.count { !it.undone }
    if (standing == 0) return if (files.size == 1) "Undid the change to 1 file" else "Undid the changes to ${files.size} files"
    return "Changed $standing ${if (standing == 1) "file" else "files"}"
}

/** "+12 −3", "New file", "Deleted", "Binary"…: what a row says about its change. */
internal fun changeSummary(change: TrackedChange): String = when {
    change.isDirectory -> when (change.kind) {
        TrackedChange.Kind.CREATED -> "New folder"
        TrackedChange.Kind.DELETED -> "Folder deleted"
        TrackedChange.Kind.MODIFIED -> "Folder"
    }
    change.binary -> when (change.kind) {
        TrackedChange.Kind.CREATED -> "New binary file"
        TrackedChange.Kind.DELETED -> "Binary file deleted"
        TrackedChange.Kind.MODIFIED -> "Binary file changed"
    }
    change.kind == TrackedChange.Kind.DELETED && change.added == 0 && change.removed == 0 -> "Deleted"
    else -> "+${change.added} −${change.removed}"
}

/**
 * The files a turn changed, under the turn: each with its +/− line counts, a tap for the diff, and
 * Undo (in the diff sheet) or Undo all here. Every undo asks first, and says when the file changed
 * since the agent left it.
 */
@Composable
internal fun ChangesCard(item: ChatItem.Changes, actions: ChangeActions, modifier: Modifier = Modifier) {
    var open by rememberSaveable(item.key) { mutableStateOf<Long?>(null) }
    var confirm by remember(item.key) { mutableStateOf<Confirm?>(null) }
    val scope = rememberCoroutineScope()
    val standing = item.files.filter { !it.undone && it.canUndo }

    LocusCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(changesTitle(item.files), Modifier.weight(1f).padding(vertical = Spacing.sm), style = MaterialTheme.typography.titleSmall)
            if (standing.size > 1) {
                TextButton(onClick = {
                    scope.launch { confirm = Confirm(null, "the changes to ${standing.size} files", actions.checkTurn(item.turnId)) }
                }) { Text("Undo all") }
            }
        }
        item.files.forEach { change ->
            GroupDivider(inset = Spacing.md)
            ChangeRow(change, onClick = { open = change.id })
        }
    }

    val shown = item.files.firstOrNull { it.id == open }
    if (shown != null) {
        DiffSheet(
            shown,
            onDismiss = { open = null },
            onUndo = {
                scope.launch { confirm = Confirm(shown.id, shown.path, actions.check(shown.id)) }
            },
        )
    }
    confirm?.let { c ->
        UndoSheet(
            c,
            onDismiss = { confirm = null },
            onConfirm = {
                if (c.id == null) actions.undoTurn(item.turnId) else actions.undo(listOf(c.id))
                confirm = null
                open = null
            },
        )
    }
}

/** An undo waiting for the user: one change ([id]) or the whole turn (null), with what [check] found. */
private class Confirm(val id: Long?, val what: String, val check: UndoCheck)

@Composable
private fun ChangeRow(change: TrackedChange, onClick: () -> Unit) {
    val colors = Locus.diff
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics { contentDescription = "${change.path}, ${changeSummary(change)}${if (change.undone) ", undone" else ""}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(change.path.substringAfterLast('/'), change.isDirectory, size = 24.dp)
        Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
            Text(
                change.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                color = if (change.undone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val folder = change.path.substringBeforeLast('/', "")
            if (folder.isNotEmpty()) {
                Text(folder, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
        }
        when {
            change.undone -> Text("Undone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            change.isDirectory || change.binary || (change.added == 0 && change.removed == 0) ->
                Text(changeSummary(change), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("+${change.added}", style = MonoCaption, color = colors.added)
                Text("−${change.removed}", style = MonoCaption, color = colors.removed)
            }
        }
    }
}

/** The change as a unified diff, with Undo in the header while it can be undone. */
@Composable
private fun DiffSheet(change: TrackedChange, onDismiss: () -> Unit, onUndo: () -> Unit) {
    val lines = remember(change.diff) { change.diff?.lines()?.dropLastWhile { it.isEmpty() }.orEmpty() }
    LocusSheet(
        title = change.path.substringAfterLast('/'),
        subtitle = change.path.substringBeforeLast('/', "").ifEmpty { null }?.let { "$it · ${changeSummary(change)}" } ?: changeSummary(change),
        action = if (!change.undone && change.canUndo) "Undo" else null,
        onAction = onUndo,
        onDismiss = onDismiss,
        scrollable = false,
    ) {
        val note = when {
            change.undone -> "You undid this change."
            !change.canUndo -> "The earlier version is no longer in the workspace trash, so this can't be undone."
            else -> null
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            change.isDirectory -> Text("A folder: there are no lines to compare.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            change.binary -> Text("A binary file: there are no lines to compare.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lines.isEmpty() -> Text(
                if (change.diff == null) "This diff is too large to keep; the counts above are still right." else "No lines changed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                // Long lines wrap: on a phone that beats scrolling sideways line by line.
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(Radius.chip))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
                    contentPadding = PaddingValues(vertical = Spacing.xs),
                ) {
                    // The `---`/`+++` header repeats the title; leave it out.
                    items(lines.filterNot { it.startsWith("--- ") || it.startsWith("+++ ") }) { line -> DiffLine(line) }
                }
            }
        }
    }
}

/** Confirms an undo: what goes back, where the current version goes, and a warning when it changed since. */
@Composable
private fun UndoSheet(confirm: Confirm, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val blocked = confirm.check.blocked
    LocusSheet(
        title = "Undo ${confirm.what}?",
        action = if (blocked == null) "Undo" else null,
        actionTone = Tone.Critical,
        onAction = onConfirm,
        onDismiss = onDismiss,
    ) {
        if (blocked != null) {
            Text(blocked, style = MaterialTheme.typography.bodyMedium)
            return@LocusSheet
        }
        Text(
            "${if (confirm.id == null) "They go" else "It goes"} back to how ${if (confirm.id == null) "they were" else "it was"} before this turn. " +
                "What's there now moves to Workspace → Trash, so you can get it back. The agent is told, and reads the file again before changing it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (confirm.check.changedSince) {
            Banner(
                text = "Changed since the agent edited it (by you, a command or a later turn). Undoing also takes back those later changes; they go to the trash too.",
                tone = Tone.Caution,
                title = "This file has changed since",
                actionsBelow = true,
            )
        }
    }
}
