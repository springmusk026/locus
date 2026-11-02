package dev.locus.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.Spacing
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
        subtitle = when {
            current != null && empty -> "Nothing has been sent yet, so this chat can still move to another project or out of this one."
            current != null -> "This chat keeps the instructions, memory setting and folder it started with. Edits to the project apply to new chats."
            empty -> "Move this chat into a project. Nothing has been sent yet, so it starts with that project's instructions, memory and folder."
            else -> "This chat has already started, so it can't join a project: its instructions, memory and folder are fixed when it starts, " +
                "which keeps its cached prompt valid. Start a new chat in a project that picks up from this one instead."
        },
        onDismiss = onDismiss,
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg)) {
            if (current != null) {
                CardGroup {
                    ListRow(title = "Folder", subtitle = current.folder, leading = { IconTile(LocusIcons.Folder) })
                    GroupDivider()
                    ListRow(
                        title = "Memory",
                        subtitle = if (current.memoryScope == MemoryScope.PROJECT_ONLY) "This project's memory only" else "This project's memory, then your general memory",
                        leading = { IconTile(LocusIcons.Library) },
                    )
                    GroupDivider()
                    ListRow(title = "Open project", onClick = { onDismiss(); onOpenProject(current.id) }, trailing = { RowChevron() })
                }
            }
            if (empty || current == null) {
                if (others.isNotEmpty()) {
                    Text(
                        if (empty) "Move to" else "Continue in",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs, start = Spacing.xs),
                    )
                    CardGroup {
                        others.forEachIndexed { i, p ->
                            if (i > 0) GroupDivider()
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
                    CardGroup(Modifier.padding(top = Spacing.md)) {
                        ListRow(title = "Take out of the project", onClick = { onDismiss(); onMove(null) })
                    }
                }
            }
        }
    }
}
