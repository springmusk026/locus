package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.cardSegment
import dev.locus.core.designsystem.pressable
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.model.MemoryScope

/** Every project, newest activity first, and a way to make one (docs/design/projects.md). */
@Composable
fun ProjectsRoute(
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onNewProject: () -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                "Projects",
                onBack = onBack,
                actions = { IconButton(onClick = onNewProject) { Icon(LocusIcons.Add, contentDescription = "New project") } },
            )
        },
    ) { padding ->
        val list = rows ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    "No projects yet",
                    "A project keeps chats together with their own folder, instructions, memory and permissions.",
                    icon = LocusIcons.Folder,
                    action = { Button(onClick = onNewProject) { Text("New project") } },
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.xs, bottom = padding.calculateBottomPadding() + Spacing.xxl),
        ) {
            list.forEachIndexed { i, row ->
                item(key = row.project.id) {
                    Box(Modifier.cardSegment(i, list.size)) {
                        Column {
                            if (i > 0) GroupDivider(inset = Spacing.lg + 36.dp + Spacing.md)
                            ListRow(
                                title = row.project.name,
                                subtitle = projectSummary(row),
                                onClick = { onOpenProject(row.project.id) },
                                leading = { IconTile(LocusIcons.Folder) },
                                trailing = { RowChevron() },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun projectSummary(row: ProjectRow): String = listOfNotNull(
    if (row.chats == 1) "1 chat" else "${row.chats} chats",
    row.project.folder,
    row.lastActive?.let { relativeTime(it) },
).joinToString(" · ")

/** One project: what it is set up with, its chats, and a new chat inside it. */
@Composable
fun ProjectRoute(
    projectId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onEdit: () -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val chatsFlow = remember(projectId) { viewModel.chats(projectId) }
    val chats by chatsFlow.collectAsStateWithLifecycle()
    val row = rows?.firstOrNull { it.project.id == projectId }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                row?.project?.name ?: "Project",
                onBack = onBack,
                status = row?.let { { Text(it.project.folder, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                actions = { IconButton(onClick = onEdit) { Icon(LocusIcons.Edit, contentDescription = "Edit project") } },
            )
        },
    ) { padding ->
        if (rows != null && row == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { EmptyState("Project not found", "It may have been deleted.", icon = LocusIcons.Folder) }
            return@Scaffold
        }
        val project = row?.project ?: return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.xs, bottom = padding.calculateBottomPadding() + Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "new") {
                Button(onClick = { viewModel.newChat(project.id, onOpenChat) }, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
                    Icon(LocusIcons.Add, contentDescription = null)
                    Text("New chat in this project", Modifier.padding(start = Spacing.sm))
                }
            }
            item(key = "setup") {
                CardGroup(Modifier.padding(top = Spacing.sm)) {
                    ListRow(title = "Workspace folder", subtitle = project.folder, leading = { IconTile(LocusIcons.Folder) })
                    GroupDivider(inset = Spacing.lg + 36.dp + Spacing.md)
                    ListRow(
                        title = "Memory",
                        subtitle = when (project.memoryScope) {
                            MemoryScope.PROJECT_ONLY -> "This project's memory only"
                            MemoryScope.PROJECT_AND_GLOBAL -> "This project's memory, then your general memory"
                        },
                        leading = { IconTile(LocusIcons.Library) },
                    )
                    GroupDivider(inset = Spacing.lg + 36.dp + Spacing.md)
                    ListRow(
                        title = "Model",
                        subtitle = row.modelLabel,
                        leading = { IconTile(LocusIcons.Sparkle) },
                    )
                }
            }
            item(key = "note") {
                SectionNote(
                    "Each chat keeps the instructions, memory setting and folder it started with. Edits apply to new chats.",
                    Modifier.padding(top = Spacing.sm, start = Spacing.xs),
                )
            }
            item(key = "chats-label") { SectionLabel(if (chats.isEmpty()) "No chats yet" else "Chats", Modifier.padding(start = 0.dp)) }
            chats.forEachIndexed { i, c ->
                item(key = c.id) {
                    Box(Modifier.cardSegment(i, chats.size)) {
                        Column {
                            if (i > 0) GroupDivider()
                            ListRow(
                                title = c.title,
                                subtitle = relativeTime(c.updatedAt),
                                onClick = { onOpenChat(c.id) },
                                trailing = { RowChevron() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The chip under a chat's title naming its project; tap for what the project means for this chat. */
@Composable
internal fun ProjectChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.pressable(onClick).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LocusIcons.Folder, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
