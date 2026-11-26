package dev.locus.feature.settings

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.model.SettingsDestination
import dev.locus.core.designsystem.PasswordField
import androidx.annotation.StringRes
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import dev.locus.core.designsystem.SettingsLink
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
internal fun BackupRoute(
    onBack: () -> Unit,
    onSuggestMemories: () -> Unit = {},
    viewModel: BackupViewModel = hiltViewModel(),
    imports: ImportChatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importState by imports.state.collectAsStateWithLifecycle()
    // Exports are zips, or the conversations JSON on its own; several at once for Claude's split download.
    val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { imports.picked(it) }
    val resources = LocalResources.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { viewModel.backup(it) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.pickedRestoreFile(it) }
    // Saved, like the sheet's fields: turning the phone mid-way keeps the password sheet open and filled.
    var askPassword by rememberSaveable { mutableStateOf(false) }

    BackupSettings(
        state = state,
        onBack = onBack,
        onIncludeWorkspace = viewModel::setIncludeWorkspace,
        onIncludeVault = viewModel::setIncludeVault,
        onBackUp = { askPassword = true },
        onCancelBackup = viewModel::cancelBackup,
        onDismissResult = viewModel::dismissResult,
        onRestore = { open.launch(arrayOf("*/*")) },
        importState = importState,
        onImportChats = { pickExport.launch(arrayOf("*/*")) },
        onDismissImport = imports::dismissOutcome,
        onSuggestMemories = onSuggestMemories,
    )
    ImportChatsSheet(
        step = importState.step,
        onChoose = imports::choose,
        onImport = { imports.import(importTexts(resources)) },
        onCancel = imports::cancel,
        onClose = imports::close,
        onChooseAnother = { imports.close(); pickExport.launch(arrayOf("*/*")) },
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
    /** Importing chats from ChatGPT or Claude (docs/design/import-chats.md). */
    importState: ImportChatsUiState = ImportChatsUiState(),
    onImportChats: () -> Unit = {},
    onDismissImport: () -> Unit = {},
    /** After an import: Library → Memory's "Suggest memories from imported chats". */
    onSuggestMemories: () -> Unit = {},
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

        importChatsSection(importState, enabled = !busy, onImport = onImportChats, onDismissOutcome = onDismissImport, onSuggestMemories = onSuggestMemories)
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

/**
 * Choose and confirm the backup password. Both fields survive turning the phone (rememberSaveable,
 * docs/design/design-system.md, "Secrets"); the strength meter and a too-short or too-easy error show
 * under the first field once something is typed in it and the person has moved on.
 */
@Composable
internal fun NewPasswordSheet(includesVault: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var again by rememberSaveable { mutableStateOf("") }
    // Whether the first field was left with something in it: its error waits until then, not the first key.
    var leftPassword by rememberSaveable { mutableStateOf(false) }
    val problem = backupPasswordProblem(password, again)
    val strength = passwordStrength(password)
    val firstFieldError = (leftPassword || again.isNotEmpty()) && password.isNotEmpty() &&
        problem in setOf(PasswordProblem.TooShort, PasswordProblem.Blank, PasswordProblem.TooWeak)
    LocusSheet(
        title = stringResource(R.string.settings_backup_password_title),
        subtitle = stringResource(if (includesVault) R.string.settings_backup_password_subtitle_keys else R.string.settings_backup_password_subtitle),
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_backup_password_action),
        actionEnabled = problem == null,
        onAction = { onDone(password) },
    ) {
        PasswordField(
            password, { password = it },
            label = stringResource(R.string.settings_backup_password_label),
            isError = firstFieldError,
            supportingText = when {
                !firstFieldError -> pluralStringResource(R.plurals.settings_backup_password_hint, MIN_PASSWORD, MIN_PASSWORD)
                problem == PasswordProblem.TooWeak -> stringResource(R.string.settings_backup_password_weak)
                else -> stringResource(R.string.settings_backup_password_too_short, MIN_PASSWORD)
            },
            modifier = Modifier.onFocusChanged { if (!it.isFocused && password.isNotEmpty()) leftPassword = true },
        )
        if (password.isNotEmpty()) PasswordStrengthMeter(strength)
        PasswordField(
            again, { again = it },
            label = stringResource(R.string.settings_backup_password_again),
            isError = again.isNotEmpty() && again != password,
            supportingText = if (again.isNotEmpty() && again != password) stringResource(R.string.settings_backup_password_mismatch) else null,
            imeAction = ImeAction.Done,
        )
        Banner(text = stringResource(R.string.settings_backup_password_forgotten), tone = Tone.Caution)
    }
}

/** How hard the password looks to guess: a meter in the strength's tone and a line that says it. */
@Composable
private fun PasswordStrengthMeter(strength: PasswordStrength) {
    val text = stringResource(strength.label)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Meter(strength.fraction, tone = strength.tone, contentDescription = text)
        SupportingText(text, style = LocusType.rowSupporting)
    }
}

internal const val MIN_PASSWORD = 8

/** Why the password can't be used yet, or null when it can. */
internal fun backupPasswordProblem(password: String, again: String): PasswordProblem? = when {
    password.length < MIN_PASSWORD -> PasswordProblem.TooShort
    password.isBlank() -> PasswordProblem.Blank
    passwordStrength(password) == PasswordStrength.Weak -> PasswordProblem.TooWeak
    again != password -> PasswordProblem.Mismatch
    else -> null
}

/** What keeps a backup password from being used; the sheet turns Continue off (the fields say why). */
internal enum class PasswordProblem { TooShort, Blank, TooWeak, Mismatch }

/**
 * A rough strength for the backup password, which is all that protects the file wherever it's saved.
 * It isn't an estimate of guesses: it turns away the passwords anyone would try first and says when a
 * password is long or varied enough.
 *
 * - **Weak** (refused): shorter than [MIN_PASSWORD]; fewer than five different characters
 *   ("aaaaaaaa", "abababab"); a run up or down the alphabet or digits ("12345678", "abcdefgh"); or one of
 *   the most common passwords, with digits on the end ("password1").
 * - **Strong**: 16 characters or more, or 12 or more mixing three kinds (lower case, upper case, digits, symbols).
 * - **Fair**: anything in between.
 */
internal enum class PasswordStrength(@StringRes val label: Int, val tone: Tone, val fraction: Float) {
    Weak(R.string.settings_backup_strength_weak, Tone.Critical, 1f / 3),
    Fair(R.string.settings_backup_strength_fair, Tone.Caution, 2f / 3),
    Strong(R.string.settings_backup_strength_strong, Tone.Live, 1f),
}

internal fun passwordStrength(password: String): PasswordStrength {
    if (password.length < MIN_PASSWORD || password.toSet().size < 5) return PasswordStrength.Weak
    val steps = password.zipWithNext { a, b -> b - a }.toSet()
    if (steps.size == 1 && steps.single() in -1..1) return PasswordStrength.Weak
    if (password.lowercase().trimEnd(Char::isDigit) in COMMON_PASSWORDS) return PasswordStrength.Weak
    val kinds = listOf(Char::isLowerCase, Char::isUpperCase, Char::isDigit).count { kind -> password.any(kind) } +
        (if (password.any { !it.isLetterOrDigit() }) 1 else 0)
    return when {
        password.length >= 16 || (password.length >= 12 && kinds >= 3) -> PasswordStrength.Strong
        else -> PasswordStrength.Fair
    }
}

/** The first passwords anyone tries, lower case and without trailing digits (top of public breach lists). */
private val COMMON_PASSWORDS = setOf(
    "password", "passw0rd", "qwerty", "qwertyuiop", "qwertyui", "asdfghjk", "iloveyou", "letmein", "welcome",
    "admin", "abc", "football", "baseball", "dragon", "monkey", "sunshine", "princess", "trustno1", "locus", "backup",
)

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
            var password by rememberSaveable(step.uri) { mutableStateOf("") }
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
                    PasswordField(
                        password, { password = it },
                        label = stringResource(R.string.settings_backup_open_password),
                        imeAction = ImeAction.Done,
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
