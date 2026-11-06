package dev.locus.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Something the user put off and can finish in one tap: a step skipped in setup, offered again where
 * they'll see it (Home). The anatomy of Home's other cards (a tinted [IconTile], a [LocusType.cardTitle]
 * title, body text under it) on a plain [LocusCard]: no glow or tone outline, because nothing is wrong.
 * [eyebrow] says where it comes from and how much is left ("Finish setting up · 3 left").
 *
 * One explicit [action] button and a dismiss button in the header, the same place as a [Banner]'s; the
 * card itself isn't tappable, so there are exactly two targets and each says what it does. Text is
 * never cut: at a large font the card grows.
 */
@Composable
fun SetupCard(
    title: String,
    body: String,
    icon: ImageVector,
    action: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
) {
    LocusCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, size = IconSize.tile, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                if (eyebrow != null) SupportingText(eyebrow, style = LocusType.chip)
                Text(title, style = LocusType.cardTitle)
            }
            IconButton(onClick = onDismiss) {
                Icon(LocusIcons.Close, contentDescription = "Dismiss: $title", modifier = Modifier.size(IconSize.medium))
            }
        }
        SupportingText(body, Modifier.padding(top = Spacing.xs), style = LocusType.rowSupporting)
        ActionRow(contentPadding = PaddingValues()) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** The setup card as Home shows it, for the catalogue. */
@Composable
internal fun SetupCardPreview() {
    SetupCard(
        title = "Add a machine",
        body = "Let the agent run commands on a computer you reach over SSH.",
        icon = LocusIcons.Server,
        action = "Add a machine",
        onAction = {},
        onDismiss = {},
        eyebrow = "Finish setting up\u00A0·\u00A03\u00A0left",
    )
}
