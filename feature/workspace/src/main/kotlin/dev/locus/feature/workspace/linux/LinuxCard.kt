package dev.locus.feature.workspace.linux

import dev.locus.core.designsystem.SideOrBelow
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.data.linux.LinuxOperation
import dev.locus.core.data.linux.LinuxPackage
import dev.locus.core.data.linux.LinuxPanel
import dev.locus.core.data.linux.PackageState
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.ErrorState
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.onContainer
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.MeterSize
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.linux.AndroidKill
import dev.locus.core.model.LINUX_INSTALL_ACTION

/**
 * The Workspace's Linux card (docs/design/linux-setup.md): install with an estimate, progress with
 * Cancel, the installed system with its terminal, services, Repair, Reinstall and Remove, a failed
 * install with Try again, and Android's phantom-process kills with the fix.
 *
 * [onOpenHealth], when given, adds "Health & details" to the installed state (the Linux health screen).
 */
@Composable
fun LinuxCard(
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenHealth: (() -> Unit)? = null,
    viewModel: LinuxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val feedback = Locus.feedback
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        feedback.show(message)
        viewModel.messageShown()
    }
    LocusCard(modifier) {
        Header(state.panel)
        when (val panel = state.panel) {
            is LinuxPanel.Unavailable -> SupportingText(panel.reason, Modifier.padding(top = Spacing.sm), style = LocusType.rowSupporting)
            is LinuxPanel.NotInstalled -> NotInstalled(panel, state, viewModel)
            is LinuxPanel.Working -> Working(panel, viewModel::cancel)
            is LinuxPanel.Installed -> Installed(panel, state, viewModel, onOpenTerminal, onOpenHealth)
            is LinuxPanel.Failed -> ErrorState(
                title = "Linux couldn't be installed",
                message = panel.reason,
                onRetry = viewModel::install,
                icon = LocusIcons.Terminal,
            )
        }
    }
}

@Composable
private fun Header(panel: LinuxPanel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        IconTile(LocusIcons.Terminal, size = IconSize.tile)
        // The state chip goes under the title when it would squeeze the line about /workspace.
        SideOrBelow(Modifier.weight(1f), main = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text("Linux", Modifier.semantics { heading() }, style = LocusType.cardTitle)
                SupportingText("Alpine Linux, with your workspace at /workspace")
            }
        }) {
            when (panel) {
                is LinuxPanel.Unavailable -> LocusChip("Not available", tone = Tone.Caution)
                is LinuxPanel.NotInstalled -> LocusChip("Not installed")
                is LinuxPanel.Working -> LocusChip(workingLabel(panel.operation), tone = Tone.Live, dot = true)
                is LinuxPanel.Installed -> LocusChip("Ready", tone = Tone.Live, dot = true)
                is LinuxPanel.Failed -> LocusChip("Failed", tone = Tone.Critical)
            }
        }
    }
}

private fun workingLabel(operation: LinuxOperation) = when (operation) {
    LinuxOperation.Install -> "Installing"
    LinuxOperation.Reinstall -> "Reinstalling"
    LinuxOperation.Repair -> "Repairing"
    LinuxOperation.Remove -> "Removing"
}

@Composable
private fun NotInstalled(panel: LinuxPanel.NotInstalled, state: LinuxUiState, viewModel: LinuxViewModel) {
    Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SupportingText(
            "Lets the agent and the terminal run programs here: Python, Git, Node.js and anything else from apk. " +
                "Installing downloads Alpine Linux and the tools you pick from alpinelinux.org. These programs don't come from F-Droid " +
                "or this app's release, and F-Droid hasn't checked them. Locus checks the Alpine image's checksum before unpacking it.",
            style = LocusType.rowSupporting,
        )
        Text("Tools to add", style = LocusType.cardTitle)
        PackagePicker(state, viewModel::toggle, enabled = true)
        SupportingText(panel.estimate.summary)
        Button(onClick = viewModel::install, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) {
            Text(if (state.selected.isEmpty()) LINUX_INSTALL_ACTION else "$LINUX_INSTALL_ACTION and ${plural(state.selected.size, "tool")}")
        }
        SupportingText("It keeps going if you leave this screen, with a notification. Locus never installs Linux on its own.")
    }
}

@Composable
private fun Working(panel: LinuxPanel.Working, onCancel: () -> Unit) {
    Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(panel.step, style = LocusType.rowSupporting)
        Meter(
            panel.fraction,
            tone = Tone.Live,
            size = MeterSize.Medium,
            contentDescription = "${workingLabel(panel.operation)} Linux: ${panel.step}",
        )
        if (panel.cancellable) {
            SupportingText("You can leave this screen; it carries on with a notification.")
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Cancel") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Installed(
    panel: LinuxPanel.Installed,
    state: LinuxUiState,
    viewModel: LinuxViewModel,
    onOpenTerminal: () -> Unit,
    onOpenHealth: (() -> Unit)?,
) {
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        panel.killed?.let { kill ->
            KillNotice(kill.what, relativeTime(kill.at), onDismiss = viewModel::dismissKill)
        }
        Column {
            KeyValueRow("Version", "Alpine Linux ${panel.version}", dense = true, mono = true)
            KeyValueRow("Space used", panel.diskBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "Checking…", dense = true, mono = true)
            KeyValueRow(
                "Services",
                if (panel.running.isEmpty()) "None running" else "${panel.running.size} running",
                dense = true,
                tone = if (panel.running.isEmpty()) null else Tone.Live,
            )
        }
        panel.running.forEach { service ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocusChip(service.name, tone = Tone.Live, dot = true, contentDescription = "Service ${service.name}, running")
                SupportingText(service.command, Modifier.weight(1f).padding(horizontal = Spacing.sm), style = LocusType.mono, maxLines = 1)
                IconButton(onClick = { viewModel.stopService(service.name) }) {
                    Icon(LocusIcons.Stop, contentDescription = "Stop ${service.name}", modifier = Modifier.size(IconSize.medium))
                }
            }
        }
        if (LinuxPackage.entries.any { state.packages[it] != PackageState.Installed }) {
            Text("Add tools", style = LocusType.cardTitle)
            PackagePicker(state, viewModel::toggle, enabled = true)
            if (state.selected.isNotEmpty()) {
                OutlinedButton(onClick = viewModel::install, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                    Text("Install ${plural(state.selected.size, "tool")}")
                }
            }
        }
        state.error?.let { Banner(it, Tone.Critical, title = "That didn't work", actionsBelow = true) }
        // One focus: the terminal. Health beside it; Repair, Reinstall and Remove are rarer, so they're
        // together in Manage, each with what it does.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Button(onClick = onOpenTerminal, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                Icon(LocusIcons.Terminal, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm).size(IconSize.small))
                Text("Open terminal")
            }
            if (onOpenHealth != null) {
                OutlinedButton(onClick = onOpenHealth, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Health & details") }
            }
            TextButton(onClick = { confirm = MANAGE }, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Manage") }
        }
    }
    when (confirm) {
        MANAGE -> LocusSheet(title = "Manage Linux", subtitle = "Alpine Linux ${panel.version}", onDismiss = { confirm = null }) {
            CardGroup {
                ListRow(
                    title = "Repair",
                    subtitle = "Rewrites Linux's network and system files and checks it starts. Your packages and files stay.",
                    subtitleMaxLines = 3,
                    onClick = { confirm = null; viewModel.repair() },
                    leading = { IconTile(LocusIcons.Tool) },
                )
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = "Reinstall",
                    subtitle = "Downloads Alpine again. Tools Locus added come back; your workspace files stay.",
                    subtitleMaxLines = 3,
                    onClick = { confirm = CONFIRM_REINSTALL },
                    leading = { IconTile(LocusIcons.Regenerate) },
                )
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = "Remove",
                    subtitle = "Deletes Linux and everything installed in it. Your workspace files stay.",
                    subtitleMaxLines = 3,
                    onClick = { confirm = CONFIRM_REMOVE },
                    leading = { IconTile(LocusIcons.Trash, tint = Tone.Critical.onContainer()) },
                )
            }
        }
        CONFIRM_REINSTALL -> ConfirmSheet(
            title = "Reinstall Linux?",
            message = "Downloads Alpine Linux again and reinstalls the tools Locus added. Anything else you installed with apk, " +
                "and running services and terminals, are removed. Your workspace files stay.",
            confirmLabel = "Reinstall",
            onConfirm = { confirm = null; viewModel.reinstall() },
            onDismiss = { confirm = null },
        )
        CONFIRM_REMOVE -> ConfirmSheet(
            title = "Remove Linux?",
            message = "Deletes Linux and everything installed in it, and stops its services and terminals. " +
                "Your workspace files stay. You can install it again later.",
            confirmLabel = "Remove",
            tone = Tone.Critical,
            onConfirm = { confirm = null; viewModel.remove() },
            onDismiss = { confirm = null },
        )
    }
}

/** Android killed a Linux process in the background: why, and how to stop it (from [AndroidKill]). */
@Composable
private fun KillNotice(what: String, whenText: String, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    Banner(
        "$what was stopped by Android (${whenText.lowercase()}). ${AndroidKill.REASON} ${AndroidKill.FIX}",
        Tone.Caution,
        title = "Killed by Android (phantom process limit)",
        actionsBelow = true,
    ) {
        TextButton(onClick = onDismiss) { Text("Dismiss") }
        TextButton(onClick = { runCatching { uri.openUri(AndroidKill.HELP_URL) } }) { Text("How to fix it") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackagePicker(state: LinuxUiState, onToggle: (LinuxPackage) -> Unit, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LinuxPackage.entries.forEach { p ->
            when (val pkg = state.packages[p]) {
                PackageState.Installed -> LocusChip(p.label, tone = Tone.Live, icon = LocusIcons.Check, contentDescription = "${p.label}, installed")
                is PackageState.Failed -> LocusChip(
                    p.label,
                    variant = ChipVariant.Filter,
                    tone = Tone.Critical,
                    selected = p in state.selected,
                    enabled = enabled,
                    onClick = { onToggle(p) },
                    contentDescription = "${p.label}, ${p.note}, failed last time: ${pkg.detail.lineSequence().firstOrNull().orEmpty()}",
                )
                else -> LocusChip(
                    p.label,
                    variant = ChipVariant.Filter,
                    selected = p in state.selected,
                    enabled = enabled,
                    onClick = { onToggle(p) },
                    contentDescription = "${p.label}, ${p.note}",
                )
            }
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

private const val CONFIRM_REINSTALL = "reinstall"
private const val CONFIRM_REMOVE = "remove"
private const val MANAGE = "manage"
