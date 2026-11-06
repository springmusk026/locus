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
internal fun folderStatus(row: ConnectedFolderRow): Pair<String, Tone> = when (row.status) {
    FolderStatus.PERMISSION_LOST -> "Access lost · reconnect it" to Tone.Critical
    FolderStatus.GONE -> "Folder not found" to Tone.Critical
    FolderStatus.PROVIDER_READ_ONLY -> "Read-only (storage app)" to Tone.Caution
    null -> "Checking…" to Tone.Neutral
    FolderStatus.OK -> if (row.folder.readOnly) "Read-only" to Tone.Neutral else "Can edit · asks first" to Tone.Live
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
    item(key = "connected-label") { SectionLabel("Connected folders", Modifier.padding(bottom = Spacing.sm)) }
    if (rows.isEmpty()) {
        item(key = "connected-note") {
            SectionNote(
                "Let Locus read and edit a folder on your phone, like a notes vault or a synced folder. Linux can't see connected folders.",
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
                    CompactIconButton(LocusIcons.Settings, contentDescription = "${row.folder.name} settings", onClick = { onManage(row.folder.id) })
                },
            )
        }
    }
    item(key = "connected-add") {
        Box(Modifier.cardSegment(rows.size, count)) { AddRow("Connect a folder", onClick = onConnect) }
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
        title = "Connect folder",
        subtitle = pending.picked.source,
        onDismiss = onDismiss,
        action = "Connect",
        actionEnabled = problem == null,
        onAction = { onConnect(name, !canEdit) },
    ) {
        OutlinedTextField(
            name, { name = it.trim() },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = name.isNotEmpty() && problem != null,
            supportingText = { Text(if (name.isNotEmpty() && problem != null) problem else "Locus finds it at folders/$name.") },
        )
        SwitchRow(
            title = "Locus can edit files",
            checked = canEdit,
            onCheckedChange = { canEdit = it },
            enabled = pending.picked.writable,
            subtitle = if (pending.picked.writable) "Every change asks you first; deleting always asks. Hidden folders like .obsidian stay read-only."
            else "The storage app holding this folder doesn't let other apps change it, so Locus can only read it.",
        )
        Banner(
            "Linux can't see connected folders: Android's storage access isn't a folder Linux can open. Locus can copy files into the workspace when a command needs them.",
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
            title = "Disconnect ${folder.name}?",
            message = "Locus loses access to this folder and forgets “Always allow” answers for it. The files stay where they are, and copies in Locus's trash stay there.",
            confirmLabel = "Disconnect",
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
                    "The folder was deleted or moved, or the SD card it was on was removed. Pick it again to reconnect it."
                } else {
                    "Android no longer lets Locus open this folder (the permission was removed, or the folder moved). Pick it again to reconnect it."
                },
                Tone.Critical,
                title = if (row.status == FolderStatus.GONE) "Folder not found" else "Access lost",
            ) { TextButton(onClick = onReconnect) { Text("Reconnect") } }
            FolderStatus.PROVIDER_READ_ONLY -> Banner("The storage app holding this folder doesn't let other apps change it, so Locus can only read it.", Tone.Caution)
            else -> Unit
        }
        SwitchRow(
            title = "Locus can edit files",
            checked = !folder.readOnly,
            onCheckedChange = { onSave(folder.copy(readOnly = !it)) },
            enabled = row.status != FolderStatus.PROVIDER_READ_ONLY,
            subtitle = "Every change asks you first, with “Always for this folder”. Deleting always asks.",
        )
        SwitchRow(
            title = "Edit hidden folders",
            checked = folder.hiddenWritable,
            onCheckedChange = { onSave(folder.copy(hiddenWritable = it)) },
            enabled = !folder.readOnly,
            subtitle = "Apps keep settings and history in .obsidian, .git and .trash. Off keeps them read-only.",
        )
        SectionLabel("Projects")
        SectionNote(
            if (projects.isEmpty()) "Chats outside projects see every connected folder. Once you have projects, you can link this folder to them here."
            else "Chats outside projects see every connected folder. A project's chats see only the folders linked to it.",
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
            TextButton(onClick = onReconnect) { Text("Pick the folder again") }
        }
        CriticalOutlinedButton("Disconnect", onClick = { confirming = true })
    }
}

/** The line under a connected folder's top bar: the path the agent uses for it. */
@Composable
internal fun ConnectedFolderStatus(path: String) {
    SupportingText(path, style = LocusType.mono, maxLines = 1)
}
