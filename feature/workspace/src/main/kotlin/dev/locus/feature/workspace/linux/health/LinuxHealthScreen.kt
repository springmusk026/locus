package dev.locus.feature.workspace.linux.health

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.ErrorState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.MeterSize
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.meterFraction
import dev.locus.core.designsystem.meterPercent
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.linux.AndroidKill
import dev.locus.core.linux.HealthCheck
import dev.locus.core.linux.HealthLevel
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxFix
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.linux.LinuxInventory
import dev.locus.core.linux.LinuxReport
import dev.locus.core.linux.LinuxStatus

/**
 * The Linux health screen (docs/design/linux-health.md), opened from the Workspace's Linux card: a
 * verdict, the checks with a fix each, storage and memory, services, and the installed packages.
 *
 * [onOpenTerminal] opens the terminal; [onSetUpLinux] goes back to the Linux card when Linux isn't
 * installed, which is where it's installed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxHealthRoute(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSetUpLinux: () -> Unit,
    viewModel: LinuxHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val feedback = Locus.feedback
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        feedback.show(message.text, if (message.problem) Tone.Critical else Tone.Neutral)
        viewModel.messageShown()
    }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var phantomHelp by rememberSaveable { mutableStateOf(false) }
    val report = state.report
    val fixes = FixActions(
        run = { check ->
            when (check.fix) {
                LinuxFix.Repair -> viewModel.repair()
                LinuxFix.Reinstall -> confirm = CONFIRM_REINSTALL
                LinuxFix.CleanCaches -> confirm = CONFIRM_CLEAN
                LinuxFix.PhantomHelp -> phantomHelp = true
                LinuxFix.RestartService -> check.target?.let(viewModel::restartService)
                LinuxFix.ResetDns -> viewModel.resetDns()
                LinuxFix.OpenTerminal -> onOpenTerminal()
                null -> Unit
            }
        },
        busy = { fix ->
            when (fix) {
                LinuxFix.Repair -> state.repairing
                LinuxFix.CleanCaches -> state.busy == LinuxHealthUiState.Busy.Clean
                LinuxFix.ResetDns -> state.busy == LinuxHealthUiState.Busy.Dns
                LinuxFix.RestartService -> state.busy == LinuxHealthUiState.Busy.Restart
                else -> false
            }
        },
        anyBusy = state.busy != null || state.repairing,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                "Linux health",
                onBack = onBack,
                subtitle = report?.let(::identity),
                actions = {
                    if (state.status == LinuxStatus.Ready) {
                        IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading && !state.refreshing) {
                            Icon(LocusIcons.Regenerate, contentDescription = "Check again")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val body = Modifier.padding(padding)
        when (val status = state.status) {
            is LinuxStatus.Unavailable -> EmptyState(
                title = "Linux can't run on this phone",
                body = status.reason,
                modifier = body,
                icon = LocusIcons.Terminal,
            )
            is LinuxStatus.Installing -> Column(body.fillMaxSize().padding(Spacing.xxl), verticalArrangement = Arrangement.Center) {
                Text("Linux is being set up", style = LocusType.stateTitle, modifier = Modifier.semantics { heading() })
                SupportingText(status.step, Modifier.padding(top = Spacing.xs, bottom = Spacing.md))
                Meter(status.fraction, size = MeterSize.Medium, contentDescription = status.fraction?.let { "${status.step}, ${meterPercent(it)}" } ?: status.step)
            }
            LinuxStatus.NotInstalled, is LinuxStatus.Failed -> EmptyState(
                title = "Linux isn't installed",
                body = if (status is LinuxStatus.Failed) "The last install didn't finish: ${status.message}" else
                    "Install it from the Workspace's Linux card to run commands, servers and the terminal. Its health shows here once it's set up.",
                modifier = body,
                icon = LocusIcons.Terminal,
                action = { Button(onClick = onSetUpLinux, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Set up Linux") } },
            )
            LinuxStatus.Ready -> when {
                report == null && state.error != null -> ErrorState(
                    title = "Couldn't check Linux",
                    message = state.error.orEmpty(),
                    onRetry = { viewModel.refresh() },
                    modifier = body,
                    retrying = state.loading,
                )
                report == null -> LoadingState(body)
                else -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = body.fillMaxSize(),
                ) {
                    HealthContent(state, report, fixes, viewModel, onOpenTerminal)
                }
            }
        }
    }

    when (confirm) {
        CONFIRM_CLEAN -> ConfirmSheet(
            title = "Clean caches?",
            message = "Deletes ${report?.storage?.cacheBytes?.let(LinuxHealth::bytes) ?: "the"} of package and download caches " +
                "(apk, pip, npm, Go, Cargo, Gradle). Nothing installed is removed; tools download what they need again.",
            confirmLabel = "Clean",
            onConfirm = { confirm = null; viewModel.cleanCaches() },
            onDismiss = { confirm = null },
        )
        CONFIRM_REINSTALL -> ConfirmSheet(
            title = "Reinstall Linux?",
            message = "Downloads Alpine Linux again and reinstalls the tools Locus added. Anything else you installed with apk, " +
                "and running services and terminals, are removed. Your workspace files stay.",
            confirmLabel = "Reinstall",
            tone = Tone.Critical,
            onConfirm = { confirm = null; viewModel.reinstall() },
            onDismiss = { confirm = null },
        )
    }
    if (phantomHelp) PhantomHelpSheet(report?.phantom, onDismiss = { phantomHelp = false })
}

/** How the checks' buttons run and show progress; one fix at a time. */
private class FixActions(val run: (HealthCheck) -> Unit, val busy: (LinuxFix?) -> Boolean, val anyBusy: Boolean)

@Composable
private fun HealthContent(
    state: LinuxHealthUiState,
    report: LinuxReport,
    fixes: FixActions,
    viewModel: LinuxHealthViewModel,
    onOpenTerminal: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var askedOnly by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "verdict") { Verdict(state.checks, report, state.repairing) }
        item(key = "checks-label") { SectionLabel("Checks", Modifier.padding(top = Spacing.md)) }
        item(key = "checks") { Checks(state, report, fixes, onCheckNetwork = viewModel::checkNetwork) }
        report.storage?.let { storage ->
            item(key = "storage-label") { SectionLabel("Storage", Modifier.padding(top = Spacing.md)) }
            item(key = "storage") { StorageCard(storage, cleaning = state.busy == LinuxHealthUiState.Busy.Clean, enabled = !fixes.anyBusy) { fixes.run(HealthCheck("clean", "", HealthLevel.Ok, "", LinuxFix.CleanCaches)) } }
        }
        report.memory?.let { memory ->
            item(key = "memory-label") { SectionLabel("Memory", Modifier.padding(top = Spacing.md)) }
            item(key = "memory") { MemoryCard(memory, report.processes) }
        }
        item(key = "services-label") { SectionLabel("Services", Modifier.padding(top = Spacing.md)) }
        item(key = "services") {
            Services(
                report.services,
                busy = state.busy == LinuxHealthUiState.Busy.Restart,
                enabled = !fixes.anyBusy,
                onRestart = viewModel::restartService,
                onStop = viewModel::stopService,
            )
        }
        packages(report, state, query, { query = it }, askedOnly, { askedOnly = it }, viewModel::checkUpgrades, onOpenTerminal)
    }
}

/** "Alpine 3.24.3 · aarch64 · proot 5.4.0" for the top bar. */
private fun identity(report: LinuxReport): String =
    listOfNotNull(report.alpineVersion?.let { "Alpine $it" }, report.arch, report.runtime.prootVersion?.let { "proot $it" }).joinToString(" · ")

private fun HealthLevel.tone() = when (this) {
    HealthLevel.Ok -> Tone.Live
    HealthLevel.Warning -> Tone.Caution
    HealthLevel.Problem -> Tone.Critical
}

private fun HealthLevel.label() = when (this) {
    HealthLevel.Ok -> "OK"
    HealthLevel.Warning -> "Warning"
    HealthLevel.Problem -> "Problem"
}

@Composable
private fun Verdict(checks: List<HealthCheck>, report: LinuxReport, repairing: Boolean) {
    val level = LinuxHealth.overall(checks)
    val headline = LinuxHealth.headline(checks)
    LocusCard(glow = level.tone().color().takeIf { level != HealthLevel.Ok }) {
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            LocusChip(level.label(), tone = level.tone(), dot = true, contentDescription = "Status: ${level.label()}")
            Text(headline, style = LocusType.stateTitle, modifier = Modifier.semantics { heading() })
            val problems = checks.count { it.level != HealthLevel.Ok }
            SupportingText(
                buildString {
                    append(if (problems == 0) "${checks.size} checks passed" else "$problems of ${checks.size} checks need attention")
                    append(" · checked ${relativeTime(report.takenAt).lowercase()}")
                },
            )
            if (repairing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    InlineSpinner(contentDescription = null)
                    SupportingText("Repairing…")
                }
            }
        }
    }
}

@Composable
private fun Checks(state: LinuxHealthUiState, report: LinuxReport, fixes: FixActions, onCheckNetwork: () -> Unit) {
    CardGroup {
        state.checks.forEachIndexed { i, check ->
            if (i > 0) GroupDivider()
            CheckRow(check, fixes)
        }
        if (report.network == null) {
            if (state.checks.isNotEmpty()) GroupDivider()
            ListRow(
                title = "Internet from Linux",
                subtitle = "Not checked. Looks up and opens Alpine's package mirror.",
                subtitleMaxLines = 3,
                leading = { Icon(LocusIcons.Globe, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.medium)) },
                trailing = {
                    if (state.checkingNetwork) InlineSpinner(contentDescription = "Checking the network")
                    else TextButton(onClick = onCheckNetwork, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Check") }
                },
            )
        }
    }
}

@Composable
private fun CheckRow(check: HealthCheck, fixes: FixActions) {
    val icon = when (check.level) {
        HealthLevel.Ok -> LocusIcons.Check
        HealthLevel.Warning -> LocusIcons.Info
        HealthLevel.Problem -> LocusIcons.Alert
    }
    ListRow(
        title = check.title,
        subtitle = check.detail,
        titleMaxLines = 2,
        subtitleMaxLines = 6,
        leading = { Icon(icon, contentDescription = check.level.label(), tint = check.level.tone().color(), modifier = Modifier.size(IconSize.medium)) },
        trailing = check.fix?.let { fix ->
            {
                if (fixes.busy(fix)) {
                    InlineSpinner(contentDescription = "${fix.label} in progress")
                } else {
                    TextButton(
                        onClick = { fixes.run(check) },
                        enabled = !fixes.anyBusy,
                        modifier = Modifier.heightIn(min = Sizes.touchTarget).semantics { contentDescription = "${fix.label}: ${check.title}" },
                    ) { Text(fix.label) }
                }
            }
        },
    )
}

@Composable
private fun StorageCard(storage: LinuxReport.Storage, cleaning: Boolean, enabled: Boolean, onClean: () -> Unit) {
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val used = (storage.phoneTotalBytes - storage.phoneFreeBytes).coerceAtLeast(0)
            val fraction = meterFraction(used, storage.phoneTotalBytes)
            Text("Phone storage", style = LocusType.cardTitle)
            Meter(fraction, size = MeterSize.Medium, contentDescription = "Phone storage, ${meterPercent(fraction)} used")
            SupportingText("${LinuxHealth.bytes(storage.phoneFreeBytes)} free of ${LinuxHealth.bytes(storage.phoneTotalBytes)}")
        }
        Column(Modifier.padding(top = Spacing.sm)) {
            KeyValueRow("Linux uses", LinuxHealth.bytes(storage.linuxBytes), dense = true, mono = true)
            if (storage.tmpBytes > 0) KeyValueRow("/tmp", LinuxHealth.bytes(storage.tmpBytes), dense = true, mono = true)
            storage.biggest.forEach { KeyValueRow(it.path, LinuxHealth.bytes(it.bytes), dense = true, mono = true) }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Caches", style = LocusType.cardTitle)
                SupportingText(
                    if (storage.caches.isEmpty()) "None to clean."
                    else storage.caches.joinToString(", ") { "${it.path} ${LinuxHealth.bytes(it.bytes)}" },
                )
            }
            if (cleaning) InlineSpinner(contentDescription = "Cleaning caches")
            else OutlinedButton(onClick = onClean, enabled = enabled && storage.cacheBytes > 0, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                Text(if (storage.cacheBytes > 0) "Clean ${LinuxHealth.bytes(storage.cacheBytes)}" else "Clean")
            }
        }
    }
}

@Composable
private fun MemoryCard(memory: LinuxReport.Memory, processes: List<LinuxReport.GuestProcess>) {
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val used = (memory.totalBytes - memory.availableBytes).coerceAtLeast(0)
            val fraction = meterFraction(used, memory.totalBytes)
            Text("Phone memory", style = LocusType.cardTitle)
            Meter(fraction, size = MeterSize.Medium, contentDescription = "Phone memory, ${meterPercent(fraction)} in use")
            SupportingText("${LinuxHealth.bytes(memory.availableBytes)} available of ${LinuxHealth.bytes(memory.totalBytes)}")
        }
        Column(Modifier.padding(top = Spacing.sm)) {
            val total = processes.sumOf { it.rssBytes }
            KeyValueRow(
                "Linux programs",
                if (processes.isEmpty()) "None running" else "${processes.size} using ${LinuxHealth.bytes(total)}",
                dense = true,
            )
            processes.take(TOP_PROCESSES).forEach { KeyValueRow(it.name, LinuxHealth.bytes(it.rssBytes), dense = true, mono = true) }
            if (processes.size > TOP_PROCESSES) SupportingText("and ${processes.size - TOP_PROCESSES} more", Modifier.padding(horizontal = Spacing.md))
        }
    }
}

@Composable
private fun Services(
    services: List<LinuxEnvironment.ServiceStatus>,
    busy: Boolean,
    enabled: Boolean,
    onRestart: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    if (services.isEmpty()) {
        SupportingText("No services. Ask the agent to start a server and it appears here, with its state.", Modifier.padding(horizontal = Spacing.xs))
        return
    }
    CardGroup {
        services.forEachIndexed { i, s ->
            if (i > 0) GroupDivider()
            val (label, tone) = when {
                s.running -> "Running" to Tone.Live
                s.killedByAndroid -> "Killed by Android" to Tone.Caution
                s.lostOnRestart -> "Stopped by restart" to Tone.Neutral
                else -> "Exited" to Tone.Neutral
            }
            ListRow(
                title = s.name,
                subtitle = s.command,
                subtitleMaxLines = 2,
                meta = { LocusChip(label, tone = tone, dot = s.running, contentDescription = "${s.name}: $label") },
                trailing = {
                    if (busy) {
                        InlineSpinner(contentDescription = "Working on ${s.name}")
                    } else if (s.running) {
                        TextButton(onClick = { onStop(s.name) }, enabled = enabled, modifier = Modifier.heightIn(min = Sizes.touchTarget).semantics { contentDescription = "Stop ${s.name}" }) { Text("Stop") }
                    } else {
                        TextButton(onClick = { onRestart(s.name) }, enabled = enabled, modifier = Modifier.heightIn(min = Sizes.touchTarget).semantics { contentDescription = "Restart ${s.name}" }) { Text("Restart") }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.packages(
    report: LinuxReport,
    state: LinuxHealthUiState,
    query: String,
    onQuery: (String) -> Unit,
    askedOnly: Boolean,
    onAskedOnly: (Boolean) -> Unit,
    onCheckUpgrades: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val all = report.packages
    item(key = "packages-label") {
        SectionLabel(
            "Packages",
            Modifier.padding(top = Spacing.md),
            action = if (all != null && !state.checkingUpgrades) "Check for updates" else null,
            onAction = onCheckUpgrades,
        )
    }
    if (all == null) {
        item(key = "packages-error") {
            Banner("apk's package database can't be read, so the installed packages can't be listed. Reinstalling Linux fixes it.", Tone.Critical, title = "Packages unknown")
        }
        return
    }
    item(key = "updates") { Updates(report.upgrades, state.checkingUpgrades, state.upgradesError, onCheckUpgrades, onOpenTerminal) }
    item(key = "package-filters") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search ${all.size} packages") },
                leadingIcon = { Icon(LocusIcons.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { onQuery("") }) { Icon(LocusIcons.Close, contentDescription = "Clear search") } }
                } else null,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                LocusChip(
                    "Asked for (${all.count { it.explicit }})",
                    variant = ChipVariant.Filter,
                    selected = askedOnly,
                    onClick = { onAskedOnly(!askedOnly) },
                    contentDescription = "Only packages asked for, not their dependencies",
                )
                LocusChip("${all.size} installed", contentDescription = "${all.size} packages installed")
                LocusChip(LinuxHealth.bytes(all.sumOf { it.installedBytes }), contentDescription = "Packages take ${LinuxHealth.bytes(all.sumOf { it.installedBytes })}")
            }
        }
    }
    val shown = LinuxInventory.search(all, query).filter { !askedOnly || it.explicit }
    if (shown.isEmpty()) {
        item(key = "packages-empty") {
            SupportingText(if (query.isBlank()) "No packages." else "No installed package matches “${query.trim()}”.", Modifier.padding(Spacing.md))
        }
    } else {
        items(shown, key = { "pkg:${it.name}" }) { p ->
            CardGroup {
                ListRow(
                    title = p.name,
                    subtitle = listOfNotNull(p.version, LinuxHealth.bytes(p.installedBytes).takeIf { p.installedBytes > 0 }, p.description.takeIf { it.isNotBlank() }).joinToString(" · "),
                    subtitleMaxLines = 2,
                    meta = if (p.explicit) ({ LocusChip("Asked for", tone = Tone.Brand, contentDescription = "Asked for") }) else null,
                )
            }
        }
    }
}

@Composable
private fun Updates(
    upgrades: List<LinuxInventory.Upgrade>?,
    checking: Boolean,
    error: String?,
    onCheck: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    when {
        checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(horizontal = Spacing.xs)) {
            InlineSpinner(contentDescription = null)
            SupportingText("Checking Alpine's repositories for updates…")
        }
        error != null -> Banner(error, Tone.Caution, title = "Couldn't check for updates", actionsBelow = true) {
            TextButton(onClick = onCheck) { Text("Try again") }
        }
        upgrades == null -> SupportingText("Updates aren't checked until you ask, since that downloads Alpine's package index.", Modifier.padding(horizontal = Spacing.xs))
        upgrades.isEmpty() -> SupportingText("Everything is up to date.", Modifier.padding(horizontal = Spacing.xs))
        else -> LocusCard {
            Text("${upgrades.size} update${if (upgrades.size == 1) "" else "s"} available", style = LocusType.cardTitle)
            Column(Modifier.padding(vertical = Spacing.xs)) {
                upgrades.take(MAX_UPGRADES).forEach { KeyValueRow(it.name, "${it.installed} → ${it.available}", dense = true, mono = true) }
                if (upgrades.size > MAX_UPGRADES) SupportingText("and ${upgrades.size - MAX_UPGRADES} more", Modifier.padding(horizontal = Spacing.md))
            }
            SupportingText("Install them with `apk upgrade` in the terminal.")
            OutlinedButton(onClick = onOpenTerminal, modifier = Modifier.padding(top = Spacing.sm).heightIn(min = Sizes.touchTarget)) { Text("Open terminal") }
        }
    }
}

/** Why Android stops Linux in the background, and the switch that stops it (from #113's [AndroidKill]). */
@Composable
private fun PhantomHelpSheet(phantom: LinuxReport.Phantom?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    LocusSheet(title = "Android's background limit", onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(AndroidKill.REASON, style = LocusType.rowSupporting)
            Text(AndroidKill.FIX, style = LocusType.rowSupporting)
            val state = when (phantom?.monitorOff) {
                true -> "On this phone the restriction is off."
                false -> "On this phone the restriction is on."
                null -> "This phone doesn't let Locus read the setting, so check it yourself."
            }
            SupportingText(state + if (phantom?.developerOptionsOn == false) " Developer options are off; on Android 14 and later, turning them off also turns the restriction back on." else "")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(bottom = Spacing.xl)) {
                Button(
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .recoverCatching { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    modifier = Modifier.heightIn(min = Sizes.touchTarget),
                ) { Text("Developer options") }
                TextButton(onClick = { runCatching { uri.openUri(AndroidKill.HELP_URL) } }, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Read the guide") }
            }
        }
    }
}

private const val TOP_PROCESSES = 5
private const val MAX_UPGRADES = 12
private const val CONFIRM_CLEAN = "clean"
private const val CONFIRM_REINSTALL = "reinstall"
