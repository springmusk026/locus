package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.data.remote.LinkPreview
import dev.locus.core.data.remote.RemoteActivity
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.pressable
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.model.SettingsDestination

/** Where a chat's project runs its commands, for the header chip (docs/design/remote-execution.md). */
data class ChatRemoteInfo(val projectId: String, val alias: String, val folder: String, val activity: RemoteActivity?)

/** "Running on build-server" under the chat's title, next to the project chip; live while it syncs or runs. */
@Composable
internal fun RemoteChip(info: ChatRemoteInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (info.activity) {
        RemoteActivity.RUNNING -> stringResource(R.string.chat_remote_chip_running, info.alias)
        RemoteActivity.SYNCING -> stringResource(R.string.chat_remote_chip_syncing, info.alias)
        null -> stringResource(R.string.chat_remote_chip_runs, info.alias)
    }
    Row(
        modifier.pressable(onClick).padding(top = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (info.activity != null) StatusDot(Tone.Live)
        else Icon(LocusIcons.Server, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        SupportingText(label, style = LocusType.chip, maxLines = 1)
    }
}

/** What running on the machine means for this chat; the setting itself is in the project's settings. */
@Composable
internal fun ChatRemoteSheet(info: ChatRemoteInfo, onDismiss: () -> Unit, onOpenProject: () -> Unit) {
    LocusSheet(
        title = stringResource(R.string.chat_remote_sheet_title, info.alias),
        subtitle = info.folder,
        onDismiss = onDismiss,
        action = stringResource(R.string.chat_remote_project_settings),
        onAction = { onDismiss(); onOpenProject() },
    ) {
        // The sheet gives the side edges, so the text lines up with the title.
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                stringResource(R.string.chat_remote_sheet_body, info.alias),
                style = LocusType.rowSupporting,
            )
            SupportingText(
                stringResource(R.string.chat_remote_sheet_note, info.alias),
            )
        }
    }
}

/** The step the "Where commands run" sheet is at. */
sealed interface RemoteSheetStep {
    data object Choose : RemoteSheetStep

    /** Picking the folder on [alias]; [home] is null while it's being looked up. */
    data class Folder(val hostId: String, val alias: String, val folder: String, val loading: Boolean, val error: String? = null) : RemoteSheetStep

    /** What linking would copy, before anything is copied. */
    data class Preview(val hostId: String, val preview: LinkPreview) : RemoteSheetStep

    data class Linking(val alias: String, val done: Int, val total: Int) : RemoteSheetStep

    /** Back to the phone (confirmation). */
    data class Unlink(val alias: String) : RemoteSheetStep
}

/** A machine the project could run on: only ones with a confirmed host key. */
data class RemoteHostRow(val id: String, val alias: String, val address: String)

/** The editor's row: where the project's commands run now. [busy] while the project is saved first (a new one). */
@Composable
internal fun RemoteBackendRow(alias: String?, folder: String?, onClick: () -> Unit, busy: Boolean = false) {
    CardGroup {
        ListRow(
            title = if (alias == null) stringResource(R.string.chat_remote_this_phone) else stringResource(R.string.chat_remote_on_alias, alias),
            subtitle = folder ?: stringResource(R.string.chat_remote_row_default),
            leading = { Icon(if (alias == null) LocusIcons.Phone else LocusIcons.Server, null) },
            trailing = { if (busy) InlineSpinner(contentDescription = stringResource(R.string.chat_remote_saving)) else RowChevron() },
            onClick = onClick,
            enabled = !busy,
        )
    }
}

/**
 * Choose where the project's commands run. Linking a machine is explicit and preview-first: pick it,
 * pick the folder there, see what would be copied (and what never is), then confirm.
 */
@Composable
internal fun RemoteBackendSheet(
    step: RemoteSheetStep,
    currentAlias: String?,
    hosts: List<RemoteHostRow>,
    onDismiss: () -> Unit,
    onChoosePhone: () -> Unit,
    onChooseHost: (RemoteHostRow) -> Unit,
    onFolder: (String) -> Unit,
    onPreview: () -> Unit,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
    onCancelLink: () -> Unit = {},
) {
    when (step) {
        RemoteSheetStep.Choose -> LocusSheet(title = stringResource(R.string.chat_remote_choose_title), subtitle = stringResource(R.string.chat_remote_choose_subtitle), onDismiss = onDismiss) {
            Column {
                CardGroup {
                    ListRow(
                        title = stringResource(R.string.chat_remote_this_phone),
                        subtitle = stringResource(R.string.chat_remote_nothing_leaves),
                        leading = { Icon(LocusIcons.Phone, null) },
                        trailing = { if (currentAlias == null) Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_remote_selected)) },
                        onClick = onChoosePhone,
                    )
                    hosts.forEach { h ->
                        GroupDivider()
                        ListRow(
                            title = stringResource(R.string.chat_remote_run_on, h.alias),
                            titleMaxLines = 2,
                            subtitle = h.address,
                            leading = { Icon(LocusIcons.Server, null) },
                            trailing = { if (currentAlias == h.alias) Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_remote_selected)) },
                            onClick = { onChooseHost(h) },
                        )
                    }
                    if (hosts.isEmpty()) {
                        // No machine yet: the way to add one, instead of a sentence about where that is. The sheet
                        // stays open, so the new machine is here on the way back.
                        val settings = Locus.settings
                        GroupDivider()
                        ListRow(
                            title = stringResource(R.string.chat_remote_add_ssh),
                            subtitle = stringResource(R.string.chat_remote_add_ssh_hint),
                            leading = { Icon(LocusIcons.Add, null) },
                            trailing = { RowChevron() },
                            onClick = { settings?.open(SettingsDestination.Ssh) },
                            enabled = settings != null,
                        )
                    }
                }
                if (hosts.isEmpty()) {
                    SectionNote(
                        stringResource(R.string.chat_remote_own_machine),
                        Modifier.padding(top = Spacing.sm),
                    )
                } else {
                    SettingsLink(
                        SettingsDestination.Ssh,
                        Modifier.padding(top = Spacing.sm),
                        hint = stringResource(R.string.chat_remote_own_machines_hint),
                    )
                }
            }
        }
        is RemoteSheetStep.Folder -> LocusSheet(
            title = stringResource(R.string.chat_remote_run_on, step.alias),
            subtitle = stringResource(R.string.chat_remote_folder_subtitle),
            onDismiss = onDismiss,
            action = stringResource(R.string.chat_remote_preview),
            actionEnabled = !step.loading && step.folder.isNotBlank(),
            onAction = onPreview,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Banner(
                    stringResource(R.string.chat_remote_leave_body, step.alias),
                    Tone.Caution,
                    title = stringResource(R.string.chat_remote_leave_title),
                )
                // What went wrong, then what to do about it.
                step.error?.let { error ->
                    val fix = if (error.contains("not writable", ignoreCase = true)) " " + stringResource(R.string.chat_remote_folder_fix, step.alias) else ""
                    Banner(error + fix, Tone.Critical)
                }
                if (step.loading) Meter(null, contentDescription = stringResource(R.string.chat_remote_checking))
                OutlinedTextField(
                    step.folder, onFolder, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.chat_remote_folder_label, step.alias)) }, singleLine = true, enabled = !step.loading,
                    supportingText = { Text(stringResource(R.string.chat_remote_folder_help)) },
                )
            }
        }
        is RemoteSheetStep.Preview -> {
            val p = step.preview
            LocusSheet(
                title = stringResource(R.string.chat_remote_copy_title, p.alias),
                subtitle = stringResource(if (p.folderExists) R.string.chat_remote_folder_exists else R.string.chat_remote_folder_new, p.folder),
                onDismiss = onDismiss,
                action = stringResource(R.string.chat_remote_link_copy, files(p.upload.size)),
                actionTone = Tone.Caution,
                onAction = onLink,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CardGroup {
                        PreviewRow(stringResource(R.string.chat_remote_copied_to, p.alias), stringResource(R.string.chat_remote_files_size, files(p.upload.size), sizeLabel(p.uploadBytes)), p.upload)
                        if (p.download.isNotEmpty()) {
                            GroupDivider()
                            PreviewRow(stringResource(R.string.chat_remote_copied_phone), stringResource(R.string.chat_remote_already_there, files(p.download.size)), p.download)
                        }
                        GroupDivider()
                        PreviewRow(stringResource(R.string.chat_remote_secrets), if (p.secrets.isEmpty()) stringResource(R.string.chat_remote_none_found) else files(p.secrets.size), p.secrets)
                        if (p.stays.isNotEmpty() || p.tooLarge.isNotEmpty()) {
                            GroupDivider()
                            PreviewRow(stringResource(R.string.chat_remote_stays), stringResource(R.string.chat_remote_stays_what), p.stays + p.tooLarge)
                        }
                    }
                    SectionNote(stringResource(R.string.chat_remote_after, p.alias), Modifier.padding(top = Spacing.xs))
                }
            }
        }
        // Cancel stops the copy: the project is linked only once the first copy finishes, so it stays on this
        // phone. What was already copied stays on the machine.
        is RemoteSheetStep.Linking -> LocusSheet(
            title = stringResource(R.string.chat_remote_copying, step.alias),
            subtitle = stringResource(R.string.chat_remote_progress, step.done, step.total),
            onDismiss = onCancelLink,
            action = stringResource(R.string.chat_remote_cancel),
            onAction = onCancelLink,
        ) {
            Meter(
                if (step.total == 0) 0f else step.done.toFloat() / step.total,
                Modifier.padding(vertical = Spacing.md),
                tone = Tone.Live,
                contentDescription = stringResource(R.string.chat_remote_files_copied),
            )
            SupportingText(stringResource(R.string.chat_remote_copying_note, step.alias))
        }
        is RemoteSheetStep.Unlink -> ConfirmSheet(
            title = stringResource(R.string.chat_remote_unlink_title),
            message = stringResource(R.string.chat_remote_unlink_body, step.alias),
            confirmLabel = stringResource(R.string.chat_remote_unlink_confirm),
            onConfirm = onUnlink,
            onDismiss = onDismiss,
        )
    }
}

/** One line of the preview, with up to five of its paths. */
@Composable
private fun PreviewRow(title: String, summary: String, paths: List<String>) {
    val shown = paths.take(5).joinToString("\n") + if (paths.size > 5) "\n" + stringResource(R.string.chat_remote_and_more, paths.size - 5) else ""
    ListRow(title = title, subtitle = if (paths.isEmpty()) summary else "$summary\n$shown", subtitleMaxLines = 7)
}

@Composable
private fun files(n: Int): String = pluralStringResource(R.plurals.chat_remote_files, n, n)

internal fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
}
