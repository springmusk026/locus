package dev.locus.feature.workspace.linux.health

import dev.locus.core.designsystem.AmountRow
import dev.locus.core.designsystem.SideOrBelow
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.ErrorState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.RowChevron
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
import dev.locus.core.designsystem.SwitchRow
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
 * summary, top to bottom: the verdict, the problems with a fix each, storage, memory (a warning
 * only when it's low), services, and the packages as one row that opens [onOpenPackages]. Long lists
 * (passed checks, folders, processes) show their top few until "Show all" ([HealthSummary]).
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
    onOpenPackages: () -> Unit,
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
                LinuxFix.RestartService, LinuxFix.StartService -> check.target?.let(viewModel::restartService)
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
                LinuxFix.RestartService, LinuxFix.StartService -> state.busy == LinuxHealthUiState.Busy.Restart
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
                    title = "Linux didn't answer",
                    // What to do first; the raw reason stays, last, for anyone reporting it.
                    message = "Its health check didn't finish. Try again, and if it keeps failing, reinstall Linux from Workspace." +
                        state.error?.takeIf { it.isNotBlank() }?.let { "\n\nDetails: $it" }.orEmpty(),
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
                    HealthContent(state, report, fixes, viewModel, onOpenPackages)
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
    onOpenPackages: () -> Unit,
) {
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
        if (report.memory != null || report.processes.isNotEmpty()) {
            item(key = "memory-label") { SectionLabel("Memory", Modifier.padding(top = Spacing.md)) }
            item(key = "memory") { MemorySection(report.memory, report.processes) }
        }
        item(key = "services-label") { SectionLabel("Services", Modifier.padding(top = Spacing.md)) }
        item(key = "services") {
            Services(
                report.services,
                busy = state.busy == LinuxHealthUiState.Busy.Restart,
                autostartBusy = state.busyService.takeIf { state.busy == LinuxHealthUiState.Busy.Autostart },
                enabled = !fixes.anyBusy,
                onRestart = viewModel::restartService,
                onStop = viewModel::stopService,
                onAutostart = viewModel::setAutostart,
            )
        }
        item(key = "packages") { PackagesRow(report.packages, onOpenPackages, Modifier.padding(top = Spacing.md)) }
    }
}

/** The packages as one row: how many the user installed and in all; the list is its own screen. */
@Composable
private fun PackagesRow(packages: List<LinuxInventory.Package>?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    CardGroup(modifier) {
        ListRow(
            title = "Packages",
            subtitle = HealthSummary.packagesLine(packages),
            leading = { Icon(LocusIcons.Library, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.medium)) },
            onClick = onOpen,
            trailing = { RowChevron() },
        )
    }
}

/** "Show all 12" / "Show fewer" under a list cut to its top few. */
@Composable
private fun ShowAll(expanded: Boolean, total: Int, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
        Text(if (expanded) "Show fewer" else "Show all $total")
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
    // No glow: the chip carries the level, and a tinted card under it pulls the chip's text below AA.
    LocusCard {
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
    var showPassed by rememberSaveable { mutableStateOf(false) }
    val problems = HealthSummary.problems(state.checks)
    val passed = HealthSummary.passed(state.checks)
    CardGroup {
        problems.forEachIndexed { i, check ->
            if (i > 0) GroupDivider()
            CheckRow(check, fixes)
        }
        if (passed.isNotEmpty()) {
            if (problems.isNotEmpty()) GroupDivider()
            ListRow(
                title = "${passed.size} check${if (passed.size == 1) "" else "s"} passed",
                titleMaxLines = 2,
                subtitle = passed.joinToString(", ") { it.title },
                leading = { Icon(LocusIcons.Check, contentDescription = null, tint = Tone.Live.color(), modifier = Modifier.size(IconSize.medium)) },
                onClick = { showPassed = !showPassed },
                trailing = { Text(if (showPassed) "Hide" else "Show", style = LocusType.label, color = MaterialTheme.colorScheme.primary) },
            )
            if (showPassed) passed.forEach { check ->
                GroupDivider()
                CheckRow(check, fixes)
            }
        }
        if (report.network == null) {
            if (problems.isNotEmpty() || passed.isNotEmpty()) GroupDivider()
            ListRow(
                title = "Internet from Linux",
                subtitle = "Not checked. Looks up and opens Alpine's package mirror.",
                subtitleMaxLines = Int.MAX_VALUE,
                titleMaxLines = 2,
                leading = { Icon(LocusIcons.Globe, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.medium)) },
                meta = {
                    if (state.checkingNetwork) InlineSpinner(contentDescription = "Checking the network")
                    else OutlinedButton(onClick = onCheckNetwork, modifier = Modifier.padding(top = Spacing.xs).heightIn(min = Sizes.touchTarget)) { Text("Check") }
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
        subtitleMaxLines = Int.MAX_VALUE,
        leading = { Icon(icon, contentDescription = check.level.label(), tint = check.level.tone().color(), modifier = Modifier.size(IconSize.medium)) },
        // The fix sits under the explanation, so the title and detail keep the row's width.
        meta = check.fix?.let { fix ->
            {
                if (fixes.busy(fix)) {
                    InlineSpinner(contentDescription = "${fix.label} in progress")
                } else {
                    OutlinedButton(
                        onClick = { fixes.run(check) },
                        enabled = !fixes.anyBusy,
                        modifier = Modifier.padding(top = Spacing.xs).heightIn(min = Sizes.touchTarget).semantics { contentDescription = "${fix.label}: ${check.title}" },
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
            AmountRow("Linux uses", LinuxHealth.bytes(storage.linuxBytes))
            if (storage.tmpBytes > 0) AmountRow("/tmp", LinuxHealth.bytes(storage.tmpBytes), monoLabel = true)
            var allFolders by rememberSaveable { mutableStateOf(false) }
            HealthSummary.visible(storage.biggest, allFolders).forEach { AmountRow(it.path, LinuxHealth.bytes(it.bytes), monoLabel = true) }
            if (HealthSummary.hasMore(storage.biggest)) ShowAll(allFolders, storage.biggest.size) { allFolders = !allFolders }
        }
        // The button goes under the caches when beside them it would squeeze the list.
        SideOrBelow(Modifier.fillMaxWidth().padding(top = Spacing.sm), main = {
            Column {
                Text("Caches", style = LocusType.cardTitle)
                SupportingText(
                    if (storage.caches.isEmpty()) "None to clean."
                    else storage.caches.joinToString(", ") { "${it.path} ${LinuxHealth.bytes(it.bytes)}" },
                )
            }
        }) {
            if (cleaning) InlineSpinner(contentDescription = "Cleaning caches")
            else OutlinedButton(onClick = onClean, enabled = enabled && storage.cacheBytes > 0, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                Text(if (storage.cacheBytes > 0) "Clean ${LinuxHealth.bytes(storage.cacheBytes)}" else "Clean")
            }
        }
    }
}

/**
 * Memory as a warning, not a dashboard: a caution banner only when the phone is low, then one line of
 * what Linux's programs hold and the biggest few.
 */
@Composable
private fun MemorySection(memory: LinuxReport.Memory?, processes: List<LinuxReport.GuestProcess>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val warning = HealthSummary.memoryWarning(memory)
        if (warning != null) Banner(warning, Tone.Caution, title = "Phone is low on memory")
        LocusCard {
            if (memory != null && warning == null) {
                AmountRow("Phone memory", "${LinuxHealth.bytes(memory.availableBytes)} available")
            }
            AmountRow("Linux programs", HealthSummary.processesLine(processes))
            var allProcesses by rememberSaveable { mutableStateOf(false) }
            HealthSummary.visible(processes, allProcesses).forEach { AmountRow(it.name, LinuxHealth.bytes(it.rssBytes), monoLabel = true) }
            if (HealthSummary.hasMore(processes)) ShowAll(allProcesses, processes.size) { allProcesses = !allProcesses }
        }
    }
}

/**
 * What a service's chip says, its tone, and its button. A service a restart ended is neutral: services
 * end with Locus, so that's expected, not a problem (unless it was set to start when Locus opens).
 */
internal data class ServiceView(val label: String, val tone: Tone, val action: ServiceAction)

internal enum class ServiceAction(val label: String) { Stop("Stop"), Start("Start"), Restart("Restart") }

internal fun serviceView(s: LinuxEnvironment.ServiceStatus): ServiceView = when (s.state) {
    LinuxEnvironment.ServiceState.Running -> ServiceView("Running", Tone.Live, ServiceAction.Stop)
    LinuxEnvironment.ServiceState.Crashed -> ServiceView("Crashed, ${LinuxHealth.exitDescription(s.exitCode)}", Tone.Critical, ServiceAction.Restart)
    LinuxEnvironment.ServiceState.KilledByAndroid -> ServiceView("Killed by Android", Tone.Caution, ServiceAction.Restart)
    LinuxEnvironment.ServiceState.StoppedByRestart ->
        if (s.autostart) ServiceView("Didn't start", Tone.Caution, ServiceAction.Start) else ServiceView("Stopped by restart", Tone.Neutral, ServiceAction.Start)
    LinuxEnvironment.ServiceState.Finished ->
        ServiceView("Finished", if (s.autostart) Tone.Caution else Tone.Neutral, ServiceAction.Start)
}

@Composable
private fun Services(
    services: List<LinuxEnvironment.ServiceStatus>,
    busy: Boolean,
    /** The service whose "Start when Locus opens" is being saved. */
    autostartBusy: String?,
    enabled: Boolean,
    onRestart: (String) -> Unit,
    onStop: (String) -> Unit,
    onAutostart: (String, Boolean) -> Unit,
) {
    if (services.isEmpty()) {
        SupportingText("No services. Ask the agent to start a server and it appears here, with its state.", Modifier.padding(horizontal = Spacing.xs))
        return
    }
    CardGroup {
        services.forEachIndexed { i, s ->
            if (i > 0) GroupDivider()
            val view = serviceView(s)
            ListRow(
                title = s.name,
                subtitle = s.command,
                subtitleMaxLines = 2,
                meta = { LocusChip(view.label, tone = view.tone, dot = s.running, contentDescription = "${s.name}: ${view.label}") },
                trailing = {
                    if (busy) {
                        InlineSpinner(contentDescription = "Working on ${s.name}")
                    } else {
                        val action = view.action
                        TextButton(
                            onClick = { if (action == ServiceAction.Stop) onStop(s.name) else onRestart(s.name) },
                            enabled = enabled,
                            modifier = Modifier.heightIn(min = Sizes.touchTarget).semantics { contentDescription = "${action.label} ${s.name}" },
                        ) { Text(action.label) }
                    }
                },
            )
            SwitchRow(
                title = "Start when Locus opens",
                subtitle = "Runs its command again each time you open Locus, after the app or the phone restarts.",
                checked = s.autostart,
                onCheckedChange = { onAutostart(s.name, it) },
                enabled = enabled,
                busy = autostartBusy == s.name,
            )
        }
    }
}

/** Why Android stops Linux in the background, and the switch that stops it (from #113's [AndroidKill]). */
@Composable
private fun PhantomHelpSheet(phantom: LinuxReport.Phantom?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    LocusSheet(title = "Android's background limit", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(AndroidKill.REASON, style = LocusType.rowSupporting)
            Text(AndroidKill.FIX, style = LocusType.rowSupporting)
            val state = when (phantom?.monitorOff) {
                true -> "On this phone the restriction is off."
                false -> "On this phone the restriction is on."
                null -> "This phone doesn't let Locus read the setting, so check it yourself."
            }
            SupportingText(state + if (phantom?.developerOptionsOn == false) " Developer options are off; on Android 14 and later, turning them off also turns the restriction back on." else "")
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(bottom = Spacing.xl),
            ) {
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

private const val CONFIRM_CLEAN = "clean"
private const val CONFIRM_REINSTALL = "reinstall"
