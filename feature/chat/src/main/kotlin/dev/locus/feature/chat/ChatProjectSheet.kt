package dev.locus.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        title = current?.name ?: "Projects",
        subtitle = if (current != null) "This chat's project" else "This chat isn't in a project",
        onDismiss = onDismiss,
    ) {
        // Why it can or can't move, in plain words, under the title rather than packed into it.
        SectionNote(
            when {
                current != null && empty -> "Nothing has been sent yet, so this chat can still move to another project or out of this one."
                current != null -> "This chat keeps the instructions, memory and folder it started with. Changes to the project apply to new chats."
                empty -> "Nothing has been sent yet, so this chat can move into a project and start with its instructions, memory and folder."
                else -> "A chat's instructions, memory and folder are set when it starts, so this one can't join a project. " +
                    "Start a new chat in a project that picks up from this one instead."
            },
        )
        if (current != null) {
            CardGroup {
                ListRow(title = "Folder", subtitle = current.folder, leading = { IconTile(LocusIcons.Folder) })
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = "Memory",
                    subtitle = if (current.memoryScope == MemoryScope.PROJECT_ONLY) "This project's only" else "This project's, then your general memory",
                    subtitleMaxLines = 2,
                    leading = { IconTile(LocusIcons.Library) },
                )
                GroupDivider(inset = Insets.iconRow)
                ListRow(title = "Open project", onClick = { onDismiss(); onOpenProject(current.id) }, leading = { IconTile(LocusIcons.Settings) }, trailing = { RowChevron() })
            }
        }
        if (empty || current == null) {
            if (others.isNotEmpty()) {
                SectionLabel(if (empty) "Move to" else "Continue in")
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
                    ListRow(title = "Take out of the project", onClick = { onDismiss(); onMove(null) }, leading = { IconTile(LocusIcons.Close) })
                }
            }
        }
    }
}
