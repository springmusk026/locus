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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
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
                stringResource(R.string.chat_projects_title),
                onBack = onBack,
                // With none yet, the empty state's own New project button is the one way in.
                actions = { if (!rows.isNullOrEmpty()) IconButton(onClick = onNewProject) { Icon(LocusIcons.Add, contentDescription = stringResource(R.string.chat_projects_new)) } },
            )
        },
    ) { padding ->
        val list = rows ?: run {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    stringResource(R.string.chat_projects_empty_title),
                    stringResource(R.string.chat_projects_empty_body),
                    icon = LocusIcons.Folder,
                    action = { Button(onClick = onNewProject) { Text(stringResource(R.string.chat_projects_new)) } },
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
                            if (i > 0) GroupDivider(inset = Insets.iconRow)
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

/** "3 chats · 2 days ago": the folder is on the project's own screen (it usually repeats the name). */
@Composable
private fun projectSummary(row: ProjectRow): String = listOfNotNull(
    pluralStringResource(R.plurals.chat_projects_chats, row.chats, row.chats),
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
                row?.project?.name ?: stringResource(R.string.chat_project_screen_title),
                onBack = onBack,
                // The folder is the first row below; nothing to edit for a project that isn't there.
                actions = { if (row != null) IconButton(onClick = onEdit) { Icon(LocusIcons.Edit, contentDescription = stringResource(R.string.chat_project_screen_edit)) } },
            )
        },
    ) { padding ->
        if (rows != null && row == null) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(stringResource(R.string.chat_project_screen_not_found), stringResource(R.string.chat_project_screen_not_found_body), icon = LocusIcons.Folder) {
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.chat_project_screen_back)) }
                }
            }
            return@Scaffold
        }
        val project = row?.project ?: run {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.xs, bottom = padding.calculateBottomPadding() + Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "new") {
                Button(onClick = { viewModel.newChat(project.id, onOpenChat) }, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
                    Icon(LocusIcons.Add, contentDescription = null)
                    Text(stringResource(R.string.chat_project_screen_new_chat), Modifier.padding(start = Spacing.sm))
                }
            }
            item(key = "setup") {
                CardGroup(Modifier.padding(top = Spacing.sm)) {
                    ListRow(title = stringResource(R.string.chat_project_screen_folder), subtitle = project.folder, leading = { IconTile(LocusIcons.Folder) })
                    GroupDivider(inset = Insets.iconRow)
                    ListRow(
                        title = stringResource(R.string.chat_project_screen_memory),
                        subtitle = when (project.memoryScope) {
                            MemoryScope.PROJECT_ONLY -> stringResource(R.string.chat_project_screen_memory_only)
                            MemoryScope.PROJECT_AND_GLOBAL -> stringResource(R.string.chat_project_screen_memory_then_general)
                        },
                        subtitleMaxLines = 2,
                        leading = { IconTile(LocusIcons.Library) },
                    )
                    GroupDivider(inset = Insets.iconRow)
                    ListRow(
                        title = stringResource(R.string.chat_project_screen_model),
                        subtitle = row.modelLabel.asString(),
                        leading = { IconTile(LocusIcons.Server) },
                    )
                }
            }
            item(key = "note") {
                SectionNote(
                    stringResource(R.string.chat_project_screen_note),
                    Modifier.padding(top = Spacing.sm),
                )
            }
            item(key = "chats-label") { SectionLabel(stringResource(R.string.chat_project_screen_chats)) }
            if (chats.isEmpty()) {
                item(key = "no-chats") { SectionNote(stringResource(R.string.chat_project_screen_no_chats)) }
            }
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
        modifier.pressable(onClick).padding(top = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LocusIcons.Folder, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        SupportingText(name, style = LocusType.chip, maxLines = 1)
    }
}
