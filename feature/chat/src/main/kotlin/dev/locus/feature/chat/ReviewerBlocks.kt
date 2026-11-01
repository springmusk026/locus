package dev.locus.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.data.agent.ReviewerBlock
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing

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
            val caution = Locus.colors.caution
            Row(
                Modifier.fillMaxWidth()
                    .background(caution.copy(alpha = 0.10f), RoundedCornerShape(Radius.chip))
                    .padding(start = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Blocked: ${block.summary} — ${block.reason}",
                    Modifier.weight(1f).padding(vertical = Spacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (block.queued) {
                    Text("Runs at the next step", Modifier.padding(horizontal = Spacing.sm), style = MaterialTheme.typography.labelMedium, color = caution)
                } else {
                    TextButton(onClick = { onRunAnyway(block.callId) }, contentPadding = PaddingValues(horizontal = Spacing.sm)) { Text("Run anyway") }
                    IconButton(onClick = { onDismiss(block.callId) }, modifier = Modifier.size(32.dp)) {
                        Icon(LocusIcons.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
