package dev.locus.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.locus.core.data.agent.ReviewerBlock
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SideOrBelow
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.container

/**
 * Calls the safety reviewer blocked, as small non-modal chips above the composer: what was stopped
 * and why, with "Run anyway" (runs exactly that call) and dismiss. The agent has already been told
 * and carries on; these never hold up the chat.
 */
@Composable
internal fun ReviewerBlockChips(
    blocks: List<ReviewerBlock>,
    onRunAnyway: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (blocks.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        // The latest two; older ones are still in Activity.
        blocks.takeLast(2).forEach { block ->
            // The whole sentence is read; the actions sit beside it while it keeps most of the width, under it otherwise.
            SideOrBelow(
                Modifier.fillMaxWidth()
                    .background(Tone.Caution.container(), MaterialTheme.shapes.small)
                    .padding(start = Spacing.md),
                gap = Spacing.xs,
                main = {
                    Text(
                        "Blocked: ${block.summary} — ${block.reason}",
                        Modifier.padding(top = Spacing.sm, bottom = Spacing.sm, end = Spacing.md),
                        style = LocusType.supporting,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (block.queued) {
                        Text("Runs at the next step", Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm), style = LocusType.chip, color = Locus.colors.onCautionContainer)
                    } else {
                        TextButton(onClick = { onRunAnyway(block.callId) }, contentPadding = PaddingValues(horizontal = Spacing.sm)) { Text("Run anyway") }
                        IconButton(onClick = { onDismiss(block.callId) }) {
                            Icon(LocusIcons.Close, contentDescription = "Dismiss", modifier = Modifier.size(IconSize.small))
                        }
                    }
                }
            }
        }
    }
}
