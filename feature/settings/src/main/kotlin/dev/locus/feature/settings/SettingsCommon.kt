package dev.locus.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.Spacing

/** Inset for dividers between rows that have a 36dp leading tile. */
internal val ROW_INSET = Spacing.lg + 36.dp + Spacing.md

/** The frame every Settings page shares: a top bar with back, and a padded list. */
@Composable
internal fun SettingsPageScaffold(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LocusTopBar(title, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
            content = content,
        )
    }
}

@Composable
internal fun Chevron() {
    Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The last row of a group: adds an item. */
@Composable
internal fun AddRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(LocusIcons.Add, tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.padding(start = Spacing.md), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        // A switch's text is what it does, so it wraps rather than being cut off.
        titleMaxLines = 2,
        subtitleMaxLines = 4,
        onClick = { onChange(!checked) },
        leading = { IconTile(icon) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

internal val SectionPadding = Modifier.padding(start = 0.dp)
internal val NotePadding = Modifier.padding(top = Spacing.sm, start = 0.dp)
