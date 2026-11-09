package dev.locus.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.LocusSheet
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.locus.core.agent.home.MemoryReads
import dev.locus.core.agent.home.MemoryRef
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone

/**
 * What the steps card can do with the memory topics a turn read: which are no longer saved, Forget
 * one, and check which are still there (the user may have forgotten one in Library or another chat).
 */
internal class MemoryReadActions(
    val forgotten: Set<MemoryRef> = emptySet(),
    val forget: (MemoryRef) -> Unit = {},
    val check: (List<MemoryRef>) -> Unit = {},
)

internal val LocalMemoryReads = compositionLocalOf { MemoryReadActions() }

/** The memory topics [calls] read, from their arguments and results as stored (nothing is added to the transcript). */
internal fun memoryReads(calls: List<ChatItem.ToolCall>): List<MemoryRef> =
    MemoryReads.inCalls(calls.map { MemoryReads.Call(it.name, it.argumentsJson, it.result?.content, it.result?.isError == true) })

/** "Read 1 memory note", "Read 3 memory notes". */
internal fun memoryReadSummary(count: Int): String = "Read $count memory ${if (count == 1) "note" else "notes"}"

/**
 * A turn's memory reads as one quiet step: "Read 2 memory notes". A tap lists each topic with Forget
 * (confirmed in a sheet), so what the agent relied on stays one tap away without a row per topic in
 * the transcript.
 */
@Composable
internal fun MemoryReadRow(reads: List<MemoryRef>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val showLabel = stringResource(R.string.chat_memory_show)
    Row(
        modifier.fillMaxWidth().heightIn(min = dev.locus.core.designsystem.Sizes.touchTarget)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = showLabel) { open = true }
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(IconSize.large), contentAlignment = Alignment.Center) {
            Icon(LocusIcons.Note, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.primary)
        }
        Text(pluralStringResource(R.plurals.chat_memory_read, reads.size, reads.size), Modifier.weight(1f), style = LocusType.rowSupporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(LocusIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.outline)
    }
    if (open) {
        LocusSheet(title = stringResource(R.string.chat_memory_sheet_title), subtitle = stringResource(R.string.chat_memory_sheet_subtitle), onDismiss = { open = false }) {
            MemoryReadList(reads)
        }
    }
}

/** Each topic a turn read, with Forget (confirmed in a sheet); in [MemoryReadRow]'s sheet. */
@Composable
internal fun MemoryReadList(reads: List<MemoryRef>) {
    val actions = LocalMemoryReads.current
    LaunchedEffect(reads) { actions.check(reads) }
    var confirm by remember { mutableStateOf<MemoryRef?>(null) }
    Column(Modifier.fillMaxWidth()) {
        reads.forEach { ref ->
            val gone = ref in actions.forgotten
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(LocusIcons.Note, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (ref.general) stringResource(R.string.chat_memory_label_general, ref.topic.replace('-', ' ')) else ref.topic.replace('-', ' '),
                    Modifier.weight(1f),
                    style = LocusType.rowSupporting,
                    color = if (gone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    // Two lines, so Forget beside it doesn't cut the note's name at a large font.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (gone) {
                    SupportingText(
                        stringResource(R.string.chat_memory_forgotten),
                        Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        style = LocusType.chip,
                    )
                } else {
                    TextButton(onClick = { confirm = ref }) { Text(stringResource(R.string.chat_memory_forget)) }
                }
            }
        }
    }
    confirm?.let { ref ->
        ConfirmSheet(
            title = stringResource(R.string.chat_memory_confirm_title, ref.topic.replace('-', ' ')),
            message = stringResource(if (ref.general) R.string.chat_memory_confirm_general else R.string.chat_memory_confirm_project),
            confirmLabel = stringResource(R.string.chat_memory_forget),
            tone = Tone.Critical,
            onConfirm = {
                actions.forget(ref)
                confirm = null
            },
            onDismiss = { confirm = null },
        )
    }
}

/** A topic as the card lists it: its name in words, and where it came from in a project chat. */
internal fun memoryReadLabel(ref: MemoryRef): String = ref.topic.replace('-', ' ') + if (ref.general) " · general memory" else ""
