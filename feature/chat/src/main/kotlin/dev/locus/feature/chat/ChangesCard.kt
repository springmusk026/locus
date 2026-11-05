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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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

/** A line count grouped as the phone's language groups digits: "12,601" in English, "12.601" in German. */
internal fun formatCount(n: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    java.text.NumberFormat.getIntegerInstance(locale).format(n)

/** "+12 −3", "+12", "−3": only the parts that aren't zero; null when nothing was added or removed. */
internal fun lineCounts(added: Int, removed: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String? =
    listOfNotNull("+${formatCount(added, locale)}".takeIf { added > 0 }, "−${formatCount(removed, locale)}".takeIf { removed > 0 })
        .joinToString(" ").ifEmpty { null }

private fun linesLabel(n: Int): String = if (n == 1) "1 line" else "${formatCount(n)} lines"

/**
 * What a row says about its change. A file the turn created reads "New · 121 lines" and one it deleted
 * "Deleted · 40 lines", never "+121 −0": the net diff of a new file only adds. A changed file shows the
 * counts that aren't zero ("+12 −3", "+12"). Folders and binary files have no lines to count.
 */
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
    change.kind == TrackedChange.Kind.CREATED -> if (change.added > 0) "New · ${linesLabel(change.added)}" else "New · empty"
    change.kind == TrackedChange.Kind.DELETED -> if (change.removed > 0) "Deleted · ${linesLabel(change.removed)}" else "Deleted"
    else -> lineCounts(change.added, change.removed) ?: "No line changes"
}

/** Whether [changeSummary] is line counts (drawn in the diff colours) rather than words (drawn as a label). */
internal fun summaryIsCounts(change: TrackedChange): Boolean =
    !change.isDirectory && !change.binary && change.kind == TrackedChange.Kind.MODIFIED && lineCounts(change.added, change.removed) != null

/**
 * The summary's phrases after the line counts: how many files are new or deleted (["1 new file",
 * "1 deleted"]), counting what still stands; empty when none are. Each is drawn as one unbreakable unit.
 */
internal fun changesTotalsParts(files: List<TrackedChange>): List<String> {
    val standing = files.filter { !it.undone }
    val created = standing.count { it.kind == TrackedChange.Kind.CREATED }
    val deleted = standing.count { it.kind == TrackedChange.Kind.DELETED }
    return listOfNotNull(
        created.takeIf { it > 0 }?.let { if (it == 1) "1 new file" else "${formatCount(it)} new files" },
        deleted.takeIf { it > 0 }?.let { "${formatCount(it)} deleted" },
    )
}

/**
 * Each of a file's edits in this turn, oldest first, for the expanded card: "Edit 1 · +124",
 * "Edit 2 · +11 −14". Tool edits are numbered; a change found between them (a command, the user) is
 * "Changed outside the file tools", since it has no counts of its own.
 */
internal fun editLines(change: TrackedChange): List<String> {
    var n = 0
    return change.edits.map { edit ->
        if (edit.outside) {
            "Changed outside the file tools"
        } else {
            n++
            "Edit $n · " + if (edit.binary) "binary" else (lineCounts(edit.added, edit.removed) ?: "no line changes")
        }
    }
}

/** A card with a few files opens expanded; with more it starts as its summary row. */
internal fun changesExpandedByDefault(fileCount: Int): Boolean = fileCount <= CHANGES_OPEN_MAX

private const val CHANGES_OPEN_MAX = 3

/** The lines still-standing changes add and remove, for the summary row: (added, removed). */
internal fun changesTotals(files: List<TrackedChange>): Pair<Int, Int> =
    files.filter { !it.undone }.let { standing -> standing.sumOf { it.added } to standing.sumOf { it.removed } }

/** "Edited 4 times"; null for a file changed once. */
internal fun editCountLabel(change: TrackedChange): String? = change.editCount.takeIf { it > 1 }?.let { "Edited $it times" }

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
    val totalsParts = changesTotalsParts(item.files)

    // A still render (preview, screenshot) shows the settled card, not the first frame of its growth.
    val still = androidx.compose.ui.platform.LocalInspectionMode.current
    LocusCard(modifier.fillMaxWidth().then(if (still) Modifier else Modifier.animateContentSize()), contentPadding = PaddingValues(0.dp)) {
        val hasTotals = added > 0 || removed > 0 || totalsParts.isNotEmpty()
        // The header: the title row (title, chevron, Undo all), then the totals on a line of their own, the
        // card's full width, so neither squeezes the other at a large font. The whole header folds the list.
        Column(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide files" else "Show files") { expanded = !expanded }
                .semantics(mergeDescendants = true) {
                    contentDescription = (
                        listOfNotNull(title, "${formatCount(added)} lines added".takeIf { added > 0 }, "${formatCount(removed)} removed".takeIf { removed > 0 }) +
                            totalsParts
                        ).joinToString(", ")
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(bottom = if (hasTotals) Spacing.sm else 0.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).padding(start = Spacing.md, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f).padding(vertical = Spacing.sm), style = LocusType.cardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(
                    LocusIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = Spacing.sm).size(IconSize.small).rotate(if (expanded) 270f else 90f),
                )
                if (standing.size > 1) {
                    TextButton(onClick = {
                        scope.launch { confirm = Confirm(null, "the changes to ${standing.size} files", actions.checkTurn(item.turnId)) }
                    }) { Text("Undo all") }
                }
            }
            // Only the counts that aren't zero (a new file adds lines and removes none), then how many files
            // are new or deleted. Each part keeps together; a line breaks only between parts.
            if (hasTotals) {
                Text(countsText(added, removed, totalsParts), Modifier.fillMaxWidth().padding(horizontal = Spacing.md), style = LocusType.mono)
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
            // Blocked: the diff is still there to read, and to put back by hand.
            onViewDiff = {
                confirm = null
                open = c.id ?: item.files.firstOrNull { !it.undone }?.id
            },
            onConfirm = {
                if (c.id == null) actions.undoTurn(item.turnId) else actions.undo(listOf(c.id))
                confirm = null
                open = null
            },
        )
    }
}

/** What to do when undo can't run: read the diff and put the lines back, by hand or by asking Locus. */
internal fun undoBlockedNextStep(single: Boolean): String =
    "The diff still shows every line that changed. Open it to put ${if (single) "the file" else "the files"} back by hand, or ask Locus in this chat to change ${if (single) "it" else "them"} back."

/** An undo waiting for the user: one change ([id]) or the whole turn (null), with what [check] found. */
private class Confirm(val id: Long?, val what: String, val check: UndoCheck)

@Composable
private fun ChangeRow(change: TrackedChange, onClick: () -> Unit) {
    val edited = editCountLabel(change)
    val time = change.updatedAt.takeIf { it > 0 }?.let { relativeTime(it) }
    val summary = changeSummary(change)
    // Each edit when there was more than one, so repeated edits show without opening the file.
    val edits = if (change.edits.size > 1 && !change.undone) editLines(change) else emptyList()
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Show diff and history", onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics {
                contentDescription = (listOfNotNull(change.path, summary, edited, time?.let { "last changed $it" }, "undone".takeIf { change.undone }) + edits)
                    .joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(change.path.substringAfterLast('/'), change.isDirectory, size = IconSize.large)
        Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
            Text(
                change.path.substringAfterLast('/'),
                // Undone reads from the strike-through and the "Undone" label, not from faint text.
                style = if (change.undone) LocusType.rowSupporting.copy(textDecoration = TextDecoration.LineThrough) else LocusType.rowSupporting,
                color = if (change.undone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // What changed sits under the name, not beside it, so a large font never squeezes the name or
            // breaks a count: "+12 −3 · Edited 2 times", "New · 121 lines".
            if (!change.undone) {
                if (summaryIsCounts(change)) {
                    Text(countsText(change.added, change.removed, listOfNotNull(edited)), style = LocusType.mono)
                } else {
                    SupportingText(keepPhrases(listOfNotNull(summary, edited).joinToString(" · ")), style = LocusType.chip)
                }
            }
            val folder = change.path.substringBeforeLast('/', "")
            val under = listOfNotNull(folder.ifEmpty { null }, time).joinToString(" · ")
            if (under.isNotEmpty()) {
                SupportingText(under, style = LocusType.mono, maxLines = 2)
            }
            edits.forEach { line -> SupportingText(keepPhrases(line), style = LocusType.mono) }
        }
        if (change.undone) SupportingText("Undone", Modifier.padding(start = Spacing.sm), style = LocusType.chip)
    }
}

/**
 * "+12,601 −11,015 · 1 new file · 1 deleted": the counts that aren't zero in the diff colours, then
 * [notes] in the supporting colour. One text in which each part is one unit (no-break spaces inside a
 * phrase, and "·" kept with the part after it), so a line only ever breaks between parts.
 */
@Composable
private fun countsText(added: Int, removed: Int, notes: List<String>): AnnotatedString {
    val colors = Locus.diff
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        if (added > 0) withStyle(SpanStyle(color = colors.added)) { append("+${formatCount(added)}") }
        if (removed > 0) {
            if (length > 0) append(" ")
            withStyle(SpanStyle(color = colors.removed)) { append("−${formatCount(removed)}") }
        }
        notes.forEach { note ->
            withStyle(SpanStyle(color = muted)) { append((if (length > 0) " · " else "") + unbreakable(note)) }
        }
    }
}

/** [text] with its spaces made no-break, so it wraps as one unit. */
internal fun unbreakable(text: String): String = text.replace(' ', ' ')

/** "New · 121 lines · Edited 2 times" that wraps only at its " · " separators, each phrase kept whole. */
internal fun keepPhrases(text: String): String = text.split(" · ").joinToString(" · ") { unbreakable(it) }

/** A diff's lines without the `---`/`+++` header (the sheet's title already names the file). */
private fun diffBody(diff: String?): List<String> =
    diff?.lines()?.dropLastWhile { it.isEmpty() }.orEmpty().filterNot { it.startsWith("--- ") || it.startsWith("+++ ") }

/** "write_file" → "Wrote the file", a command → "Changed by a command or you". */
internal fun editLabel(edit: dev.locus.core.data.repository.FileEdit): String = when {
    edit.outside -> "Changed outside the file tools (a command, or you)"
    else -> when (edit.toolName) {
        "write_file" -> "Wrote the file"
        "edit_file", "multi_edit" -> "Edited"
        "delete_path" -> "Deleted"
        "move_path" -> "Moved"
        "copy_path" -> "Copied"
        "make_directory" -> "Created"
        else -> "Changed"
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
                                // Mono for the line counts only; a sentence reads in the supporting role.
                                val counts = !edit.outside && !edit.binary
                                SupportingText(
                                    when {
                                        edit.outside -> "No per-edit detail: only the file tools record their own diffs"
                                        edit.binary -> "Binary: no lines to compare"
                                        edit.diff == null && (edit.added > 0 || edit.removed > 0) -> "${lineCounts(edit.added, edit.removed)} · too large to keep the diff"
                                        else -> lineCounts(edit.added, edit.removed) ?: "No line changes"
                                    },
                                    style = if (counts) LocusType.mono else LocusType.supporting,
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
                    // Not an edit with a time: the file's state now, so it reads as a note under the history.
                    Banner(
                        text = "A command, you or a later turn changed it after the edits above. There's no per-edit detail for that; undoing takes it back too.",
                        tone = Tone.Caution,
                        title = "Changed again since",
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }
        }
    }
}

/** Confirms an undo: what goes back, where the current version goes, and a warning when it changed since. */
@Composable
private fun UndoSheet(confirm: Confirm, onDismiss: () -> Unit, onConfirm: () -> Unit, onViewDiff: () -> Unit) {
    val blocked = confirm.check.blocked
    // One file: its name in the title, where it lives under it, so a large font cuts neither.
    val single = confirm.id != null
    val folder = if (single) confirm.what.substringBeforeLast('/', "").ifEmpty { null } else null
    LocusSheet(
        title = "Undo ${if (single) confirm.what.substringAfterLast('/') else confirm.what}?",
        subtitle = folder,
        action = if (blocked == null) "Undo" else "View the diff",
        actionTone = if (blocked == null) Tone.Critical else Tone.Brand,
        onAction = if (blocked == null) onConfirm else onViewDiff,
        onDismiss = onDismiss,
    ) {
        if (blocked != null) {
            // Why, then what still works: the diff shows every line that changed.
            Text(blocked, style = LocusType.rowSupporting)
            SupportingText(undoBlockedNextStep(single), style = LocusType.rowSupporting)
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
