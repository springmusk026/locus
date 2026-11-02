package dev.locus.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow

// Rows for a CardGroup of ListRows with a 36dp leading tile; dividers between them use Insets.iconRow.

/** A list row's trailing chevron: the row opens a page or a sheet. */
@Composable
fun RowChevron() {
    Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The last row of a group: adds (or imports) an item. */
@Composable
fun AddRow(title: String, icon: ImageVector = LocusIcons.Add, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.padding(start = Spacing.md), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A row that opens in place to show [content] under it. Shows [badge] as a pill, or else [meta] as
 * small text, at the end; the subtitle shows more lines while open.
 */
@Composable
fun ExpandableRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badge: String? = null,
    meta: String? = null,
    startExpanded: Boolean = false,
    badgeTone: Tone = Tone.Caution,
    /** When this changes the row returns to [startExpanded]; by default when the badge changes. */
    expandKey: String? = badge,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title, expandKey) { mutableStateOf(startExpanded) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) 6 else 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                badge != null -> StatusPill(badge, badgeTone)
                meta != null -> Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
        }
    }
}
