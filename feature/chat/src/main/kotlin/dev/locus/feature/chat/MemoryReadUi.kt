package dev.locus.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
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
    Row(
        modifier.fillMaxWidth().heightIn(min = dev.locus.core.designsystem.Sizes.touchTarget)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Show memory notes") { open = true }
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(IconSize.large), contentAlignment = Alignment.Center) {
            Icon(LocusIcons.Note, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.primary)
        }
        Text(memoryReadSummary(reads.size), Modifier.weight(1f), style = LocusType.rowSupporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(LocusIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.outline)
    }
    if (open) {
        LocusSheet(title = "Memory this turn read", subtitle = "Forget a note to delete it from Locus's memory", onDismiss = { open = false }) {
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
                    memoryReadLabel(ref),
                    Modifier.weight(1f),
                    style = LocusType.rowSupporting,
                    color = if (gone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    // Two lines, so Forget beside it doesn't cut the note's name at a large font.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (gone) {
                    SupportingText(
                        "Forgotten",
                        Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        style = LocusType.chip,
                    )
                } else {
                    TextButton(onClick = { confirm = ref }) { Text("Forget") }
                }
            }
        }
    }
    confirm?.let { ref ->
        ConfirmSheet(
            title = "Forget “${ref.topic.replace('-', ' ')}”?",
            message = if (ref.general) {
                "Locus deletes this note from your general memory, so no chat will know it from now on. This chat has already read it."
            } else {
                "Locus deletes this note from its memory and won't know it in new chats. This chat has already read it."
            },
            confirmLabel = "Forget",
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
