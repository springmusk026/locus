package dev.locus.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.locus.core.data.folders.FolderStatus
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.CompactIconButton
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.CriticalOutlinedButton
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.cardSegment
import dev.locus.core.model.ConnectedFolder
import dev.locus.core.model.ConnectedFolders
import dev.locus.core.model.Project

/** What a connected folder's row says about it, and in which tone. */
@Composable
internal fun folderStatus(row: ConnectedFolderRow): Pair<String, Tone> = when (row.status) {
    FolderStatus.PERMISSION_LOST -> stringResource(R.string.workspace_connected_status_access_lost) to Tone.Critical
    FolderStatus.GONE -> stringResource(R.string.workspace_folder_not_found) to Tone.Critical
    FolderStatus.PROVIDER_READ_ONLY -> stringResource(R.string.workspace_connected_status_provider_read_only) to Tone.Caution
    null -> stringResource(R.string.workspace_connected_status_checking) to Tone.Neutral
    FolderStatus.OK -> if (row.folder.readOnly) {
        stringResource(R.string.workspace_connected_status_read_only) to Tone.Neutral
    } else {
        stringResource(R.string.workspace_connected_status_can_edit) to Tone.Live
    }
}

/**
 * Workspace → Connected folders, on the workspace's top level (docs/design/connected-folders.md): one
 * row per folder (tap to browse, the settings button for its sheet), and Connect a folder.
 */
internal fun LazyListScope.connectedFolders(
    rows: List<ConnectedFolderRow>,
    onConnect: () -> Unit,
    onOpen: (ConnectedFolder) -> Unit,
    onManage: (String) -> Unit,
) {
    item(key = "connected-label") { SectionLabel(stringResource(R.string.workspace_connected_label), Modifier.padding(bottom = Spacing.sm)) }
    if (rows.isEmpty()) {
        item(key = "connected-note") {
            SectionNote(
                stringResource(R.string.workspace_connected_note),
                Modifier.padding(bottom = Spacing.sm),
            )
        }
    }
    val count = rows.size + 1
    rows.forEachIndexed { i, row ->
        item(key = "connected-${row.folder.id}") {
            val (status, tone) = folderStatus(row)
            ListRow(
                title = row.folder.name,
                modifier = Modifier.cardSegment(i, count),
                subtitle = row.folder.source.ifEmpty { null },
                onClick = { onOpen(row.folder) },
                leading = { IconTile(LocusIcons.Folder) },
                meta = { StatusLine(status, tone) },
                trailing = {
                    CompactIconButton(LocusIcons.Settings, contentDescription = stringResource(R.string.workspace_files_folder_settings, row.folder.name), onClick = { onManage(row.folder.id) })
                },
            )
        }
    }
    item(key = "connected-add") {
        Box(Modifier.cardSegment(rows.size, count)) { AddRow(stringResource(R.string.workspace_connected_add), onClick = onConnect) }
    }
}

/** Names a folder just picked before it's connected. */
@Composable
internal fun ConnectFolderSheet(
    pending: PendingConnect,
    taken: List<String>,
    onConnect: (name: String, readOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(pending.picked.uri) { mutableStateOf(pending.suggestedName) }
    var canEdit by rememberSaveable(pending.picked.uri) { mutableStateOf(pending.picked.writable) }
    val problem = ConnectedFolders.nameProblem(name, taken)
    LocusSheet(
        title = stringResource(R.string.workspace_connect_title),
        subtitle = pending.picked.source,
        onDismiss = onDismiss,
        action = stringResource(R.string.workspace_connect_action),
        actionEnabled = problem == null,
        onAction = { onConnect(name, !canEdit) },
    ) {
        OutlinedTextField(
            name, { name = it.trim() },
            label = { Text(stringResource(R.string.workspace_connect_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = name.isNotEmpty() && problem != null,
            supportingText = { Text(if (name.isNotEmpty() && problem != null) problem else stringResource(R.string.workspace_connect_name_hint, name)) },
        )
        SwitchRow(
            title = stringResource(R.string.workspace_folder_can_edit),
            checked = canEdit,
            onCheckedChange = { canEdit = it },
            enabled = pending.picked.writable,
            subtitle = if (pending.picked.writable) {
                stringResource(R.string.workspace_connect_can_edit_summary)
            } else {
                stringResource(R.string.workspace_folder_provider_read_only)
            },
        )
        Banner(
            stringResource(R.string.workspace_connect_linux_banner),
            Tone.Info,
        )
    }
}

/** A connected folder's settings: editing, hidden folders, projects, reconnect and disconnect. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolderSheet(
    row: ConnectedFolderRow,
    projects: List<Project>,
    onSave: (ConnectedFolder) -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val folder = row.folder
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        ConfirmSheet(
            title = stringResource(R.string.workspace_folder_disconnect_title, folder.name),
            message = stringResource(R.string.workspace_folder_disconnect_message),
            confirmLabel = stringResource(R.string.workspace_folder_disconnect),
            tone = Tone.Critical,
            onConfirm = { confirming = false; onDisconnect() },
            onDismiss = { confirming = false },
        )
    }
    LocusSheet(title = folder.name, subtitle = folder.source.ifEmpty { null }, onDismiss = onDismiss) {
        SupportingText("folders/${folder.name}", style = LocusType.mono, maxLines = 1)
        when (row.status) {
            FolderStatus.PERMISSION_LOST, FolderStatus.GONE -> Banner(
                if (row.status == FolderStatus.GONE) {
                    stringResource(R.string.workspace_folder_gone_banner)
                } else {
                    stringResource(R.string.workspace_folder_access_lost_banner)
                },
                Tone.Critical,
                title = stringResource(if (row.status == FolderStatus.GONE) R.string.workspace_folder_not_found else R.string.workspace_folder_access_lost),
            ) { TextButton(onClick = onReconnect) { Text(stringResource(R.string.workspace_folder_reconnect)) } }
            FolderStatus.PROVIDER_READ_ONLY -> Banner(stringResource(R.string.workspace_folder_provider_read_only), Tone.Caution)
            else -> Unit
        }
        SwitchRow(
            title = stringResource(R.string.workspace_folder_can_edit),
            checked = !folder.readOnly,
            onCheckedChange = { onSave(folder.copy(readOnly = !it)) },
            enabled = row.status != FolderStatus.PROVIDER_READ_ONLY,
            subtitle = stringResource(R.string.workspace_folder_can_edit_summary),
        )
        SwitchRow(
            title = stringResource(R.string.workspace_folder_hidden_title),
            checked = folder.hiddenWritable,
            onCheckedChange = { onSave(folder.copy(hiddenWritable = it)) },
            enabled = !folder.readOnly,
            subtitle = stringResource(R.string.workspace_folder_hidden_summary),
        )
        SectionLabel(stringResource(R.string.workspace_folder_projects))
        SectionNote(
            if (projects.isEmpty()) {
                stringResource(R.string.workspace_folder_projects_none)
            } else {
                stringResource(R.string.workspace_folder_projects_some)
            },
        )
        if (projects.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                projects.forEach { p ->
                    val linked = p.id in folder.projectIds
                    LocusChip(
                        p.name,
                        variant = ChipVariant.Filter,
                        selected = linked,
                        onClick = { onSave(folder.copy(projectIds = if (linked) folder.projectIds - p.id else folder.projectIds + p.id)) },
                    )
                }
            }
        }
        if (row.status == FolderStatus.OK || row.status == FolderStatus.PROVIDER_READ_ONLY) {
            TextButton(onClick = onReconnect) { Text(stringResource(R.string.workspace_folder_pick_again)) }
        }
        CriticalOutlinedButton(stringResource(R.string.workspace_folder_disconnect), onClick = { confirming = true })
    }
}

/** The line under a connected folder's top bar: the path the agent uses for it. */
@Composable
internal fun ConnectedFolderStatus(path: String) {
    SupportingText(path, style = LocusType.mono, maxLines = 1)
}
