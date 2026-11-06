package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.pressable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing

/**
 * Temporary chats in the UI (docs/design/temporary-chats.md): the switch in the home composer's + sheet,
 * the "Temporary" marker under a chat's title, and the sheet that says what it means, shown by itself
 * the first time the switch is turned on.
 */

/** One point of what a temporary chat does, for the sheet (and its test). */
internal data class TemporaryPoint(val icon: ImageVector, val title: String, val body: String)

/** What a temporary chat does, in the order the sheet lists it. */
internal val TEMPORARY_POINTS: List<TemporaryPoint> = listOf(
    TemporaryPoint(LocusIcons.EyeOff, "No memory", "Locus doesn't read or save memory here, look at your other chats, or learn skills from it."),
    TemporaryPoint(LocusIcons.Trash, "Deleted when you leave", "It's not in your chat list or search. A task still running finishes first, for up to 24 hours."),
    TemporaryPoint(LocusIcons.Folder, "Files stay", "Files it writes go to the workspace like any chat's, and stay there."),
    TemporaryPoint(LocusIcons.Shield, "Same safety", "Approvals and permissions work as usual. Its cost still counts in Insights."),
)

/** The + sheet's subtitle for the switch. */
internal const val TEMPORARY_SWITCH_SUBTITLE = "No memory, not in your chats, deleted when you leave."

/**
 * The line under a temporary chat's title, where a project's name would be (a temporary chat is never in
 * one), drawn exactly like [ProjectChip] so the header keeps its height and alignment. Opens [TemporarySheet].
 * The touch target is the row's, which Compose widens to 48dp without growing the header.
 */
@Composable
internal fun TemporaryMarker(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .pressable(onClick)
            .clearAndSetSemantics {
                contentDescription = "Temporary chat"
                role = Role.Button
                onClick(label = "Learn what that means") { onClick(); true }
            }
            .padding(top = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LocusIcons.EyeOff, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        SupportingText("Temporary", style = LocusType.chip, maxLines = 1)
    }
}

/**
 * What a temporary chat means. On home ([onTurnOff] set) it's about the chat the ask bar starts next, and
 * can turn the switch back off; in a chat it only explains, since a chat can't change after it's created.
 */
@Composable
internal fun TemporarySheet(onDismiss: () -> Unit, onTurnOff: (() -> Unit)? = null) {
    LocusSheet(
        title = "Temporary chat",
        subtitle = if (onTurnOff != null) "Your next chat starts temporary." else "This chat started temporary and stays that way.",
        onDismiss = onDismiss,
        action = "Got it",
        onAction = onDismiss,
    ) {
        CardGroup {
            TEMPORARY_POINTS.forEachIndexed { i, point ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                ListRow(point.title, subtitle = point.body, subtitleMaxLines = Int.MAX_VALUE, titleMaxLines = 2, leading = { IconTile(point.icon) })
            }
        }
        if (onTurnOff != null) {
            TextButton(
                onClick = onTurnOff,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).padding(horizontal = Spacing.lg),
            ) { Text("Start a normal chat instead") }
        }
    }
}
