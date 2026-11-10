package dev.locus.feature.settings

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.model.SettingsDestination
import dev.locus.core.designsystem.SecretContent
import dev.locus.core.designsystem.SettingsLink
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.data.backup.BackupArchive
import dev.locus.core.data.backup.BackupManifest
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.SettingsActionFor
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.meterFraction
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.SwitchRow
import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.designsystem.asString

/** Settings → Backup & restore: an encrypted file the user saves wherever they like, and restoring from one. */
@Composable
internal fun BackupRoute(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { viewModel.backup(it) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.pickedRestoreFile(it) }
    var askPassword by remember { mutableStateOf(false) }

    BackupSettings(
        state = state,
        onBack = onBack,
        onIncludeWorkspace = viewModel::setIncludeWorkspace,
        onIncludeVault = viewModel::setIncludeVault,
        onBackUp = { askPassword = true },
        onCancelBackup = viewModel::cancelBackup,
        onDismissResult = viewModel::dismissResult,
        onRestore = { open.launch(arrayOf("*/*")) },
    )

    if (askPassword) {
        NewPasswordSheet(
            includesVault = state.includeVault,
            onDismiss = { askPassword = false },
            onDone = { password ->
                askPassword = false
                viewModel.setBackupPassword(password)
                save.launch("locus-${LocalDate.now()}.locusbackup")
            },
        )
    }
    RestoreSheet(
        step = state.restore,
        runActive = state.runActive,
        onUnlock = viewModel::unlock,
        onConfirm = viewModel::confirmRestore,
        onDismiss = viewModel::closeRestore,
        onChooseAnother = { viewModel.closeRestore(); open.launch(arrayOf("*/*")) },
    )
}

@Composable
internal fun BackupSettings(
    state: BackupUiState,
    onBack: () -> Unit,
    onIncludeWorkspace: (Boolean) -> Unit,
    onIncludeVault: (Boolean) -> Unit,
    onBackUp: () -> Unit,
    onCancelBackup: () -> Unit,
    onDismissResult: () -> Unit,
    onRestore: () -> Unit,
) {
    val estimate = state.estimate
    val busy = state.backupProgress != null
    SettingsPageScaffold(stringResource(R.string.settings_backup_title), onBack) {
        item { SectionLabel(stringResource(R.string.settings_backup_section), SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_backup_workspace_title),
                    checked = state.includeWorkspace,
                    onCheckedChange = onIncludeWorkspace,
                    subtitle = estimate?.let { stringResource(R.string.settings_backup_workspace_size, formatBytes(it.workspaceBytes)) }
                        ?: stringResource(if (state.estimating) R.string.settings_backup_workspace_estimating else R.string.settings_backup_workspace_subtitle),
                    icon = LocusIcons.Folder,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = stringResource(R.string.settings_backup_vault_title),
                    checked = state.includeVault,
                    onCheckedChange = onIncludeVault,
                    subtitle = stringResource(R.string.settings_backup_vault_subtitle),
                    icon = LocusIcons.Key,
                )
            }
        }
        if (state.includeVault) {
            item {
                Banner(
                    title = stringResource(R.string.settings_backup_keys_warning_title),
                    text = stringResource(R.string.settings_backup_keys_warning_text),
                    tone = Tone.Caution,
                    modifier = NotePadding,
                )
            }
        }
        item {
            CardGroup(NotePadding) {
                ListRow(
                    title = stringResource(R.string.settings_backup_now_title),
                    subtitle = estimate?.let { stringResource(R.string.settings_backup_now_size, formatBytes(it.baseBytes + if (state.includeWorkspace) it.workspaceBytes else 0)) }
                        ?: stringResource(if (state.estimating) R.string.settings_backup_now_estimating else R.string.settings_backup_now_subtitle),
                    subtitleMaxLines = 2,
                    // While a backup runs the row is shown as unavailable, not just silent.
                    onClick = onBackUp,
                    enabled = !busy,
                    leading = { IconTile(LocusIcons.Import) },
                    trailing = { RowChevron() },
                )
            }
        }
        state.backupProgress?.let { progress ->
            item { ProgressCard(progress, onCancel = onCancelBackup) }
        }
        state.result?.let { result ->
            item {
                val failed = result.failed
                Banner(
                    text = result.text.asString(),
                    tone = if (failed) Tone.Critical else Tone.Live,
                    modifier = NotePadding,
                    actionsBelow = failed,
                    actions = {
                        if (failed) {
                            // Below the text, so the row may take the full width.
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End)) {
                                TextButton(onClick = onDismissResult) { Text(stringResource(R.string.settings_backup_dismiss)) }
                                // "Free some space … Settings → … → Storage": the way to it.
                                SettingsActionFor(result.text.asString(), destination = result.link)
                                TextButton(onClick = { onDismissResult(); onBackUp() }) { Text(stringResource(R.string.settings_backup_try_again)) }
                            }
                        } else {
                            // Beside the text: the button alone, at its own width. A full-width row here left the
                            // message one letter wide.
                            TextButton(onClick = onDismissResult) { Text(stringResource(R.string.settings_backup_ok)) }
                        }
                    },
                )
            }
        }
        item {
            SectionNote(
                stringResource(R.string.settings_backup_note),
                NotePadding,
            )
        }

        item { SectionLabel(stringResource(R.string.settings_backup_restore_section), SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = stringResource(R.string.settings_backup_restore_title),
                    subtitle = stringResource(R.string.settings_backup_restore_subtitle),
                    subtitleMaxLines = 2,
                    onClick = onRestore,
                    enabled = !busy,
                    leading = { IconTile(LocusIcons.Restore) },
                    trailing = { RowChevron() },
                )
            }
        }
        item { SectionNote(stringResource(R.string.settings_backup_restore_note), NotePadding) }
    }
}

@Composable
private fun ProgressCard(progress: BackupProgress, onCancel: (() -> Unit)? = null, showLabel: Boolean = true) {
    LocusCard(NotePadding) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val amount = if (progress.total > 0) stringResource(R.string.settings_backup_progress_amount, formatBytes(progress.done), formatBytes(progress.total)) else null
            Text(
                when {
                    !showLabel -> amount ?: stringResource(R.string.settings_backup_progress_working)
                    amount != null -> stringResource(R.string.settings_backup_progress_label_amount, progress.label.asString(), amount)
                    else -> stringResource(R.string.settings_backup_progress_label, progress.label.asString())
                },
                style = LocusType.rowSupporting,
            )
            Meter(
                if (progress.total > 0) meterFraction(progress.done, progress.total) else null,
                tone = Tone.Live,
                contentDescription = progress.label.asString(),
            )
            if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_backup_cancel)) }
        }
    }
}

/** Choose and confirm the backup password. */
@Composable
private fun NewPasswordSheet(includesVault: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val problem = backupPasswordProblem(password, again)
    LocusSheet(
        title = stringResource(R.string.settings_backup_password_title),
        subtitle = stringResource(if (includesVault) R.string.settings_backup_password_subtitle_keys else R.string.settings_backup_password_subtitle),
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_backup_password_action),
        actionEnabled = problem == null,
        onAction = { onDone(password) },
    ) {
        SecretContent()
        OutlinedTextField(
            password, { password = it },
            label = { Text(stringResource(R.string.settings_backup_password_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource(R.string.settings_backup_password_hint, MIN_PASSWORD)) },
        )
        SecretContent()
        OutlinedTextField(
            again, { again = it },
            label = { Text(stringResource(R.string.settings_backup_password_again)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            isError = again.isNotEmpty() && again != password,
            supportingText = if (again.isNotEmpty() && again != password) ({ Text(stringResource(R.string.settings_backup_password_mismatch)) }) else null,
        )
        Banner(text = stringResource(R.string.settings_backup_password_forgotten), tone = Tone.Caution)
    }
}

internal const val MIN_PASSWORD = 8

/** Why the password can't be used yet, or null when it can. */
internal fun backupPasswordProblem(password: String, again: String): PasswordProblem? = when {
    password.length < MIN_PASSWORD -> PasswordProblem.TooShort
    password.isBlank() -> PasswordProblem.Blank
    again != password -> PasswordProblem.Mismatch
    else -> null
}

/** What keeps a backup password from being used; the sheet turns Continue off (the fields say why). */
internal enum class PasswordProblem { TooShort, Blank, Mismatch }

/** The restore flow in one sheet: password, checking, preview and confirm. */
@Composable
private fun RestoreSheet(
    step: RestoreStep,
    runActive: Boolean,
    onUnlock: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Closes this sheet and opens the file picker again, for a file that isn't a backup. */
    onChooseAnother: () -> Unit = {},
) {
    when (step) {
        RestoreStep.Idle -> Unit
        is RestoreStep.Password -> {
            var password by remember(step.uri) { mutableStateOf("") }
            val notABackup = step.unreadable
            // A file that isn't a backup has no password to ask for: say so, and offer another file.
            LocusSheet(
                title = stringResource(if (notABackup) R.string.settings_backup_not_a_backup else R.string.settings_backup_open_title),
                subtitle = if (notABackup) null else stringResource(R.string.settings_backup_open_subtitle),
                onDismiss = onDismiss,
                action = stringResource(if (notABackup) R.string.settings_backup_choose_another else R.string.settings_backup_check),
                actionEnabled = notABackup || password.isNotEmpty(),
                onAction = { if (notABackup) onChooseAnother() else onUnlock(password) },
            ) {
                if (step.noSpace != null) NoSpaceBanner(step.noSpace)
                else if (step.error != null) Banner(text = step.error.asString(), tone = Tone.Critical)
                if (!notABackup) {
                    SecretContent()
                    OutlinedTextField(
                        password, { password = it },
                        label = { Text(stringResource(R.string.settings_backup_open_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SupportingText(
                        stringResource(R.string.settings_backup_open_note),
                    )
                }
            }
        }
        is RestoreStep.Verifying -> LocusSheet(title = stringResource(R.string.settings_backup_verifying_title), subtitle = stringResource(R.string.settings_backup_verifying_subtitle), onDismiss = onDismiss) {
            ProgressCard(step.progress, showLabel = false)
        }
        // What gets replaced comes first and the destructive action last, after the user has read it.
        is RestoreStep.Preview -> LocusSheet(
            title = stringResource(R.string.settings_backup_preview_title),
            onDismiss = onDismiss,
        ) {
            if (runActive) Banner(title = stringResource(R.string.settings_backup_run_active_title), text = stringResource(R.string.settings_backup_run_active_text), tone = Tone.Caution)
            if (step.noSpace != null) NoSpaceBanner(step.noSpace)
            else if (step.error != null) Banner(text = step.error.asString(), tone = Tone.Critical)
            Banner(
                text = restoreWarning(LocalResources.current, step.manifest),
                tone = Tone.Critical,
            )
            ManifestPreview(step.manifest)
            Button(
                onClick = onConfirm,
                // Off while a task runs or the phone hasn't room: the banners above say why.
                enabled = !runActive && step.noSpace == null,
                colors = ButtonDefaults.buttonColors(containerColor = Locus.colors.critical, contentColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget),
            ) { Text(stringResource(R.string.settings_backup_restore_confirm)) }
        }
        RestoreStep.Restoring -> LocusSheet(title = stringResource(R.string.settings_backup_restoring_title), subtitle = stringResource(R.string.settings_backup_restoring_subtitle), onDismiss = {}) {
            Meter(null, tone = Tone.Live, contentDescription = stringResource(R.string.settings_backup_restoring_title))
        }
    }
}

/** Not enough room to restore: how much it needs, and the Storage page that shows what Locus uses. */
@Composable
private fun NoSpaceBanner(need: SpaceNeeded) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Banner(text = noSpaceText(LocalResources.current, need), tone = Tone.Critical, title = stringResource(R.string.settings_backup_no_space_title))
        SettingsLink(SettingsDestination.Storage, hint = stringResource(R.string.settings_backup_no_space_hint))
    }
}

@Composable
private fun ManifestPreview(manifest: BackupManifest) {
    CardGroup {
        ListRow(
            title = stringResource(R.string.settings_backup_made, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(manifest.createdAt))),
            subtitle = stringResource(R.string.settings_backup_made_version, manifest.appVersion).let { version ->
                manifest.projects?.takeIf { it > 0 }
                    ?.let { n -> summaryJoin(version, pluralStringResource(R.plurals.settings_backup_projects, n, n)) }
                    ?: version
            },
            titleMaxLines = 2,
            subtitleMaxLines = 2,
            leading = { IconTile(LocusIcons.Clock) },
        )
        manifest.sections.forEach { section ->
            GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = sectionTitle(section.name),
                subtitle = sectionSummary(LocalResources.current, section.name, section.files, section.bytes),
                titleMaxLines = 2,
                leading = { IconTile(sectionIcon(section.name)) },
            )
        }
    }
}

@Composable
internal fun sectionTitle(name: String) = when (name) {
    BackupArchive.DATABASE -> stringResource(R.string.settings_backup_section_database)
    BackupArchive.SETTINGS -> stringResource(R.string.settings_backup_section_settings)
    BackupArchive.AGENT_HOME -> stringResource(R.string.settings_backup_section_agent_home)
    BackupArchive.WORKSPACE -> stringResource(R.string.settings_backup_section_workspace)
    BackupArchive.VAULT -> stringResource(R.string.settings_backup_section_vault)
    else -> name
}

internal fun sectionSummary(res: Resources, name: String, files: Int, bytes: Long): String {
    val size = formatBytes(bytes, res.configuration.locales[0])
    return when (name) {
        BackupArchive.VAULT -> res.getQuantityString(R.plurals.settings_backup_section_entries, files, files)
        BackupArchive.DATABASE, BackupArchive.SETTINGS -> size
        else -> summaryJoin(res, listOf(res.getQuantityString(R.plurals.settings_backup_section_files, files, files), size))
    }
}

private fun sectionIcon(name: String) = when (name) {
    BackupArchive.DATABASE -> LocusIcons.Chats
    BackupArchive.SETTINGS -> LocusIcons.Settings
    BackupArchive.AGENT_HOME -> LocusIcons.Library
    BackupArchive.WORKSPACE -> LocusIcons.Folder
    BackupArchive.VAULT -> LocusIcons.Key
    else -> LocusIcons.File
}

/** Says exactly what is replaced and what is kept. */
internal fun restoreWarning(res: Resources, manifest: BackupManifest): String = res.getString(
    R.string.settings_backup_restore_warning,
    res.getString(R.string.settings_backup_restore_warning_replaces),
    res.getString(if (manifest.includesWorkspace) R.string.settings_backup_restore_warning_workspace else R.string.settings_backup_restore_warning_workspace_kept),
    res.getString(if (manifest.includesVault) R.string.settings_backup_restore_warning_keys else R.string.settings_backup_restore_warning_keys_kept),
    res.getString(R.string.settings_backup_restore_warning_restarts),
)
