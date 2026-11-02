package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import dev.locus.core.agent.home.MemoryReads
import dev.locus.core.agent.home.MemoryRef
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.Spacing
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

/**
 * The bottom of a steps card when the turn read memory: each topic, with Forget (confirmed in a
 * sheet). Shown even when the card is collapsed, so what the agent relied on is never hidden.
 */
@Composable
internal fun MemoryReadList(reads: List<MemoryRef>) {
    val actions = LocalMemoryReads.current
    LaunchedEffect(reads) { actions.check(reads) }
    var confirm by remember { mutableStateOf<MemoryRef?>(null) }
    GroupDivider(inset = Spacing.md)
    Column(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.xs)) {
        Text("Memory read", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        reads.forEach { ref ->
            val gone = ref in actions.forgotten
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(LocusIcons.Note, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    memoryReadLabel(ref),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (gone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (gone) {
                    Text(
                        "Forgotten",
                        Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = { confirm = ref }) { Text("Forget") }
                }
            }
        }
    }
    confirm?.let { ref ->
        LocusSheet(
            title = "Forget “${ref.topic.replace('-', ' ')}”?",
            subtitle = if (ref.general) {
                "Locus deletes this note from your general memory, so no chat will know it from now on. This chat has already read it."
            } else {
                "Locus deletes this note from its memory and won't know it in new chats. This chat has already read it."
            },
            onDismiss = { confirm = null },
            action = "Forget",
            actionTone = Tone.Critical,
            onAction = {
                actions.forget(ref)
                confirm = null
            },
        ) {}
    }
}

/** A topic as the card lists it: its name in words, and where it came from in a project chat. */
internal fun memoryReadLabel(ref: MemoryRef): String = ref.topic.replace('-', ' ') + if (ref.general) " · general memory" else ""
