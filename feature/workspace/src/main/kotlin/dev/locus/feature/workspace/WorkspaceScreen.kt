package dev.locus.feature.workspace

import androidx.compose.runtime.getValue
import dev.locus.core.designsystem.relativeTime
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.feature.workspace.preview.FilePreview
import dev.locus.feature.workspace.preview.defaultShowsSource
import dev.locus.feature.workspace.preview.hasSourceToggle
import dev.locus.feature.workspace.preview.openWith
import dev.locus.feature.workspace.preview.share
import dev.locus.feature.workspace.preview.textDetail
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.FileTypeIcon
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.cardSegment
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceRoute(onBack: () -> Unit, viewModel: WorkspaceViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = state.openFile?.path
        if (uri != null && path != null) viewModel.export(path, uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    BackHandler { if (!viewModel.up()) onBack() }

    // Per-file viewer state: source/preview toggle and the top bar's fact (pages, dimensions, language).
    val openPath = state.openFile?.path
    var showSource by rememberSaveable(openPath) { mutableStateOf(state.openFile?.let(::defaultShowsSource) ?: false) }
    var detail by remember(openPath) { mutableStateOf<String?>(null) }
    var menuOpen by remember(openPath) { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val open = state.openFile
            LocusTopBar(
                when {
                    open != null -> open.name
                    state.showTrash -> "Trash"
                    state.folder == "." -> "Workspace"
                    else -> state.folder.substringAfterLast('/')
                },
                onBack = { if (!viewModel.up()) onBack() },
                status = {
                    val where = when {
                        open != null -> open.path
                        state.showTrash -> "Deleted and overwritten files"
                        state.folder == "." -> "/workspace"
                        else -> "/workspace/${state.folder}"
                    }
                    if (open != null) {
                        val facts = listOfNotNull(Formatter.formatShortFileSize(context, open.size), detail ?: textDetail(open)).joinToString(" · ")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            FileTypeIcon(open.path, isDirectory = false, size = 20.dp)
                            Text(facts, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Text(where, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    when {
                        open != null -> {
                            if (hasSourceToggle(open)) {
                                TextButton(onClick = { showSource = !showSource }) { Text(if (showSource) "Preview" else "Source") }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Icon(LocusIcons.More, contentDescription = "File actions") }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Open with…") }, onClick = { menuOpen = false; openWith(context, open) })
                                    DropdownMenuItem(text = { Text("Share") }, onClick = { menuOpen = false; share(context, open) })
                                    DropdownMenuItem(text = { Text("Export") }, onClick = { menuOpen = false; exportLauncher.launch(open.name) })
                                    if (open.text != null) {
                                        DropdownMenuItem(text = { Text("Copy text") }, onClick = {
                                            menuOpen = false
                                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                            cm?.setPrimaryClip(android.content.ClipData.newPlainText(open.name, open.text))
                                        })
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(LocusIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menuOpen = false; viewModel.delete(open.path) },
                                    )
                                }
                            }
                        }
                        !state.showTrash -> {
                            IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Icon(LocusIcons.Import, contentDescription = "Import files") }
                            IconButton(onClick = { viewModel.showTrash(true) }) { Icon(LocusIcons.Restore, contentDescription = "Trash") }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val open = state.openFile
        when {
            open != null -> FilePreview(
                open,
                showSource = showSource,
                onExport = { exportLauncher.launch(open.name) },
                onDetail = { detail = it },
                modifier = Modifier.padding(padding),
            )
            state.showTrash -> TrashList(state, viewModel::restore, viewModel::purge, Modifier.padding(padding))
            state.loading -> Unit
            state.entries.isEmpty() -> EmptyState(
                title = if (state.folder == ".") "The workspace is empty" else "This folder is empty",
                body = "Files Locus creates appear here. You can also import files, or open the workspace from your phone's Files app under “Locus workspace”.",
                modifier = Modifier.padding(padding),
                icon = LocusIcons.Folder,
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.sm, bottom = Spacing.xxl),
            ) {
                val count = state.entries.size
                items(count, key = { state.entries[it].path }) { i ->
                    val entry = state.entries[i]
                    ListRow(
                        title = entry.name,
                        modifier = Modifier.cardSegment(i, count),
                        onClick = { viewModel.open(entry) },
                        leading = {
                            FileTypeIcon(entry.name, entry.isDirectory)
                        },
                        meta = {
                            val modified = relativeTime(entry.modified)
                            Text(
                                if (entry.isDirectory) modified else "${Formatter.formatShortFileSize(context, entry.size)} · $modified",
                                style = MonoCaption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailing = { if (entry.isDirectory) Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashList(state: WorkspaceUiState, onRestore: (String) -> Unit, onPurge: (String) -> Unit, modifier: Modifier) {
    if (state.trash.isEmpty()) {
        EmptyState("Trash is empty", "Files Locus deletes or overwrites are kept here so you can undo its changes.", modifier, icon = LocusIcons.Restore)
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xxl)) {
        val count = state.trash.size
        items(count, key = { state.trash[it].id }) { i ->
            val entry = state.trash[i]
            val what = when (entry.reason.name) {
                "OVERWRITTEN" -> "Earlier version"
                "MOVED_OVER" -> "Replaced by a move"
                else -> "Deleted"
            }
            ListRow(
                title = entry.originalPath,
                modifier = Modifier.cardSegment(i, count),
                subtitle = "$what · ${relativeTime(entry.trashedAt)}",
                leading = { FileTypeIcon(entry.originalPath, isDirectory = false) },
                trailing = {
                    TextButton(onClick = { onRestore(entry.id) }) { Text("Restore") }
                    IconButton(onClick = { onPurge(entry.id) }, modifier = Modifier.size(36.dp)) {
                        Icon(LocusIcons.Close, contentDescription = "Delete permanently", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                },
            )
        }
    }
}
