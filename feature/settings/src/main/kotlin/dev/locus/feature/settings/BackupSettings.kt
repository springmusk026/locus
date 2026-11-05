package dev.locus.feature.settings

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
    SettingsPageScaffold("Backup & restore", onBack) {
        item { SectionLabel("Back up", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = "Include workspace files",
                    checked = state.includeWorkspace,
                    onCheckedChange = onIncludeWorkspace,
                    subtitle = estimate?.let { "${formatBytes(it.workspaceBytes)} · the files the agent works on" } ?: "The files the agent works on",
                    icon = LocusIcons.Folder,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = "Include keys and passwords",
                    checked = state.includeVault,
                    onCheckedChange = onIncludeVault,
                    subtitle = "API keys, sign-ins, SSH and connector secrets. Off: you enter them again after restoring.",
                    icon = LocusIcons.Key,
                )
            }
        }
        if (state.includeVault) {
            item {
                Banner(
                    title = "Keys go in the file",
                    text = "They're encrypted with your backup password, but anyone who has the file and the password can use your accounts. Keep the password somewhere safe, or leave this off.",
                    tone = Tone.Caution,
                    modifier = NotePadding,
                )
            }
        }
        item {
            CardGroup(NotePadding) {
                ListRow(
                    title = "Back up now",
                    subtitle = estimate?.let { "About ${formatBytes(it.baseBytes + if (state.includeWorkspace) it.workspaceBytes else 0)} before compression" }
                        ?: "Chats, settings, memory, skills, workflows and helpers",
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
        state.result?.let { (message, failed) ->
            item {
                Banner(
                    text = message,
                    tone = if (failed) Tone.Critical else Tone.Live,
                    modifier = NotePadding,
                    actionsBelow = failed,
                    actions = {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End)) {
                            TextButton(onClick = onDismissResult) { Text(if (failed) "Dismiss" else "OK") }
                            if (failed) {
                                // "Free some space … Settings → … → Storage": the way to it.
                                SettingsActionFor(message)
                                TextButton(onClick = { onDismissResult(); onBackUp() }) { Text("Try again") }
                            }
                        }
                    },
                )
            }
        }
        item {
            SectionNote(
                "The file is encrypted with your password (AES-256). Locus has no server and can't open a backup without the password, or recover a forgotten one.",
                NotePadding,
            )
        }

        item { SectionLabel("Restore", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Restore from a backup",
                    subtitle = "Replaces chats, settings, memory and skills on this phone",
                    subtitleMaxLines = 2,
                    onClick = onRestore,
                    enabled = !busy,
                    leading = { IconTile(LocusIcons.Restore) },
                    trailing = { RowChevron() },
                )
            }
        }
        item { SectionNote("You'll see what's in the backup before anything changes. Locus restarts to finish.", NotePadding) }
    }
}

@Composable
private fun ProgressCard(progress: BackupProgress, onCancel: (() -> Unit)? = null, showLabel: Boolean = true) {
    LocusCard(NotePadding) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val amount = if (progress.total > 0) "${formatBytes(progress.done)} of ${formatBytes(progress.total)}" else null
            Text(
                when {
                    !showLabel -> amount ?: "Working…"
                    amount != null -> "${progress.label} · $amount"
                    else -> "${progress.label}…"
                },
                style = LocusType.rowSupporting,
            )
            Meter(
                if (progress.total > 0) meterFraction(progress.done, progress.total) else null,
                tone = Tone.Live,
                contentDescription = progress.label,
            )
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
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
        title = "Backup password",
        subtitle = if (includesVault) "Protects the file, including your keys." else "Protects the file.",
        onDismiss = onDismiss,
        action = "Choose where to save",
        actionEnabled = problem == null,
        onAction = { onDone(password) },
    ) {
        OutlinedTextField(
            password, { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("At least $MIN_PASSWORD characters. A few random words are easy to type and hard to guess.") },
        )
        OutlinedTextField(
            again, { again = it },
            label = { Text("Password again") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            isError = again.isNotEmpty() && again != password,
            supportingText = if (again.isNotEmpty() && again != password) ({ Text("The passwords don't match.") }) else null,
        )
        Banner(text = "If you forget this password, the backup can't be opened. Nobody can recover it.", tone = Tone.Caution)
    }
}

internal const val MIN_PASSWORD = 8

/** Why the password can't be used yet, or null when it can. */
internal fun backupPasswordProblem(password: String, again: String): String? = when {
    password.length < MIN_PASSWORD -> "Too short"
    password.isBlank() -> "Blank"
    again != password -> "Doesn't match"
    else -> null
}

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
                title = if (notABackup) "Not a backup" else "Open backup",
                subtitle = if (notABackup) null else "Enter the password it was saved with.",
                onDismiss = onDismiss,
                action = if (notABackup) "Choose another file" else "Check",
                actionEnabled = notABackup || password.isNotEmpty(),
                onAction = { if (notABackup) onChooseAnother() else onUnlock(password) },
            ) {
                if (step.error != null) Banner(text = step.error, tone = Tone.Critical)
                if (!notABackup) {
                    OutlinedTextField(
                        password, { password = it },
                        label = { Text("Backup password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SupportingText(
                        "Nothing on this phone changes until the whole file has been checked and you confirm.",
                    )
                }
            }
        }
        is RestoreStep.Verifying -> LocusSheet(title = "Checking the backup", subtitle = "Decrypting and verifying every part.", onDismiss = onDismiss) {
            ProgressCard(step.progress, showLabel = false)
        }
        // What gets replaced comes first and the destructive action last, after the user has read it.
        is RestoreStep.Preview -> LocusSheet(
            title = "Restore this backup?",
            onDismiss = onDismiss,
        ) {
            if (runActive) Banner(title = "A task is running", text = "Stop it first, then restore.", tone = Tone.Caution)
            if (step.error != null) Banner(text = step.error, tone = Tone.Critical)
            Banner(
                text = restoreWarning(step.manifest),
                tone = Tone.Critical,
            )
            ManifestPreview(step.manifest)
            Button(
                onClick = onConfirm,
                enabled = !runActive,
                colors = ButtonDefaults.buttonColors(containerColor = Locus.colors.critical, contentColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget),
            ) { Text("Restore and restart") }
        }
        RestoreStep.Restoring -> LocusSheet(title = "Restoring", subtitle = "Locus restarts in a moment.", onDismiss = {}) {
            Meter(null, tone = Tone.Live, contentDescription = "Restoring")
        }
    }
}

@Composable
private fun ManifestPreview(manifest: BackupManifest) {
    CardGroup {
        ListRow(
            title = "Made ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(manifest.createdAt))}",
            subtitle = "Locus ${manifest.appVersion}" +
                (manifest.projects?.takeIf { it > 0 }?.let { n -> " · ${if (n == 1) "1 project" else "$n projects"}" } ?: ""),
            titleMaxLines = 2,
            subtitleMaxLines = 2,
            leading = { IconTile(LocusIcons.Clock) },
        )
        manifest.sections.forEach { section ->
            GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = sectionTitle(section.name),
                subtitle = sectionSummary(section.name, section.files, section.bytes),
                titleMaxLines = 2,
                leading = { IconTile(sectionIcon(section.name)) },
            )
        }
    }
}

internal fun sectionTitle(name: String) = when (name) {
    BackupArchive.DATABASE -> "Chats and activity"
    BackupArchive.SETTINGS -> "Settings"
    BackupArchive.AGENT_HOME -> "Memory, skills, workflows, helpers and persona"
    BackupArchive.WORKSPACE -> "Workspace files"
    BackupArchive.VAULT -> "Keys and passwords"
    else -> name
}

internal fun sectionSummary(name: String, files: Int, bytes: Long) = when (name) {
    BackupArchive.VAULT -> if (files == 1) "1 entry" else "$files entries"
    BackupArchive.DATABASE, BackupArchive.SETTINGS -> formatBytes(bytes)
    else -> "${if (files == 1) "1 file" else "$files files"} · ${formatBytes(bytes)}"
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
internal fun restoreWarning(manifest: BackupManifest): String = buildString {
    append("This replaces the chats, activity, settings, memory, skills and helpers on this phone with the backup's.")
    append(if (manifest.includesWorkspace) " The workspace is replaced too." else " Your workspace files stay as they are.")
    append(if (manifest.includesVault) " Saved keys are replaced with the backup's." else " Keys saved on this phone are kept; add any that are missing.")
    append(" Locus then restarts.")
}
