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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
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
import dev.locus.core.designsystem.SectionNote
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
    /**
     * Opens Locus's browser for the user's own browsing, beside the terminal: sites signed in to there
     * stay signed in for every chat's browser (docs/design/browser.md, "Take over and hand back").
     */
    onOpenBrowser: () -> Unit = {},
    /** Opens the Linux health screen, from the Linux card's installed state; null hides that button. */
    onOpenHealth: (() -> Unit)? = null,
    /** A file to open at once (Home's Recent files); back from it leaves the screen. Null opens the list. */
    openPath: String? = null,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(openPath) { openPath?.let(viewModel::openFromOutside) }
    // Workspace hosts its own messages (over the app root's), so they sit above its bottom bar.
    val feedback = rememberLocusFeedbackState()
    val context = LocalContext.current
    val noFolderPickerMessage = stringResource(R.string.workspace_files_no_folder_picker)
    val undoLabel = stringResource(R.string.workspace_files_undo)

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = state.openFile?.path
        if (uri != null && path != null) viewModel.export(path, uri)
    }
    // Connect a folder (docs/design/connected-folders.md): the system folder picker, for a new folder or to reconnect one.
    var reconnecting by rememberSaveable { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.picked(uri.toString(), reconnecting)
        reconnecting = null
    }
    val pickFolder = { reconnectId: String? ->
        reconnecting = reconnectId
        try {
            folderPicker.launch(null)
        } catch (_: android.content.ActivityNotFoundException) {
            reconnecting = null
            feedback.show(noFolderPickerMessage, Tone.Caution)
        }
    }
    state.pendingConnect?.let { pending ->
        ConnectFolderSheet(pending, state.connected.map { it.folder.name }, onConnect = viewModel::connect, onDismiss = viewModel::cancelConnect)
    }
    state.connected.firstOrNull { it.folder.id == state.managing }?.let { row ->
        FolderSheet(
            row,
            state.projects,
            onSave = viewModel::saveFolder,
            onReconnect = { viewModel.manage(null); pickFolder(row.folder.id) },
            onDisconnect = { viewModel.disconnect(row.folder.id) },
            onDismiss = { viewModel.manage(null) },
        )
    }
    val inConnected = state.connectedFolder

    LaunchedEffect(state.message, state.undoTrashId) {
        val message = state.message ?: return@LaunchedEffect
        val undo = state.undoTrashId
        // Undo runs in the host's scope, so it still restores after the message has gone.
        feedback.show(message, action = undo?.let { undoLabel }, onAction = undo?.let { id -> { viewModel.restore(id) } })
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
                    title = stringResource(R.string.workspace_heat_title),
                    actionsBelow = true,
                ) {
                    TextButton(onClick = viewModel::dismissHeatServices) { Text(stringResource(R.string.workspace_heat_not_now)) }
                    TextButton(onClick = viewModel::stopServicesForHeat) { Text(stringResource(R.string.workspace_heat_stop_services)) }
                }
            }
        },
        topBar = {
            val open = state.openFile
            LocusTopBar(
                when {
                    open != null -> open.name
                    state.opening != null -> state.opening.orEmpty().substringAfterLast('/')
                    state.showTrash -> stringResource(R.string.workspace_files_trash_title)
                    state.folder == "." -> stringResource(R.string.workspace_files_title)
                    else -> state.folder.substringAfterLast('/')
                },
                onBack = { if (!viewModel.up()) onBack() },
                status = {
                    if (open == null && state.opening == null && !state.showTrash && inConnected != null) {
                        ConnectedFolderStatus(state.folder)
                        return@LocusTopBar
                    }
                    val where = when {
                        open != null -> open.path
                        state.opening != null -> state.opening.orEmpty()
                        state.showTrash -> stringResource(R.string.workspace_files_trash_subtitle)
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
                                TextButton(onClick = { showSource = !showSource }) { Text(stringResource(if (showSource) R.string.workspace_files_preview else R.string.workspace_files_source)) }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Icon(LocusIcons.More, contentDescription = stringResource(R.string.workspace_files_actions)) }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.workspace_files_open_with)) }, onClick = { menuOpen = false; openWith(context, open, feedback) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.workspace_files_share)) }, onClick = { menuOpen = false; share(context, open, feedback) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.workspace_files_export)) }, onClick = { menuOpen = false; exportLauncher.launch(open.name) })
                                    if (open.text != null) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.workspace_files_copy_text)) }, onClick = {
                                            menuOpen = false
                                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                            cm?.setPrimaryClip(android.content.ClipData.newPlainText(open.name, open.text))
                                        })
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.workspace_files_delete), color = Locus.colors.critical) },
                                        leadingIcon = { Icon(LocusIcons.Trash, contentDescription = null, tint = Locus.colors.critical) },
                                        onClick = { menuOpen = false; viewModel.delete(open.path) },
                                    )
                                }
                            }
                        }
                        !state.showTrash -> {
                            IconButton(onClick = onOpenTerminal) { Icon(LocusIcons.Terminal, contentDescription = stringResource(R.string.workspace_files_terminal)) }
                            IconButton(onClick = onOpenBrowser) { Icon(LocusIcons.Globe, contentDescription = stringResource(R.string.workspace_files_browser)) }
                            if (inConnected == null) {
                                IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Icon(LocusIcons.Import, contentDescription = stringResource(R.string.workspace_files_import)) }
                            } else {
                                IconButton(onClick = { viewModel.manage(inConnected.id) }) { Icon(LocusIcons.Settings, contentDescription = stringResource(R.string.workspace_files_folder_settings, inConnected.name)) }
                            }
                            IconButton(onClick = { viewModel.showTrash(true) }) { Icon(LocusIcons.Trash, contentDescription = stringResource(R.string.workspace_files_trash_title)) }
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
            // Opened on a file from elsewhere: its viewer is on the way, not the folder list.
            state.opening != null -> LoadingState(Modifier.padding(padding))
            state.showTrash -> TrashList(state, viewModel::restore, viewModel::purge, Modifier.padding(padding))
            state.loading -> LoadingState(Modifier.padding(padding))
            state.folderError != null -> dev.locus.core.designsystem.ErrorState(
                title = stringResource(R.string.workspace_files_folder_error_title),
                message = state.folderError.orEmpty(),
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(padding),
                action = if (inConnected != null) ({ TextButton(onClick = { pickFolder(inConnected.id) }) { Text(stringResource(R.string.workspace_files_reconnect)) } }) else null,
            )
            state.entries.isEmpty() && inConnected != null -> EmptyState(
                title = stringResource(R.string.workspace_files_folder_empty_title),
                body = stringResource(R.string.workspace_files_connected_empty_body, inConnected.name),
                modifier = Modifier.padding(padding),
                icon = LocusIcons.Folder,
            )
            state.entries.isEmpty() && state.folder != "." -> EmptyState(
                title = stringResource(R.string.workspace_files_folder_empty_title),
                body = stringResource(R.string.workspace_files_empty_body),
                modifier = Modifier.padding(padding),
                icon = LocusIcons.Folder,
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.sm, bottom = Spacing.xxl),
            ) {
                // A connected folder says once, above its files, that Linux can't see it (docs/design/connected-folders.md).
                if (inConnected != null) {
                    item(key = "connected-linux-note") {
                        SectionNote(stringResource(R.string.workspace_files_connected_linux_note), Modifier.padding(bottom = Spacing.sm))
                    }
                }
                // The workspace's root has Linux's card first (docs/design/linux-setup.md), then the files.
                if (state.folder == ".") {
                    item(key = "linux") { LinuxCard(onOpenTerminal, Modifier.padding(bottom = Spacing.lg), onOpenHealth = onOpenHealth) }
                    connectedFolders(state.connected, onConnect = { pickFolder(null) }, onOpen = viewModel::openConnected, onManage = { viewModel.manage(it) })
                    item(key = "connected-gap") { androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.lg)) }
                    item(key = "files-label") { SectionLabel(stringResource(R.string.workspace_files_label), Modifier.padding(bottom = Spacing.sm)) }
                    if (state.entries.isEmpty()) {
                        item(key = "files-empty") {
                            EmptyState(
                                title = stringResource(R.string.workspace_files_workspace_empty_title),
                                body = stringResource(R.string.workspace_files_empty_body),
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
                                if (entry.isDirectory) modified else stringResource(R.string.workspace_files_row_meta, Formatter.formatShortFileSize(context, entry.size), modified),
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
        EmptyState(stringResource(R.string.workspace_trash_empty_title), stringResource(R.string.workspace_trash_empty_body), modifier, icon = LocusIcons.Restore)
        return
    }
    // The trash entry waiting for "Delete permanently" to be confirmed.
    var purging by remember { mutableStateOf<String?>(null) }
    state.trash.firstOrNull { it.id == purging }?.let { entry ->
        ConfirmSheet(
            title = stringResource(R.string.workspace_trash_purge_title),
            message = stringResource(R.string.workspace_trash_purge_message, entry.originalPath),
            confirmLabel = stringResource(R.string.workspace_files_delete),
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
                "OVERWRITTEN" -> stringResource(R.string.workspace_trash_reason_overwritten)
                "MOVED_OVER" -> stringResource(R.string.workspace_trash_reason_moved_over)
                "MOVED_AWAY" -> stringResource(R.string.workspace_trash_reason_moved_away)
                else -> stringResource(R.string.workspace_trash_reason_deleted)
            }
            ListRow(
                title = entry.originalPath,
                modifier = Modifier.cardSegment(i, count),
                subtitle = stringResource(R.string.workspace_trash_row_subtitle, what, relativeTime(entry.trashedAt)),
                // Paths and reasons get two lines; the actions are icons so the name keeps the row.
                titleMaxLines = 2,
                subtitleMaxLines = 2,
                leading = { FileTypeIcon(entry.originalPath, isDirectory = false) },
                trailing = {
                    CompactIconButton(LocusIcons.Restore, contentDescription = stringResource(R.string.workspace_trash_restore, entry.originalPath.substringAfterLast('/')), onClick = { onRestore(entry.id) }, tint = MaterialTheme.colorScheme.primary)
                    CompactIconButton(LocusIcons.Close, contentDescription = stringResource(R.string.workspace_trash_delete_permanently, entry.originalPath.substringAfterLast('/')), onClick = { purging = entry.id })
                },
            )
        }
    }
}
