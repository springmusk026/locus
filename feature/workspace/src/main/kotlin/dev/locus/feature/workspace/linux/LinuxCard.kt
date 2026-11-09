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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import dev.locus.feature.workspace.R

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
                title = stringResource(R.string.workspace_linux_failed_title),
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
                Text(stringResource(R.string.workspace_linux_title), Modifier.semantics { heading() }, style = LocusType.cardTitle)
                SupportingText(stringResource(R.string.workspace_linux_subtitle))
            }
        }) {
            when (panel) {
                is LinuxPanel.Unavailable -> LocusChip(stringResource(R.string.workspace_linux_chip_unavailable), tone = Tone.Caution)
                is LinuxPanel.NotInstalled -> LocusChip(stringResource(R.string.workspace_linux_chip_not_installed))
                is LinuxPanel.Working -> LocusChip(workingLabel(panel.operation), tone = Tone.Live, dot = true)
                is LinuxPanel.Installed -> LocusChip(stringResource(R.string.workspace_linux_chip_ready), tone = Tone.Live, dot = true)
                is LinuxPanel.Failed -> LocusChip(stringResource(R.string.workspace_linux_chip_failed), tone = Tone.Critical)
            }
        }
    }
}

@Composable
private fun workingLabel(operation: LinuxOperation) = stringResource(
    when (operation) {
        LinuxOperation.Install -> R.string.workspace_linux_working_install
        LinuxOperation.Reinstall -> R.string.workspace_linux_working_reinstall
        LinuxOperation.Repair -> R.string.workspace_linux_working_repair
        LinuxOperation.Remove -> R.string.workspace_linux_working_remove
    },
)

@Composable
private fun NotInstalled(panel: LinuxPanel.NotInstalled, state: LinuxUiState, viewModel: LinuxViewModel) {
    Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SupportingText(
            stringResource(R.string.workspace_linux_intro),
            style = LocusType.rowSupporting,
        )
        Text(stringResource(R.string.workspace_linux_tools_to_add), style = LocusType.cardTitle)
        PackagePicker(state, viewModel::toggle, enabled = true)
        SupportingText(panel.estimate.summary)
        Button(onClick = viewModel::install, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) {
            Text(
                if (state.selected.isEmpty()) {
                    LINUX_INSTALL_ACTION
                } else {
                    pluralStringResource(R.plurals.workspace_linux_install_and_tools, state.selected.size, LINUX_INSTALL_ACTION, state.selected.size)
                },
            )
        }
        SupportingText(stringResource(R.string.workspace_linux_install_note))
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
            contentDescription = stringResource(R.string.workspace_linux_working_description, workingLabel(panel.operation), panel.step),
        )
        if (panel.cancellable) {
            SupportingText(stringResource(R.string.workspace_linux_working_note))
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text(stringResource(R.string.workspace_linux_cancel)) }
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
            KeyValueRow(stringResource(R.string.workspace_linux_version), stringResource(R.string.workspace_linux_alpine_version, panel.version), dense = true, mono = true)
            KeyValueRow(stringResource(R.string.workspace_linux_space_used), panel.diskBytes?.let { Formatter.formatShortFileSize(context, it) } ?: stringResource(R.string.workspace_linux_checking), dense = true, mono = true)
            KeyValueRow(
                stringResource(R.string.workspace_linux_services),
                if (panel.running.isEmpty()) {
                    stringResource(R.string.workspace_linux_services_none)
                } else {
                    stringResource(R.string.workspace_linux_services_running, panel.running.size)
                },
                dense = true,
                tone = if (panel.running.isEmpty()) null else Tone.Live,
            )
        }
        panel.running.forEach { service ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocusChip(service.name, tone = Tone.Live, dot = true, contentDescription = stringResource(R.string.workspace_linux_service_running, service.name))
                SupportingText(service.command, Modifier.weight(1f).padding(horizontal = Spacing.sm), style = LocusType.mono, maxLines = 1)
                IconButton(onClick = { viewModel.stopService(service.name) }) {
                    Icon(LocusIcons.Stop, contentDescription = stringResource(R.string.workspace_linux_service_stop, service.name), modifier = Modifier.size(IconSize.medium))
                }
            }
        }
        if (LinuxPackage.entries.any { state.packages[it] != PackageState.Installed }) {
            Text(stringResource(R.string.workspace_linux_add_tools), style = LocusType.cardTitle)
            PackagePicker(state, viewModel::toggle, enabled = true)
            if (state.selected.isNotEmpty()) {
                OutlinedButton(onClick = viewModel::install, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                    Text(pluralStringResource(R.plurals.workspace_linux_install_tools, state.selected.size, state.selected.size))
                }
            }
        }
        state.error?.let { Banner(it, Tone.Critical, title = stringResource(R.string.workspace_linux_error_title), actionsBelow = true) }
        // One focus: the terminal. Health beside it; Repair, Reinstall and Remove are rarer, so they're
        // together in Manage, each with what it does.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Button(onClick = onOpenTerminal, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                Icon(LocusIcons.Terminal, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm).size(IconSize.small))
                Text(stringResource(R.string.workspace_linux_open_terminal))
            }
            if (onOpenHealth != null) {
                OutlinedButton(onClick = onOpenHealth, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text(stringResource(R.string.workspace_linux_health)) }
            }
            TextButton(onClick = { confirm = MANAGE }, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text(stringResource(R.string.workspace_linux_manage)) }
        }
    }
    when (confirm) {
        MANAGE -> LocusSheet(title = stringResource(R.string.workspace_linux_manage_title), subtitle = stringResource(R.string.workspace_linux_alpine_version, panel.version), onDismiss = { confirm = null }) {
            CardGroup {
                ListRow(
                    title = stringResource(R.string.workspace_linux_repair),
                    subtitle = stringResource(R.string.workspace_linux_repair_summary),
                    subtitleMaxLines = 3,
                    onClick = { confirm = null; viewModel.repair() },
                    leading = { IconTile(LocusIcons.Tool) },
                )
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = stringResource(R.string.workspace_linux_reinstall),
                    subtitle = stringResource(R.string.workspace_linux_reinstall_summary),
                    subtitleMaxLines = 3,
                    onClick = { confirm = CONFIRM_REINSTALL },
                    leading = { IconTile(LocusIcons.Regenerate) },
                )
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = stringResource(R.string.workspace_linux_remove),
                    subtitle = stringResource(R.string.workspace_linux_remove_summary),
                    subtitleMaxLines = 3,
                    onClick = { confirm = CONFIRM_REMOVE },
                    leading = { IconTile(LocusIcons.Trash, tint = Tone.Critical.onContainer()) },
                )
            }
        }
        CONFIRM_REINSTALL -> ConfirmSheet(
            title = stringResource(R.string.workspace_linux_reinstall_title),
            message = stringResource(R.string.workspace_linux_reinstall_message),
            confirmLabel = stringResource(R.string.workspace_linux_reinstall),
            onConfirm = { confirm = null; viewModel.reinstall() },
            onDismiss = { confirm = null },
        )
        CONFIRM_REMOVE -> ConfirmSheet(
            title = stringResource(R.string.workspace_linux_remove_title),
            message = stringResource(R.string.workspace_linux_remove_message),
            confirmLabel = stringResource(R.string.workspace_linux_remove),
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
        stringResource(R.string.workspace_linux_kill_message, what, whenText.lowercase(), AndroidKill.REASON, AndroidKill.FIX),
        Tone.Caution,
        title = stringResource(R.string.workspace_linux_kill_title),
        actionsBelow = true,
    ) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_linux_kill_dismiss)) }
        TextButton(onClick = { runCatching { uri.openUri(AndroidKill.HELP_URL) } }) { Text(stringResource(R.string.workspace_linux_kill_fix)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackagePicker(state: LinuxUiState, onToggle: (LinuxPackage) -> Unit, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LinuxPackage.entries.forEach { p ->
            when (val pkg = state.packages[p]) {
                PackageState.Installed -> LocusChip(p.label, tone = Tone.Live, icon = LocusIcons.Check, contentDescription = stringResource(R.string.workspace_linux_package_installed, p.label))
                is PackageState.Failed -> LocusChip(
                    p.label,
                    variant = ChipVariant.Filter,
                    tone = Tone.Critical,
                    selected = p in state.selected,
                    enabled = enabled,
                    onClick = { onToggle(p) },
                    contentDescription = stringResource(R.string.workspace_linux_package_failed, p.label, p.note, pkg.detail.lineSequence().firstOrNull().orEmpty()),
                )
                else -> LocusChip(
                    p.label,
                    variant = ChipVariant.Filter,
                    selected = p in state.selected,
                    enabled = enabled,
                    onClick = { onToggle(p) },
                    contentDescription = stringResource(R.string.workspace_linux_package, p.label, p.note),
                )
            }
        }
    }
}

private const val CONFIRM_REINSTALL = "reinstall"
private const val CONFIRM_REMOVE = "remove"
private const val MANAGE = "manage"
