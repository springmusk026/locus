package dev.locus.feature.workspace.linux.health

import dev.locus.core.designsystem.AmountRow
import androidx.compose.foundation.lazy.itemsIndexed
import dev.locus.core.designsystem.cardSegment
import dev.locus.core.designsystem.SideOrBelow
import dev.locus.core.designsystem.SearchField
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.ErrorState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.linux.LinuxInventory
import dev.locus.core.linux.LinuxStatus

/**
 * Installed Linux packages (docs/design/linux-health.md#packages): "You installed" by default (world
 * without Alpine's own base), All as a filter, search, and updates on request. Rows are compact
 * (name, version, size); a tap expands one to its description and, for the user's own packages,
 * Uninstall behind a [ConfirmSheet].
 */
@Composable
fun LinuxPackagesRoute(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSetUpLinux: () -> Unit,
    viewModel: LinuxPackagesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val feedback = Locus.feedback
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        feedback.show(message.text, if (message.problem) Tone.Critical else Tone.Neutral)
        viewModel.messageShown()
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }
    val pkgs = state.packages

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                "Packages",
                onBack = onBack,
                subtitle = pkgs?.let { "${state.userCount} you installed · ${it.size} total" },
            )
        },
    ) { padding ->
        val body = Modifier.padding(padding)
        when {
            state.status != LinuxStatus.Ready -> EmptyState(
                title = "Linux isn't installed",
                body = "Packages show here once Linux is set up.",
                modifier = body,
                icon = LocusIcons.Terminal,
                action = if (state.status is LinuxStatus.Unavailable) null else {
                    { Button(onClick = onSetUpLinux, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Set up Linux") } }
                },
            )
            pkgs == null && state.error != null -> ErrorState("Couldn't list packages", state.error.orEmpty(), onRetry = viewModel::load, modifier = body, retrying = state.loading)
            pkgs == null -> LoadingState(body)
            else -> LazyColumn(
                body.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xxl),
            ) {
                item(key = "controls", contentType = "controls") {
                    Controls(state, viewModel::setQuery, viewModel::showAll)
                }
                item(key = "updates", contentType = "updates") {
                    Updates(state, viewModel::checkUpgrades, onOpenTerminal, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
                }
                if (state.shown.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Column(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            SupportingText(
                                when {
                                    state.query.isNotBlank() -> "No ${if (state.all) "" else "package you installed "}matches “${state.query.trim()}”."
                                    else -> "You haven't installed any packages yet. Ask the agent, or run `apk add <name>` in the terminal."
                                },
                            )
                            if (!state.all) TextButton(onClick = { viewModel.showAll(true) }, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Show all ${pkgs.size}") }
                        }
                    }
                } else {
                    val count = state.shown.size
                    itemsIndexed(state.shown, key = { _, p -> p.name }, contentType = { _, _ -> "package" }) { i, p ->
                        // One card, drawn a row at a time so the list stays lazy.
                        PackageRow(
                            p,
                            modifier = Modifier.padding(horizontal = Spacing.lg).cardSegment(i, count),
                            expanded = expanded == p.name,
                            removing = state.uninstalling == p.name,
                            removeEnabled = state.uninstalling == null,
                            onToggle = { expanded = if (expanded == p.name) null else p.name },
                            onRemove = { confirmRemove = p.name },
                        )
                    }
                }
            }
        }
    }

    confirmRemove?.let { name ->
        ConfirmSheet(
            title = "Uninstall $name?",
            message = "Runs `apk del $name`. Packages it pulled in that nothing else needs go too. Your workspace files stay, and you can install it again.",
            confirmLabel = "Uninstall",
            tone = Tone.Critical,
            onConfirm = { confirmRemove = null; viewModel.uninstall(name) },
            onDismiss = { confirmRemove = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(state: LinuxPackagesUiState, onQuery: (String) -> Unit, onAll: (Boolean) -> Unit) {
    val total = state.packages?.size ?: 0
    Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SearchField(
            query = state.query,
            onQueryChange = onQuery,
            placeholder = if (state.all) "Search all $total packages" else "Search packages you installed",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            LocusChip(
                "You installed (${state.userCount})",
                variant = ChipVariant.Filter,
                selected = !state.all,
                onClick = { onAll(false) },
                contentDescription = "Show packages you installed, ${state.userCount}",
            )
            LocusChip(
                "All ($total)",
                variant = ChipVariant.Filter,
                selected = state.all,
                onClick = { onAll(true) },
                contentDescription = "Show all packages, including dependencies, $total",
            )
        }
    }
}

@Composable
private fun Updates(state: LinuxPackagesUiState, onCheck: () -> Unit, onOpenTerminal: () -> Unit, modifier: Modifier) {
    val upgrades = state.upgrades
    when {
        state.checkingUpgrades -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            InlineSpinner(contentDescription = null)
            SupportingText("Checking Alpine's repositories for updates…")
        }
        state.upgradesError != null -> Banner(state.upgradesError, Tone.Caution, modifier, title = "Couldn't check for updates", actionsBelow = true) {
            TextButton(onClick = onCheck) { Text("Try again") }
        }
        // The button goes under the note when beside it the note would be a one-word column.
        upgrades == null -> SideOrBelow(modifier.fillMaxWidth(), main = {
            SupportingText("Updates are checked when you ask; it downloads Alpine's package index.")
        }) {
            OutlinedButton(onClick = onCheck, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Check for updates") }
        }
        upgrades.isEmpty() -> SupportingText("Everything is up to date.", modifier)
        else -> LocusCard(modifier) {
            Text("${upgrades.size} update${if (upgrades.size == 1) "" else "s"} available", style = LocusType.cardTitle)
            Column(Modifier.padding(vertical = Spacing.xs)) {
                upgrades.take(MAX_UPGRADES).forEach { AmountRow(it.name, "${it.installed} → ${it.available}", monoLabel = true) }
                if (upgrades.size > MAX_UPGRADES) SupportingText("and ${upgrades.size - MAX_UPGRADES} more")
            }
            SupportingText("Install them with `apk upgrade` in the terminal.")
            OutlinedButton(onClick = onOpenTerminal, modifier = Modifier.padding(top = Spacing.sm).heightIn(min = Sizes.touchTarget)) { Text("Open terminal") }
        }
    }
}

@Composable
private fun PackageRow(
    p: LinuxInventory.Package,
    expanded: Boolean,
    removing: Boolean,
    removeEnabled: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = LinuxHealth.bytes(p.installedBytes).takeIf { p.installedBytes > 0 }
    ListRow(
        title = p.name,
        subtitle = listOfNotNull(p.version, size).joinToString(" · "),
        modifier = modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        onClick = onToggle,
        trailing = { Icon(if (expanded) LocusIcons.Expand else LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        meta = if (!expanded) null else {
            {
                Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SupportingText(p.description.ifBlank { "No description." }, style = LocusType.rowSupporting)
                    SupportingText(
                        when {
                            p.userInstalled -> "Installed by you or the agent."
                            p.explicit -> "Part of Alpine itself."
                            else -> "Installed as a dependency of another package."
                        },
                    )
                    if (p.userInstalled) {
                        if (removing) {
                            InlineSpinner(contentDescription = "Uninstalling ${p.name}")
                        } else {
                            TextButton(
                                onClick = onRemove,
                                enabled = removeEnabled,
                                modifier = Modifier.heightIn(min = Sizes.touchTarget).semantics { contentDescription = "Uninstall ${p.name}" },
                            ) { Text("Uninstall", color = if (removeEnabled) Locus.colors.critical else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
    )
}

private const val MAX_UPGRADES = 12
