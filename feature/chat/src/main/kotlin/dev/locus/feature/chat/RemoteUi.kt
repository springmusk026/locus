package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.locus.core.data.remote.LinkPreview
import dev.locus.core.data.remote.RemoteActivity
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.pressable

/** Where a chat's project runs its commands, for the header chip (docs/design/remote-execution.md). */
data class ChatRemoteInfo(val projectId: String, val alias: String, val folder: String, val activity: RemoteActivity?)

/** "Running on build-server" under the chat's title, next to the project chip; live while it syncs or runs. */
@Composable
internal fun RemoteChip(info: ChatRemoteInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (info.activity) {
        RemoteActivity.RUNNING -> "Running on ${info.alias}"
        RemoteActivity.SYNCING -> "Syncing with ${info.alias}"
        null -> "Runs on ${info.alias}"
    }
    Row(
        modifier.pressable(onClick).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (info.activity != null) StatusDot(Tone.Live, size = 5.dp)
        else Icon(LocusIcons.Server, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** What running on the machine means for this chat; the setting itself is in the project's settings. */
@Composable
internal fun ChatRemoteSheet(info: ChatRemoteInfo, onDismiss: () -> Unit, onOpenProject: () -> Unit) {
    LocusSheet(
        title = "Commands run on ${info.alias}",
        subtitle = info.folder,
        onDismiss = onDismiss,
        action = "Project settings",
        onAction = { onDismiss(); onOpenProject() },
    ) {
        Column(Modifier.padding(horizontal = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                "This project's commands, services and test runs go to ${info.alias}. Before each one, files you or the agent changed here are " +
                    "copied there; afterwards, what the command changed comes back and shows on the turn's changes card.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Approvals stay on this phone. Long builds keep running on ${info.alias} while the phone sleeps. Secrets (.env files, keys, " +
                    "files holding your saved API keys) are never copied.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** The editor's row: where the project's commands run now. */
@Composable
internal fun RemoteBackendRow(alias: String?, folder: String?, onClick: () -> Unit) {
    CardGroup {
        ListRow(
            title = if (alias == null) "This phone (Linux)" else "On $alias",
            subtitle = folder ?: "Commands, services and tests run in Linux on the phone",
            leading = { Icon(if (alias == null) LocusIcons.Phone else LocusIcons.Server, null) },
            onClick = onClick,
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
) {
    when (step) {
        RemoteSheetStep.Choose -> LocusSheet(title = "Where commands run", subtitle = "For this project's chats: run_command, services and test runs.", onDismiss = onDismiss) {
            Column(Modifier.padding(horizontal = Spacing.lg)) {
                CardGroup {
                    ListRow(
                        title = "This phone (Linux)",
                        subtitle = "Nothing leaves the phone",
                        leading = { Icon(LocusIcons.Phone, null) },
                        trailing = { if (currentAlias == null) Icon(LocusIcons.Check, null) },
                        onClick = onChoosePhone,
                    )
                    hosts.forEach { h ->
                        GroupDivider()
                        ListRow(
                            title = "Run on ${h.alias}",
                            subtitle = h.address,
                            leading = { Icon(LocusIcons.Server, null) },
                            trailing = { if (currentAlias == h.alias) Icon(LocusIcons.Check, null) },
                            onClick = { onChooseHost(h) },
                        )
                    }
                }
                SectionNote(
                    if (hosts.isEmpty()) "Add one of your machines in Settings → SSH machines and confirm its host key to run builds there."
                    else "Your own machines from Settings → SSH machines, with a confirmed host key. There's no Locus server in between.",
                    Modifier.padding(top = Spacing.sm),
                )
            }
        }
        is RemoteSheetStep.Folder -> LocusSheet(
            title = "Run on ${step.alias}",
            subtitle = "Choose the folder this project is copied to.",
            onDismiss = onDismiss,
            action = "Preview what's copied",
            actionEnabled = !step.loading && step.folder.isNotBlank(),
            onAction = onPreview,
        ) {
            Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Banner(
                    "The project's files are copied to ${step.alias} and its commands run there, so they leave the phone. Secrets never do: .env " +
                        "files, keys, credentials and any file holding an API key you saved in Locus. Approvals stay on this phone.",
                    Tone.Caution,
                    title = "Files leave the phone",
                )
                step.error?.let { Banner(it, Tone.Critical) }
                if (step.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(
                    step.folder, onFolder, Modifier.fillMaxWidth(),
                    label = { Text("Folder on ${step.alias}") }, singleLine = true, enabled = !step.loading,
                    supportingText = { Text("Absolute or under ~, e.g. ~/locus/app. Locus only deletes files there that it copied.") },
                )
            }
        }
        is RemoteSheetStep.Preview -> {
            val p = step.preview
            LocusSheet(
                title = "Copy to ${p.alias}?",
                subtitle = p.folder + if (p.folderExists) " (exists)" else " (will be made)",
                onDismiss = onDismiss,
                action = "Link and copy ${files(p.upload.size)}",
                actionTone = Tone.Caution,
                onAction = onLink,
            ) {
                Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CardGroup {
                        PreviewRow("Copied to ${p.alias}", "${files(p.upload.size)}, ${sizeLabel(p.uploadBytes)}", p.upload)
                        if (p.download.isNotEmpty()) {
                            GroupDivider()
                            PreviewRow("Copied to the phone", "${files(p.download.size)} already in that folder", p.download)
                        }
                        GroupDivider()
                        PreviewRow("Never copied: secrets", if (p.secrets.isEmpty()) "None found" else files(p.secrets.size), p.secrets)
                        if (p.stays.isNotEmpty() || p.tooLarge.isNotEmpty()) {
                            GroupDivider()
                            PreviewRow("Stay where they are", "git, dependencies, builds, .locusignore, files over 100 MB", p.stays + p.tooLarge)
                        }
                    }
                    SectionNote("After this, files sync before and after each command. Build outputs stay on ${p.alias}.", Modifier.padding(top = Spacing.xs))
                }
            }
        }
        is RemoteSheetStep.Linking -> LocusSheet(title = "Copying to ${step.alias}", subtitle = "${step.done} of ${step.total} files", onDismiss = {}) {
            LinearProgressIndicator(
                progress = { if (step.total == 0) 0f else step.done.toFloat() / step.total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.md),
            )
        }
        is RemoteSheetStep.Unlink -> LocusSheet(
            title = "Run on this phone again?",
            subtitle = "New commands run in Linux on the phone. The copy on ${step.alias} stays there; Locus doesn't delete it.",
            onDismiss = onDismiss,
            action = "Run on this phone",
            onAction = onUnlink,
        ) {}
    }
}

/** One line of the preview, with up to five of its paths. */
@Composable
private fun PreviewRow(title: String, summary: String, paths: List<String>) {
    val shown = paths.take(5).joinToString("\n") + if (paths.size > 5) "\n…and ${paths.size - 5} more" else ""
    ListRow(title = title, subtitle = if (paths.isEmpty()) summary else "$summary\n$shown", subtitleMaxLines = 7)
}

private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

internal fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
}
