package dev.locus.feature.chat

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import dev.locus.core.agent.Compaction
import dev.locus.core.agent.Rewind
import dev.locus.core.data.agent.RewindPreview
import dev.locus.core.data.agent.SummaryDraft
import dev.locus.core.data.repository.RewindFile
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.FileTypeIcon
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.TextArea
import dev.locus.core.designsystem.TextAreaSize
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.startsTurn

// Rewind here and Summarise from here (docs/design/rewind.md): the message menus' entries, their sheets, and
// the rows they leave in the transcript.

/** Why Summarise from here isn't offered on a message. */
enum class SummariseUnavailable {
    /** An earlier summary already stands in for it. */
    SUMMARISED,

    /** A reply with nothing after it. */
    NOTHING_AFTER,

    /** Too little from here on. */
    TOO_LITTLE,

    /** Anthropic compacted this part on its server; summarising it again would bring back what that folded away. */
    SERVER_COMPACTED,
}

/**
 * What a message's menu offers for its history: Rewind here (null [rewind] when it's offered) and Summarise from
 * here (null [summarise]), with how many of the model's messages a summary from here would stand in for.
 */
data class MessageActions(
    val rewind: Rewind.Unavailable? = null,
    val summarise: SummariseUnavailable? = null,
    val summariseCount: Int = 0,
)

/**
 * [MessageActions] for every user message and reply on the shown branch [path], given the model's [context] of it
 * (core/agent `Compaction.modelContext`). One pass each, so a long chat stays cheap; it agrees with
 * `Rewind.plan` and `Compaction.from`, which the runner uses (see `RewindActionsTest`).
 */
internal fun messageActions(path: List<Message>, context: List<Message>): Map<String, MessageActions> {
    if (path.isEmpty()) return emptyMap()
    val lastTurnStart = path.indexOfLast { it.startsTurn() }
    val inContext = context.withIndex().associate { (i, m) -> m.id to i }
    // For each context index: where the next message that starts a turn is, and whether Anthropic compacted on
    // its server from there to the end.
    val nextStart = IntArray(context.size + 1) { -1 }
    for (i in context.indices.reversed()) nextStart[i] = if (i + 1 < context.size && context[i + 1].startsTurn()) i + 1 else nextStart[i + 1]
    val serverFrom = BooleanArray(context.size + 1)
    for (i in context.indices.reversed()) serverFrom[i] = serverFrom[i + 1] || Compaction.serverCompactedIn(listOf(context[i]))
    return path.withIndex().filter { (_, m) -> m.role == Role.USER && m.parts.none { it is Part.Checkpoint } || m.role == Role.ASSISTANT }
        .associate { (i, m) ->
            val rewind = when {
                m.role == Role.USER && i == 0 -> Rewind.Unavailable.FIRST_MESSAGE
                m.role == Role.ASSISTANT && i >= lastTurnStart -> Rewind.Unavailable.NOTHING_AFTER
                else -> null
            }
            val at = inContext[m.id]
            val start = when {
                at == null -> null
                m.role == Role.USER -> at
                else -> nextStart[at].takeIf { it >= 0 }
            }
            val summarise = when {
                at == null -> SummariseUnavailable.SUMMARISED
                start == null -> SummariseUnavailable.NOTHING_AFTER
                context.size - start < Compaction.MIN_FROM_MESSAGES -> SummariseUnavailable.TOO_LITTLE
                serverFrom[start] -> SummariseUnavailable.SERVER_COMPACTED
                else -> null
            }
            m.id to MessageActions(rewind, summarise, if (start != null) context.size - start else 0)
        }
}

/** Why an entry is off, in the user's words; null when it's on. [running]: the agent is working on the chat. */
internal fun rewindUnavailableText(resources: Resources, running: Boolean, reason: Rewind.Unavailable?): String? = when {
    running -> resources.getString(R.string.chat_history_unavailable_running)
    reason == Rewind.Unavailable.FIRST_MESSAGE -> resources.getString(R.string.chat_rewind_unavailable_first)
    reason == Rewind.Unavailable.NOTHING_AFTER -> resources.getString(R.string.chat_rewind_unavailable_nothing_after)
    reason == Rewind.Unavailable.NOT_ON_BRANCH -> resources.getString(R.string.chat_rewind_unavailable_branch)
    else -> null
}

internal fun summariseUnavailableText(resources: Resources, running: Boolean, reason: SummariseUnavailable?): String? = when {
    running -> resources.getString(R.string.chat_history_unavailable_running)
    reason == SummariseUnavailable.SUMMARISED -> resources.getString(R.string.chat_summarise_unavailable_summarised)
    reason == SummariseUnavailable.NOTHING_AFTER -> resources.getString(R.string.chat_summarise_unavailable_nothing_after)
    reason == SummariseUnavailable.TOO_LITTLE -> resources.getString(R.string.chat_summarise_unavailable_too_little)
    reason == SummariseUnavailable.SERVER_COMPACTED -> resources.getString(R.string.chat_summarise_unavailable_server)
    else -> null
}

/**
 * The two history entries of a message's menu: Rewind here and Summarise from here. An entry that isn't offered
 * stays in the menu, greyed, with the reason under its name.
 */
@Composable
internal fun HistoryMenuItems(actions: MessageActions, running: Boolean, onRewind: () -> Unit, onSummarise: () -> Unit) {
    val resources = LocalResources.current
    MenuEntry(stringResource(R.string.chat_history_rewind), LocusIcons.Restore, rewindUnavailableText(resources, running, actions.rewind), onRewind)
    MenuEntry(stringResource(R.string.chat_history_summarise), LocusIcons.Note, summariseUnavailableText(resources, running, actions.summarise), onSummarise)
}

@Composable
private fun MenuEntry(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, unavailable: String?, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label)
                if (unavailable != null) SupportingText(unavailable, style = LocusType.rowSupporting)
            }
        },
        leadingIcon = { Icon(icon, null) },
        enabled = unavailable == null,
        onClick = onClick,
    )
}

// ---- Rewind -----------------------------------------------------------------------------------------

/** "Changed after this point: goes back" and the like: what happens to a file a rewind lists. */
internal fun rewindFileLine(resources: Resources, file: RewindFile): String = resources.getString(
    when (file.state) {
        RewindFile.State.CHANGED_SINCE -> R.string.chat_rewind_file_changed_since
        RewindFile.State.GONE -> R.string.chat_rewind_file_gone
        RewindFile.State.RESTORE -> when (file.kind) {
            TrackedChange.Kind.CREATED -> R.string.chat_rewind_file_created
            TrackedChange.Kind.DELETED -> R.string.chat_rewind_file_deleted
            TrackedChange.Kind.MODIFIED -> R.string.chat_rewind_file_modified
        }
    },
)

/** One line per kind of action that ran after the point and stays: "3 shell commands ran after this; their effects stay." */
internal fun rewindEffectLines(
    resources: Resources,
    effects: List<Rewind.Effect>,
    /** Some files go back because commands' changes were snapshotted (docs/design/shell-snapshots.md): say only the rest stays. */
    commandFilesBack: Boolean = false,
): List<Pair<String, List<String>>> =
    Rewind.EffectKind.entries.mapNotNull { kind ->
        val of = effects.filter { it.kind == kind }
        if (of.isEmpty()) return@mapNotNull null
        val res = when (kind) {
            Rewind.EffectKind.COMMAND -> if (commandFilesBack) R.plurals.chat_rewind_effects_command_tracked else R.plurals.chat_rewind_effects_command
            Rewind.EffectKind.OUTSIDE -> R.plurals.chat_rewind_effects_outside
            Rewind.EffectKind.HELPER -> R.plurals.chat_rewind_effects_helper
            Rewind.EffectKind.OTHER -> R.plurals.chat_rewind_effects_other
        }
        resources.getQuantityString(res, of.size, of.size) to of.map { if (it.tool == Rewind.RAN_ANYWAY || it.label != it.tool) it.label else it.tool }.distinct()
    }

/** Effects listed by name under their line before "and 3 more". */
private const val EFFECTS_SHOWN = 4

/**
 * Confirms Rewind here: what the chat does (continues from here; the rest stays as a branch), the files that go
 * back, the ones left as they are and why, and what ran after the point that stays done.
 */
@Composable
internal fun RewindSheet(preview: RewindPreview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val restore = preview.files.filter { it.state == RewindFile.State.RESTORE }
    val changedSince = preview.files.filter { it.state == RewindFile.State.CHANGED_SINCE }
    val gone = preview.files.filter { it.state == RewindFile.State.GONE }
    LocusSheet(
        title = stringResource(R.string.chat_rewind_title),
        action = stringResource(R.string.chat_rewind_action),
        actionTone = if (restore.isEmpty()) Tone.Brand else Tone.Critical,
        onAction = onConfirm,
        onDismiss = onDismiss,
    ) {
        SupportingText(
            stringResource(if (preview.userMessage) R.string.chat_rewind_body_user else R.string.chat_rewind_body_reply),
            style = LocusType.rowSupporting,
        )
        if (preview.files.isEmpty()) {
            SupportingText(stringResource(R.string.chat_rewind_no_files), style = LocusType.rowSupporting)
        }
        if (restore.isNotEmpty()) {
            SectionLabel(pluralStringResource(R.plurals.chat_rewind_files_title, restore.size, restore.size))
            FileList(restore, resources)
            SupportingText(stringResource(R.string.chat_rewind_trash_note), style = LocusType.rowSupporting)
        }
        if (changedSince.isNotEmpty()) {
            Banner(
                text = pluralStringResource(R.plurals.chat_rewind_changed_since_body, changedSince.size, changedSince.size),
                tone = Tone.Caution,
                title = stringResource(R.string.chat_rewind_changed_since_title),
                actionsBelow = true,
            )
            FileList(changedSince, resources)
        }
        if (gone.isNotEmpty()) {
            SectionLabel(stringResource(R.string.chat_rewind_gone_title))
            FileList(gone, resources)
        }
        val effects = rewindEffectLines(resources, preview.effects, commandFilesBack = preview.files.any { it.byCommand && it.state == RewindFile.State.RESTORE })
        if (effects.isNotEmpty()) {
            SectionLabel(stringResource(R.string.chat_rewind_effects_title))
            effects.forEach { (line, names) ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(line, style = LocusType.rowSupporting, color = MaterialTheme.colorScheme.onSurface)
                    names.take(EFFECTS_SHOWN).forEach { SupportingText(it, style = LocusType.mono, maxLines = 2) }
                    if (names.size > EFFECTS_SHOWN) SupportingText(pluralStringResource(R.plurals.chat_rewind_more, names.size - EFFECTS_SHOWN, names.size - EFFECTS_SHOWN), style = LocusType.mono)
                }
            }
        }
        SupportingText(stringResource(R.string.chat_rewind_agent_note), style = LocusType.rowSupporting)
    }
}

@Composable
private fun ColumnScope.FileList(files: List<RewindFile>, resources: Resources) {
    LocusCard {
        files.forEach { file ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                FileTypeIcon(file.path.substringAfterLast('/'), file.isDirectory, size = IconSize.large)
                Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                    Text(file.path.substringAfterLast('/'), style = LocusType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val folder = file.path.substringBeforeLast('/', "")
                    SupportingText(
                        listOfNotNull(rewindFileLine(resources, file), folder.ifEmpty { null }).joinToString(" · "),
                        style = LocusType.rowSupporting,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/**
 * Under the transcript after a rewind: the later messages are on another branch, one tap away. Shown while the
 * chat's last message has a continuation that isn't shown.
 */
@Composable
internal fun LaterBranchRow(onShow: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(LocusIcons.Branch, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.small))
        SupportingText(stringResource(R.string.chat_later_branch), Modifier.weight(1f).padding(start = Spacing.sm), style = LocusType.rowSupporting)
        TextButton(onClick = onShow) { Text(stringResource(R.string.chat_later_branch_show)) }
    }
}

// ---- Summarise from here ---------------------------------------------------------------------------

/**
 * Asks before Summarise from here: what stays (everything before, still read from the cache), what becomes the
 * summary ([count] messages), that it can be read and edited first, and the user's optional instructions.
 */
@Composable
internal fun SummariseSheet(userMessage: Boolean, count: Int, onConfirm: (instructions: String) -> Unit, onDismiss: () -> Unit) {
    var instructions by rememberSaveable { mutableStateOf("") }
    LocusSheet(
        title = stringResource(R.string.chat_summarise_title),
        action = stringResource(R.string.chat_summarise_action),
        onAction = { onConfirm(instructions.trim()) },
        onDismiss = onDismiss,
    ) {
        SupportingText(
            pluralStringResource(if (userMessage) R.plurals.chat_summarise_body_user else R.plurals.chat_summarise_body_reply, count, count),
            style = LocusType.rowSupporting,
        )
        SupportingText(stringResource(R.string.chat_summarise_review_note), style = LocusType.rowSupporting)
        SectionLabel(stringResource(R.string.chat_summarise_instructions_label))
        LocusCard {
            TextArea(
                value = instructions,
                onValueChange = { instructions = it },
                placeholder = stringResource(R.string.chat_summarise_instructions_hint),
                size = TextAreaSize.Regular,
            )
        }
    }
}

/**
 * The summary editor: a new summary to review before it's used ([review]), or an existing one to correct. Saving
 * stores a new checkpoint; nothing stored is changed. [count] is how many messages it stands in for.
 */
@Composable
internal fun SummaryEditorSheet(
    initial: String,
    count: Int,
    review: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Review only: throw the draft away. */
    onDiscard: (() -> Unit)? = null,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    LocusSheet(
        title = stringResource(if (review) R.string.chat_summary_review_title else R.string.chat_summary_edit_title),
        action = stringResource(if (review) R.string.chat_summary_use else R.string.chat_summary_save),
        actionEnabled = text.isNotBlank() && (review || text.trim() != initial.trim()),
        onAction = { onSave(text.trim()) },
        onDismiss = onDismiss,
    ) {
        SupportingText(
            if (review) pluralStringResource(R.plurals.chat_summary_review_body, count, count) else stringResource(R.string.chat_summary_edit_body),
            style = LocusType.rowSupporting,
        )
        LocusCard {
            TextArea(value = text, onValueChange = { text = it }, size = TextAreaSize.Tall)
        }
        if (text.isBlank()) Text(stringResource(R.string.chat_summary_empty), style = LocusType.rowSupporting, color = MaterialTheme.colorScheme.error)
        if (onDiscard != null) {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.chat_summary_discard), color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** A summary written and waiting for review, at the end of the transcript: it isn't used until the user says so. */
@Composable
internal fun PendingSummaryCard(draft: SummaryDraft, onReview: () -> Unit, onDiscard: () -> Unit) {
    LocusCard(onClick = onReview) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LocusIcons.Note, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.medium))
            Text(stringResource(R.string.chat_summary_pending_title), Modifier.weight(1f).padding(start = Spacing.sm), style = LocusType.cardTitle)
        }
        SupportingText(pluralStringResource(R.plurals.chat_summary_pending_body, draft.replaced, draft.replaced))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.chat_summary_discard)) }
            TextButton(onClick = onReview) { Text(stringResource(R.string.chat_summary_review)) }
        }
    }
}
