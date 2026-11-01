package dev.locus.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.RowChevron

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
                    subtitle = estimate?.let { "${formatBytes(it.workspaceBytes)} · the files the agent works on" } ?: "The files the agent works on",
                    icon = LocusIcons.Folder,
                    checked = state.includeWorkspace,
                    onChange = onIncludeWorkspace,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = "Include keys and passwords",
                    subtitle = "API keys, sign-ins, SSH and connector secrets. Off: you enter them again after restoring.",
                    icon = LocusIcons.Key,
                    checked = state.includeVault,
                    onChange = onIncludeVault,
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
                        ?: "Chats, settings, memory, skills and helpers",
                    onClick = if (busy) null else onBackUp,
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
                    actions = { TextButton(onClick = onDismissResult) { Text("OK") } },
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
                    onClick = if (busy) null else onRestore,
                    leading = { IconTile(LocusIcons.Restore) },
                    trailing = { RowChevron() },
                )
            }
        }
        item { SectionNote("You'll see what's in the backup before anything changes. Locus restarts to finish.", NotePadding) }
    }
}

@Composable
private fun ProgressCard(progress: BackupProgress, onCancel: (() -> Unit)? = null) {
    LocusCard(NotePadding) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                if (progress.total > 0) "${progress.label} · ${formatBytes(progress.done)} of ${formatBytes(progress.total)}" else "${progress.label}…",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (progress.total > 0) {
                LinearProgressIndicator(progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
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
        Banner(text = "If you forget this password, the backup can't be opened. Nobody can recover it.", tone = Tone.Neutral)
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
) {
    when (step) {
        RestoreStep.Idle -> Unit
        is RestoreStep.Password -> {
            var password by remember(step.uri) { mutableStateOf("") }
            val notABackup = step.unreadable
            LocusSheet(
                title = "Open backup",
                subtitle = "Enter the password it was saved with.",
                onDismiss = onDismiss,
                action = "Check",
                actionEnabled = password.isNotEmpty() && !notABackup,
                onAction = { onUnlock(password) },
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
                    Text(
                        "Nothing on this phone changes until the whole file has been checked and you confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is RestoreStep.Verifying -> LocusSheet(title = "Checking the backup", subtitle = "Decrypting and verifying every part.", onDismiss = onDismiss) {
            ProgressCard(step.progress)
        }
        is RestoreStep.Preview -> LocusSheet(
            title = "Restore this backup?",
            onDismiss = onDismiss,
            action = "Restore and restart",
            actionEnabled = !runActive,
            actionTone = Tone.Critical,
            onAction = onConfirm,
        ) {
            if (runActive) Banner(title = "A task is running", text = "Stop it first, then restore.", tone = Tone.Caution)
            if (step.error != null) Banner(text = step.error, tone = Tone.Critical)
            ManifestPreview(step.manifest)
            Banner(
                text = restoreWarning(step.manifest),
                tone = Tone.Critical,
            )
        }
        RestoreStep.Restoring -> LocusSheet(title = "Restoring", subtitle = "Locus restarts in a moment.", onDismiss = {}) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ManifestPreview(manifest: BackupManifest) {
    CardGroup {
        ListRow(
            title = "Made ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(manifest.createdAt))}",
            subtitle = "Locus ${manifest.appVersion} · database v${manifest.schemaVersion}" +
                (manifest.projects?.takeIf { it > 0 }?.let { n -> " · ${if (n == 1) "1 project" else "$n projects"}" } ?: ""),
            leading = { IconTile(LocusIcons.Clock) },
        )
        manifest.sections.forEach { section ->
            GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = sectionTitle(section.name),
                subtitle = sectionSummary(section.name, section.files, section.bytes),
                leading = { IconTile(sectionIcon(section.name)) },
            )
        }
    }
}

internal fun sectionTitle(name: String) = when (name) {
    BackupArchive.DATABASE -> "Chats and activity"
    BackupArchive.SETTINGS -> "Settings"
    BackupArchive.AGENT_HOME -> "Memory, skills, helpers and persona"
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
