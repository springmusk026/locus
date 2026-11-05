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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import dev.locus.core.designsystem.SectionLabel
import dev.locus.feature.workspace.linux.LinuxCard
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.FileTypeIcon
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.CompactIconButton
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocusFeedbackHost
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.ProvideLocusFeedback
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.rememberLocusFeedbackState
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.cardSegment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceRoute(
    onBack: () -> Unit,
    /** Opens the interactive terminal (docs/design/terminal.md). */
    onOpenTerminal: () -> Unit = {},
    /** Opens the Linux health screen, from the Linux card's installed state; null hides that button. */
    onOpenHealth: (() -> Unit)? = null,
    /** A file to open at once (Home's Recent files); back from it leaves the screen. Null opens the list. */
    openPath: String? = null,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(openPath) { openPath?.let(viewModel::openFromOutside) }
    // Until the app root hosts feedback (docs/design/design-system.md, migration step 1), Workspace hosts its own.
    val feedback = rememberLocusFeedbackState()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = state.openFile?.path
        if (uri != null && path != null) viewModel.export(path, uri)
    }

    LaunchedEffect(state.message, state.undoTrashId) {
        val message = state.message ?: return@LaunchedEffect
        val undo = state.undoTrashId
        // Undo runs in the host's scope, so it still restores after the message has gone.
        feedback.show(message, action = undo?.let { "Undo" }, onAction = undo?.let { id -> { viewModel.restore(id) } })
        viewModel.messageShown()
    }
    BackHandler { if (!viewModel.up()) onBack() }

    // Per-file viewer state: source/preview toggle and the top bar's fact (pages, dimensions, language).
    val openPath = state.openFile?.path
    var showSource by rememberSaveable(openPath) { mutableStateOf(state.openFile?.let(::defaultShowsSource) ?: false) }
    var detail by remember(openPath) { mutableStateOf<String?>(null) }
    var menuOpen by remember(openPath) { mutableStateOf(false) }

    val heatServices by viewModel.heatServices.collectAsStateWithLifecycle()
    ProvideLocusFeedback(feedback) {
    Scaffold(
        snackbarHost = { LocusFeedbackHost(feedback) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (heatServices.isNotEmpty()) {
                dev.locus.core.designsystem.Banner(
                    dev.locus.core.data.device.LinuxHeatAdvice.message(heatServices),
                    dev.locus.core.designsystem.Tone.Caution,
                    Modifier.navigationBarsPadding().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    title = "Phone is very hot",
                    actionsBelow = true,
                ) {
                    TextButton(onClick = viewModel::dismissHeatServices) { Text("Not now") }
                    TextButton(onClick = viewModel::stopServicesForHeat) { Text("Stop services") }
                }
            }
        },
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
                            FileTypeIcon(open.path, isDirectory = false, size = IconSize.medium)
                            SupportingText(facts, style = LocusType.mono, maxLines = 1)
                        }
                    } else {
                        SupportingText(where, style = LocusType.mono, maxLines = 1)
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
                                        text = { Text("Delete", color = Locus.colors.critical) },
                                        leadingIcon = { Icon(LocusIcons.Trash, contentDescription = null, tint = Locus.colors.critical) },
                                        onClick = { menuOpen = false; viewModel.delete(open.path) },
                                    )
                                }
                            }
                        }
                        !state.showTrash -> {
                            IconButton(onClick = onOpenTerminal) { Icon(LocusIcons.Terminal, contentDescription = "Terminal") }
                            IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Icon(LocusIcons.Import, contentDescription = "Import files") }
                            IconButton(onClick = { viewModel.showTrash(true) }) { Icon(LocusIcons.Trash, contentDescription = "Trash") }
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
            state.loading -> LoadingState(Modifier.padding(padding))
            state.entries.isEmpty() && state.folder != "." -> EmptyState(
                title = "This folder is empty",
                body = "Files Locus creates appear here. You can also import files, or open the workspace from your phone's Files app under “Locus workspace”.",
                modifier = Modifier.padding(padding),
                icon = LocusIcons.Folder,
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.sm, bottom = Spacing.xxl),
            ) {
                // The workspace's root has Linux's card first (docs/design/linux-setup.md), then the files.
                if (state.folder == ".") {
                    item(key = "linux") { LinuxCard(onOpenTerminal, Modifier.padding(bottom = Spacing.lg), onOpenHealth = onOpenHealth) }
                    item(key = "files-label") { SectionLabel("Files", Modifier.padding(bottom = Spacing.sm)) }
                    if (state.entries.isEmpty()) {
                        item(key = "files-empty") {
                            EmptyState(
                                title = "The workspace is empty",
                                body = "Files Locus creates appear here. You can also import files, or open the workspace from your phone's Files app under “Locus workspace”.",
                                icon = LocusIcons.Folder,
                            )
                        }
                    }
                }
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
                            SupportingText(
                                if (entry.isDirectory) modified else "${Formatter.formatShortFileSize(context, entry.size)} · $modified",
                                style = LocusType.mono,
                            )
                        },
                        trailing = { if (entry.isDirectory) Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }
    }
    } // ProvideLocusFeedback
}

@Composable
private fun TrashList(state: WorkspaceUiState, onRestore: (String) -> Unit, onPurge: (String) -> Unit, modifier: Modifier) {
    if (state.trash.isEmpty()) {
        EmptyState("Trash is empty", "Files Locus deletes or overwrites are kept here so you can undo its changes.", modifier, icon = LocusIcons.Restore)
        return
    }
    // The trash entry waiting for "Delete permanently" to be confirmed.
    var purging by remember { mutableStateOf<String?>(null) }
    state.trash.firstOrNull { it.id == purging }?.let { entry ->
        ConfirmSheet(
            title = "Delete permanently?",
            message = "${entry.originalPath} can't be restored after this.",
            confirmLabel = "Delete",
            tone = Tone.Critical,
            onConfirm = { onPurge(entry.id); purging = null },
            onDismiss = { purging = null },
        )
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xxl)) {
        val count = state.trash.size
        items(count, key = { state.trash[it].id }) { i ->
            val entry = state.trash[i]
            val what = when (entry.reason.name) {
                "OVERWRITTEN" -> "Earlier version"
                "MOVED_OVER" -> "Replaced by a move"
                "MOVED_AWAY" -> "Before a move"
                else -> "Deleted"
            }
            ListRow(
                title = entry.originalPath,
                modifier = Modifier.cardSegment(i, count),
                subtitle = "$what · ${relativeTime(entry.trashedAt)}",
                // Paths and reasons get two lines; the actions are icons so the name keeps the row.
                titleMaxLines = 2,
                subtitleMaxLines = 2,
                leading = { FileTypeIcon(entry.originalPath, isDirectory = false) },
                trailing = {
                    CompactIconButton(LocusIcons.Restore, contentDescription = "Restore ${entry.originalPath.substringAfterLast('/')}", onClick = { onRestore(entry.id) }, tint = MaterialTheme.colorScheme.primary)
                    CompactIconButton(LocusIcons.Close, contentDescription = "Delete ${entry.originalPath.substringAfterLast('/')} permanently", onClick = { purging = entry.id })
                },
            )
        }
    }
}
