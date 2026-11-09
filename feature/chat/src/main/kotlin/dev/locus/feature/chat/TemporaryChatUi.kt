package dev.locus.feature.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
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

/** One point of what a temporary chat does, for the sheet. */
internal data class TemporaryPoint(val icon: ImageVector, @StringRes val title: Int, @StringRes val body: Int)

/** What a temporary chat does, in the order the sheet lists it. */
internal val TEMPORARY_POINTS: List<TemporaryPoint> = listOf(
    TemporaryPoint(LocusIcons.EyeOff, R.string.chat_temporary_no_memory_title, R.string.chat_temporary_no_memory_body),
    TemporaryPoint(LocusIcons.Trash, R.string.chat_temporary_deleted_title, R.string.chat_temporary_deleted_body),
    TemporaryPoint(LocusIcons.Folder, R.string.chat_temporary_files_title, R.string.chat_temporary_files_body),
    TemporaryPoint(LocusIcons.Shield, R.string.chat_temporary_safety_title, R.string.chat_temporary_safety_body),
)

/**
 * The line under a temporary chat's title, where a project's name would be (a temporary chat is never in
 * one), drawn exactly like [ProjectChip] so the header keeps its height and alignment. Opens [TemporarySheet].
 * The touch target is the row's, which Compose widens to 48dp without growing the header.
 */
@Composable
internal fun TemporaryMarker(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.chat_temporary_description)
    val learnLabel = stringResource(R.string.chat_temporary_learn)
    Row(
        modifier
            .pressable(onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = learnLabel) { onClick(); true }
            }
            .padding(top = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LocusIcons.EyeOff, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        SupportingText(stringResource(R.string.chat_temporary_marker), style = LocusType.chip, maxLines = 1)
    }
}

/**
 * What a temporary chat means. On home ([onTurnOff] set) it's about the chat the ask bar starts next, and
 * can turn the switch back off; in a chat it only explains, since a chat can't change after it's created.
 */
@Composable
internal fun TemporarySheet(onDismiss: () -> Unit, onTurnOff: (() -> Unit)? = null) {
    LocusSheet(
        title = stringResource(R.string.chat_temporary_title),
        subtitle = stringResource(if (onTurnOff != null) R.string.chat_temporary_subtitle_next else R.string.chat_temporary_subtitle_this),
        onDismiss = onDismiss,
        action = stringResource(R.string.chat_temporary_got_it),
        onAction = onDismiss,
    ) {
        CardGroup {
            TEMPORARY_POINTS.forEachIndexed { i, point ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                ListRow(stringResource(point.title), subtitle = stringResource(point.body), subtitleMaxLines = Int.MAX_VALUE, titleMaxLines = 2, leading = { IconTile(point.icon) })
            }
        }
        if (onTurnOff != null) {
            TextButton(
                onClick = onTurnOff,
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).padding(horizontal = Spacing.lg),
            ) { Text(stringResource(R.string.chat_temporary_start_normal)) }
        }
    }
}
