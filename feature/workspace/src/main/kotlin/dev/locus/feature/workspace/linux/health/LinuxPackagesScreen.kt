package dev.locus.feature.workspace.linux.health

import androidx.compose.ui.platform.LocalResources
import dev.locus.core.designsystem.asString
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import dev.locus.feature.workspace.R

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
    val resources = LocalResources.current
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        feedback.show(message.text.asString(resources), if (message.problem) Tone.Critical else Tone.Neutral)
        viewModel.messageShown()
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }
    val pkgs = state.packages

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                stringResource(R.string.workspace_packages_title),
                onBack = onBack,
                subtitle = pkgs?.let { stringResource(R.string.workspace_packages_subtitle, state.userCount, it.size) },
            )
        },
    ) { padding ->
        val body = Modifier.padding(padding)
        when {
            state.status != LinuxStatus.Ready -> EmptyState(
                title = stringResource(R.string.workspace_packages_not_installed_title),
                body = stringResource(R.string.workspace_packages_not_installed_body),
                modifier = body,
                icon = LocusIcons.Terminal,
                action = if (state.status is LinuxStatus.Unavailable) null else {
                    { Button(onClick = onSetUpLinux, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text(stringResource(R.string.workspace_packages_set_up)) } }
                },
            )
            pkgs == null && state.error != null -> ErrorState(stringResource(R.string.workspace_packages_error_title), state.error?.asString().orEmpty(), onRetry = viewModel::load, modifier = body, retrying = state.loading)
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
                                    state.query.isNotBlank() && state.all -> stringResource(R.string.workspace_packages_no_match_all, state.query.trim())
                                    state.query.isNotBlank() -> stringResource(R.string.workspace_packages_no_match_yours, state.query.trim())
                                    else -> stringResource(R.string.workspace_packages_none_installed)
                                },
                            )
                            if (!state.all) TextButton(onClick = { viewModel.showAll(true) }, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text(stringResource(R.string.workspace_packages_show_all, pkgs.size)) }
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
            title = stringResource(R.string.workspace_packages_uninstall_title, name),
            message = stringResource(R.string.workspace_packages_uninstall_message, name),
            confirmLabel = stringResource(R.string.workspace_packages_uninstall),
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
            placeholder = if (state.all) stringResource(R.string.workspace_packages_search_all, total) else stringResource(R.string.workspace_packages_search_yours),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            LocusChip(
                stringResource(R.string.workspace_packages_filter_yours, state.userCount),
                variant = ChipVariant.Filter,
                selected = !state.all,
                onClick = { onAll(false) },
                contentDescription = stringResource(R.string.workspace_packages_filter_yours_description, state.userCount),
            )
            LocusChip(
                stringResource(R.string.workspace_packages_filter_all, total),
                variant = ChipVariant.Filter,
                selected = state.all,
                onClick = { onAll(true) },
                contentDescription = stringResource(R.string.workspace_packages_filter_all_description, total),
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
            SupportingText(stringResource(R.string.workspace_packages_checking_updates))
        }
        state.upgradesError != null -> Banner(state.upgradesError, Tone.Caution, modifier, title = stringResource(R.string.workspace_packages_updates_error_title), actionsBelow = true) {
            TextButton(onClick = onCheck) { Text(stringResource(R.string.workspace_packages_try_again)) }
        }
        // The button goes under the note when beside it the note would be a one-word column.
        upgrades == null -> SideOrBelow(modifier.fillMaxWidth(), main = {
            SupportingText(stringResource(R.string.workspace_packages_updates_note))
        }) {
            OutlinedButton(onClick = onCheck, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text(stringResource(R.string.workspace_packages_check_updates)) }
        }
        upgrades.isEmpty() -> SupportingText(stringResource(R.string.workspace_packages_up_to_date), modifier)
        else -> LocusCard(modifier) {
            Text(pluralStringResource(R.plurals.workspace_packages_updates_available, upgrades.size, upgrades.size), style = LocusType.cardTitle)
            Column(Modifier.padding(vertical = Spacing.xs)) {
                upgrades.take(MAX_UPGRADES).forEach { AmountRow(it.name, "${it.installed} → ${it.available}", monoLabel = true) }
                if (upgrades.size > MAX_UPGRADES) SupportingText(stringResource(R.string.workspace_packages_updates_more, upgrades.size - MAX_UPGRADES))
            }
            SupportingText(stringResource(R.string.workspace_packages_updates_install))
            OutlinedButton(onClick = onOpenTerminal, modifier = Modifier.padding(top = Spacing.sm).heightIn(min = Sizes.touchTarget)) { Text(stringResource(R.string.workspace_packages_open_terminal)) }
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
    val rowState = stringResource(if (expanded) R.string.workspace_packages_expanded else R.string.workspace_packages_collapsed)
    ListRow(
        title = p.name,
        subtitle = listOfNotNull(p.version, size).joinToString(" · "),
        modifier = modifier.semantics { stateDescription = rowState },
        onClick = onToggle,
        trailing = { Icon(if (expanded) LocusIcons.Expand else LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        meta = if (!expanded) null else {
            {
                Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SupportingText(p.description.ifBlank { stringResource(R.string.workspace_packages_no_description) }, style = LocusType.rowSupporting)
                    SupportingText(
                        when {
                            p.userInstalled -> stringResource(R.string.workspace_packages_origin_user)
                            p.explicit -> stringResource(R.string.workspace_packages_origin_alpine)
                            else -> stringResource(R.string.workspace_packages_origin_dependency)
                        },
                    )
                    if (p.userInstalled) {
                        if (removing) {
                            InlineSpinner(contentDescription = stringResource(R.string.workspace_packages_uninstalling, p.name))
                        } else {
                            val uninstallDescription = stringResource(R.string.workspace_packages_uninstall_description, p.name)
                            TextButton(
                                onClick = onRemove,
                                enabled = removeEnabled,
                                modifier = Modifier.heightIn(min = Sizes.touchTarget).semantics { contentDescription = uninstallDescription },
                            ) { Text(stringResource(R.string.workspace_packages_uninstall), color = if (removeEnabled) Locus.colors.critical else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
    )
}

private const val MAX_UPGRADES = 12
