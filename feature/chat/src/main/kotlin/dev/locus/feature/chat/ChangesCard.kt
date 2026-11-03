package dev.locus.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.relativeTime
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.FileTypeIcon
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
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

/** A card with a few files opens expanded; with more it starts as its summary row. */
internal fun changesExpandedByDefault(fileCount: Int): Boolean = fileCount <= CHANGES_OPEN_MAX

private const val CHANGES_OPEN_MAX = 3

/** The lines still-standing changes add and remove, for the summary row: (added, removed). */
internal fun changesTotals(files: List<TrackedChange>): Pair<Int, Int> =
    files.filter { !it.undone }.let { standing -> standing.sumOf { it.added } to standing.sumOf { it.removed } }

/** "edited 4 times"; null for a file changed once. */
internal fun editCountLabel(change: TrackedChange): String? = change.editCount.takeIf { it > 1 }?.let { "edited $it times" }

/**
 * The files a turn changed, under the turn: a summary row ("Changed 11 files · +865 −282", Undo all)
 * that folds the list away, each file with its +/− line counts, how often and when it last changed,
 * and a tap for its diff and edit history, with Undo there. Every undo asks first, and says when the
 * file changed since the agent left it.
 */
@Composable
internal fun ChangesCard(item: ChatItem.Changes, actions: ChangeActions, modifier: Modifier = Modifier) {
    var open by rememberSaveable(item.key) { mutableStateOf<Long?>(null) }
    // Remembered per card for the session, scrolled away and back included.
    var expanded by rememberSaveable(item.key + ":open") { mutableStateOf(changesExpandedByDefault(item.files.size)) }
    var confirm by remember(item.key) { mutableStateOf<Confirm?>(null) }
    val scope = rememberCoroutineScope()
    val standing = item.files.filter { !it.undone && it.canUndo }
    val (added, removed) = changesTotals(item.files)
    val title = changesTitle(item.files)
    val colors = Locus.diff

    LocusCard(modifier.fillMaxWidth().animateContentSize(), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).padding(end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = Sizes.touchTarget)
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide files" else "Show files") { expanded = !expanded }
                    .padding(start = Spacing.md, end = Spacing.sm)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$title, $added lines added, $removed removed"
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f, fill = false), style = LocusType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (added > 0 || removed > 0) {
                    Text("+$added", Modifier.padding(start = Spacing.sm), style = LocusType.mono, color = colors.added)
                    Text("−$removed", Modifier.padding(start = Spacing.xs), style = LocusType.mono, color = colors.removed)
                }
                Box(Modifier.weight(1f, fill = true))
                Icon(
                    LocusIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(IconSize.small).rotate(if (expanded) 270f else 90f),
                )
            }
            if (standing.size > 1) {
                TextButton(onClick = {
                    scope.launch { confirm = Confirm(null, "the changes to ${standing.size} files", actions.checkTurn(item.turnId)) }
                }) { Text("Undo all") }
            }
        }
        if (expanded) {
            item.files.forEach { change ->
                GroupDivider(inset = Spacing.md)
                ChangeRow(change, onClick = { open = change.id })
            }
        }
    }

    val shown = item.files.firstOrNull { it.id == open }
    if (shown != null) {
        DiffSheet(
            shown,
            actions = actions,
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
    val edited = editCountLabel(change)
    val time = change.updatedAt.takeIf { it > 0 }?.let { relativeTime(it) }
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Show diff and history", onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics {
                contentDescription = listOfNotNull(change.path, changeSummary(change), edited, time?.let { "last changed $it" }, "undone".takeIf { change.undone })
                    .joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(change.path.substringAfterLast('/'), change.isDirectory, size = IconSize.large)
        Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    change.path.substringAfterLast('/'),
                    Modifier.weight(1f, fill = false),
                    style = LocusType.rowSupporting,
                    color = if (change.undone) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (change.editCount > 1) SupportingText("×${change.editCount}", Modifier.padding(start = Spacing.xs), style = LocusType.mono)
            }
            val folder = change.path.substringBeforeLast('/', "")
            val under = listOfNotNull(folder.ifEmpty { null }, time).joinToString(" · ")
            if (under.isNotEmpty()) {
                SupportingText(under, style = LocusType.mono, maxLines = 1)
            }
        }
        when {
            change.undone -> SupportingText("Undone", style = LocusType.chip)
            change.isDirectory || change.binary || (change.added == 0 && change.removed == 0) ->
                SupportingText(changeSummary(change), style = LocusType.chip)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("+${change.added}", style = LocusType.mono, color = colors.added)
                Text("−${change.removed}", style = LocusType.mono, color = colors.removed)
            }
        }
    }
}

/** A diff's lines without the `---`/`+++` header (the sheet's title already names the file). */
private fun diffBody(diff: String?): List<String> =
    diff?.lines()?.dropLastWhile { it.isEmpty() }.orEmpty().filterNot { it.startsWith("--- ") || it.startsWith("+++ ") }

/** "write_file" → "Wrote", a command → "Changed by a command or you". */
internal fun editLabel(edit: dev.locus.core.data.repository.FileEdit): String = when {
    edit.outside -> "Changed outside the file tools (a command, or you)"
    else -> when (edit.toolName) {
        "write_file" -> "write_file · wrote the file"
        "edit_file" -> "edit_file · edited"
        "multi_edit" -> "multi_edit · edited"
        "delete_path" -> "delete_path · deleted"
        "move_path" -> "move_path · moved"
        "copy_path" -> "copy_path · copied"
        else -> edit.toolName
    }
}

/**
 * The change as a unified diff (the net change for the turn), then each edit to the file in this turn
 * in order with its time, the tool that made it and its own diff (a tap opens it). Undo in the header
 * while it can be undone.
 */
@Composable
private fun DiffSheet(change: TrackedChange, actions: ChangeActions, onDismiss: () -> Unit, onUndo: () -> Unit) {
    val lines = remember(change.diff) { diffBody(change.diff) }
    var openEdits by rememberSaveable(change.id) { mutableStateOf(emptySet<Int>()) }
    // Changed since the agent's last edit (a command, the user, a later turn): no per-edit detail for that.
    val changedSince by produceState(false, change.id, change.updatedAt) {
        value = !change.undone && runCatching { actions.check(change.id).changedSince }.getOrDefault(false)
    }
    val clock = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    LocusSheet(
        title = change.path.substringAfterLast('/'),
        subtitle = listOfNotNull(change.path.substringBeforeLast('/', "").ifEmpty { null }, changeSummary(change), editCountLabel(change)).joinToString(" · "),
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
        if (note != null) SupportingText(note, style = LocusType.rowSupporting)
        // Long lines wrap: on a phone that beats scrolling sideways line by line. As tall as the sheet
        // allows, then it scrolls.
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            item(key = "net") { SupportingText("Net change this turn", style = LocusType.chip) }
            when {
                change.isDirectory -> item { SupportingText("A folder: there are no lines to compare.", style = LocusType.rowSupporting) }
                change.binary -> item { SupportingText("A binary file: there are no lines to compare.", style = LocusType.rowSupporting) }
                lines.isEmpty() -> item {
                    SupportingText(
                        if (change.diff == null) "This diff is too large to keep; the counts above are still right." else "No lines changed.",
                        style = LocusType.rowSupporting,
                    )
                }
                else -> items(lines) { line -> DiffLine(line, Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.subtleFill))) }
            }
            item(key = "history") {
                Text(
                    when {
                        change.edits.isEmpty() -> "Edit history"
                        change.editCount <= 1 -> "Edit history · changed once"
                        else -> "Edit history · edited ${change.editCount} times"
                    },
                    Modifier.padding(top = Spacing.md),
                    style = LocusType.cardTitle,
                )
            }
            if (change.edits.isEmpty()) {
                item {
                    SupportingText(
                        "No per-edit history for this change: it was recorded before Locus kept one, or came back from a backup. The net change above is complete.",
                        style = LocusType.rowSupporting,
                    )
                }
            }
            change.edits.forEachIndexed { i, edit ->
                item(key = "edit:$i") {
                    val open = i in openEdits
                    val canOpen = !edit.outside && edit.diff != null
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)
                                .then(
                                    if (canOpen) Modifier.clickable(role = Role.Button, onClickLabel = if (open) "Hide this edit's diff" else "Show this edit's diff") {
                                        openEdits = if (open) openEdits - i else openEdits + i
                                    } else Modifier,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SupportingText(clock.format(java.util.Date(edit.at)), Modifier.padding(end = Spacing.sm), style = LocusType.mono)
                            Column(Modifier.weight(1f)) {
                                Text(editLabel(edit), style = LocusType.rowSupporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                SupportingText(
                                    when {
                                        edit.outside -> "No per-edit detail: only the file tools record their own diffs"
                                        edit.binary -> "Binary: no lines to compare"
                                        edit.diff == null && (edit.added > 0 || edit.removed > 0) -> "+${edit.added} −${edit.removed} · too large to keep the diff"
                                        else -> "+${edit.added} −${edit.removed}"
                                    },
                                    style = LocusType.mono,
                                    maxLines = 2,
                                )
                            }
                            if (canOpen) {
                                Icon(
                                    LocusIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(IconSize.small).rotate(if (open) 270f else 90f),
                                )
                            }
                        }
                        if (open) {
                            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.subtleFill))) {
                                diffBody(edit.diff).forEach { DiffLine(it) }
                            }
                        }
                    }
                }
            }
            if (changedSince) {
                item(key = "since") {
                    Text("Changed again since (a command, you, or a later turn)", Modifier.padding(top = Spacing.xs), style = LocusType.rowSupporting)
                    SupportingText("No per-edit detail for that change; undoing takes it back too.", style = LocusType.mono, maxLines = 2)
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
            Text(blocked, style = LocusType.rowSupporting)
            return@LocusSheet
        }
        SupportingText(
            "${if (confirm.id == null) "They go" else "It goes"} back to how ${if (confirm.id == null) "they were" else "it was"} before this turn. " +
                "What's there now moves to Workspace → Trash, so you can get it back. The agent is told, and reads the file again before changing it.",
            style = LocusType.rowSupporting,
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
