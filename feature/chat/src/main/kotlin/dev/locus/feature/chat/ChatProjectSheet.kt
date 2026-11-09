package dev.locus.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.Project

/** A chat's project, and the projects it could join or continue in. */
data class ChatProjectInfo(val current: Project? = null, val all: List<Project> = emptyList())

/**
 * What the chat's project means for it, and the cache-safe ways to change it
 * (docs/design/projects.md, "Moving chats"):
 * - a chat with no messages yet moves freely, since its prompt is rebuilt before anything is cached;
 * - a chat that has started can't move, because its prompt and folder are fixed, so it offers a new
 *   chat in the project that picks up from this one.
 */
@Composable
internal fun ChatProjectSheet(
    info: ChatProjectInfo,
    empty: Boolean,
    onDismiss: () -> Unit,
    onOpenProject: (String) -> Unit,
    onMove: (String?) -> Unit,
    onContinueIn: (String) -> Unit,
) {
    val current = info.current
    val others = info.all.filter { it.id != current?.id }
    LocusSheet(
        title = current?.name ?: stringResource(R.string.chat_project_title),
        subtitle = stringResource(if (current != null) R.string.chat_project_subtitle else R.string.chat_project_subtitle_none),
        onDismiss = onDismiss,
    ) {
        // Why it can or can't move, in plain words, under the title rather than packed into it.
        SectionNote(
            stringResource(
                when {
                    current != null && empty -> R.string.chat_project_note_can_move
                    current != null -> R.string.chat_project_note_fixed
                    empty -> R.string.chat_project_note_can_join
                    else -> R.string.chat_project_note_cannot_join
                },
            ),
        )
        if (current != null) {
            CardGroup {
                ListRow(title = stringResource(R.string.chat_project_folder), subtitle = current.folder, leading = { IconTile(LocusIcons.Folder) })
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = stringResource(R.string.chat_project_memory),
                    subtitle = stringResource(if (current.memoryScope == MemoryScope.PROJECT_ONLY) R.string.chat_project_memory_only else R.string.chat_project_memory_then_general),
                    subtitleMaxLines = 2,
                    leading = { IconTile(LocusIcons.Library) },
                )
                GroupDivider(inset = Insets.iconRow)
                ListRow(title = stringResource(R.string.chat_project_open), onClick = { onDismiss(); onOpenProject(current.id) }, leading = { IconTile(LocusIcons.Settings) }, trailing = { RowChevron() })
            }
        }
        if (empty || current == null) {
            if (others.isNotEmpty()) {
                SectionLabel(stringResource(if (empty) R.string.chat_project_move_to else R.string.chat_project_continue_in))
                CardGroup {
                    others.forEachIndexed { i, p ->
                        if (i > 0) GroupDivider(inset = Insets.iconRow)
                        ListRow(
                            title = p.name,
                            subtitle = p.folder,
                            leading = { IconTile(LocusIcons.Folder) },
                            onClick = { onDismiss(); if (empty) onMove(p.id) else onContinueIn(p.id) },
                        )
                    }
                }
            }
            if (empty && current != null) {
                CardGroup {
                    ListRow(title = stringResource(R.string.chat_project_take_out), onClick = { onDismiss(); onMove(null) }, leading = { IconTile(LocusIcons.Close) })
                }
            }
        }
    }
}
