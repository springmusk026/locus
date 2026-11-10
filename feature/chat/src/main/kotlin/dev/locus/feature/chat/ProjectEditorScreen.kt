package dev.locus.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.TextArea
import dev.locus.core.designsystem.TextAreaSize
import dev.locus.core.model.SettingsDestination
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.accentFor
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.ToolPolicy

/**
 * Create or edit a project: name, folder (fixed once made), instructions, memory scope, models and
 * permissions. Everything that goes into a chat's prompt applies to new chats only, and says so.
 */
@Composable
fun ProjectEditorRoute(
    projectId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val viewModel = hiltViewModel<ProjectEditorViewModel, ProjectEditorViewModel.Factory>(key = "project-editor-${projectId ?: "new"}") { it.create(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmWiden by remember { mutableStateOf<ProjectToolRow?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                stringResource(if (state.isNew) R.string.chat_projecteditor_new else R.string.chat_projecteditor_edit),
                onBack = onBack,
                // A project needs a name (its folder defaults from it): Save waits for one, and the field says so.
                actions = { TextButton(onClick = { viewModel.save(onSaved) }, enabled = !state.loading && state.name.isNotBlank()) { Text(stringResource(R.string.chat_projecteditor_save)) } },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.xs, bottom = padding.calculateBottomPadding() + Spacing.xxl),
        ) {
            state.error?.let { error -> item(key = "error") { Banner(error.asString(), Tone.Critical, Modifier.padding(bottom = Spacing.sm)) } }
            item(key = "name") {
                OutlinedTextField(
                    state.name, viewModel::setName, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.chat_projecteditor_name)) }, singleLine = true,
                    supportingText = if (state.name.isBlank()) ({ Text(stringResource(R.string.chat_projecteditor_name_needed)) }) else null,
                )
            }
            item(key = "folder") {
                if (state.isNew) {
                    OutlinedTextField(
                        state.folder, viewModel::setFolder, Modifier.fillMaxWidth().padding(top = Spacing.sm),
                        label = { Text(stringResource(R.string.chat_projecteditor_folder)) },
                        placeholder = { Text(dev.locus.core.model.ProjectPolicy.defaultFolder(state.name.ifBlank { "my project" })) },
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.chat_projecteditor_folder_help)) },
                    )
                } else {
                    CardGroup(Modifier.padding(top = Spacing.sm)) {
                        ListRow(title = stringResource(R.string.chat_projecteditor_folder), subtitle = state.folder, leading = { Icon(LocusIcons.Folder, null) })
                    }
                }
            }

            item(key = "instructions-label") { SectionLabel(stringResource(R.string.chat_projecteditor_instructions)) }
            item(key = "instructions") {
                // The card is the field, as for the other instructions and prompts in the app.
                LocusCard {
                    TextArea(
                        state.instructions, viewModel::setInstructions, size = TextAreaSize.Tall,
                        placeholder = stringResource(R.string.chat_projecteditor_instructions_hint),
                    )
                }
            }
            item(key = "instructions-note") { SectionNote(stringResource(R.string.chat_projecteditor_new_chats_note), Modifier.padding(top = Spacing.sm)) }

            item(key = "memory-label") { SectionLabel(stringResource(R.string.chat_projecteditor_memory)) }
            item(key = "memory") {
                Segmented(
                    MemoryScope.entries.map { it.label },
                    selected = state.memoryScope.ordinal,
                    onSelect = { viewModel.setMemoryScope(MemoryScope.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "memory-note") {
                SectionNote(
                    when (state.memoryScope) {
                        MemoryScope.PROJECT_AND_GLOBAL -> stringResource(R.string.chat_projecteditor_memory_both)
                        MemoryScope.PROJECT_ONLY -> stringResource(R.string.chat_projecteditor_memory_only)
                    },
                    Modifier.padding(top = Spacing.sm),
                )
            }

            item(key = "model-label") { SectionLabel(stringResource(R.string.chat_projecteditor_models)) }
            item(key = "models") {
                CardGroup {
                    ProviderChoice(stringResource(R.string.chat_projecteditor_default_model), stringResource(R.string.chat_projecteditor_default_model_hint), state.providers, state.providerId, state.model) { id, m -> viewModel.setModel(id, m) }
                    GroupDivider()
                    ProviderChoice(stringResource(R.string.chat_projecteditor_helper_model), stringResource(R.string.chat_projecteditor_helper_model_hint), state.providers, state.helperProviderId, state.helperModel) { id, m -> viewModel.setHelper(id, m) }
                }
            }

            // Where commands run: shown while creating too. Choosing a machine saves the new project first.
            item(key = "remote-label") { SectionLabel(stringResource(R.string.chat_projecteditor_where)) }
            item(key = "remote") { RemoteBackendRow(state.remoteAlias, state.remoteFolder, onClick = viewModel::openRemoteSheet, busy = state.savingForRemote) }
            item(key = "remote-note") {
                SectionNote(
                    when {
                        state.remoteAlias != null -> stringResource(R.string.chat_projecteditor_where_linked)
                        state.isNew -> stringResource(R.string.chat_projecteditor_where_new)
                        else -> stringResource(R.string.chat_projecteditor_where_existing)
                    },
                    Modifier.padding(top = Spacing.sm),
                )
            }
            if (!state.isNew) {
                item(key = "perm-label") { SectionLabel(stringResource(R.string.chat_projecteditor_permissions)) }
                item(key = "perm-note") {
                    SettingsLink(
                        SettingsDestination.Permissions,
                        Modifier.padding(bottom = Spacing.sm),
                        hint = stringResource(R.string.chat_projecteditor_permissions_hint),
                    )
                }
                item(key = "tools") {
                    val groups = state.tools.groupBy { accentFor(it.name).first }.toSortedMap(compareBy { it.ordinal })
                    CardGroup {
                        groups.entries.forEachIndexed { i, (accent, rows) ->
                            if (i > 0) GroupDivider()
                            val changed = rows.count { it.own != null }
                            ExpandableRow(
                                title = accent.name.replace('_', ' '),
                                // What applies here in words; each tool is named when the group opens.
                                subtitle = toolGroupSummary(rows),
                                icon = accentFor(rows.first().name).second,
                                badge = if (changed > 0) stringResource(R.string.chat_projecteditor_set, changed) else null,
                                badgeTone = Tone.Brand,
                            ) {
                                rows.forEach { row -> ToolPolicyRow(row, onSet = { p -> if (p == ToolPolicy.ALLOW) confirmWiden = row else viewModel.setToolPolicy(row.name, p) }) }
                            }
                        }
                    }
                }
                if (state.connectors.isNotEmpty()) {
                    item(key = "connectors-label") { SectionLabel(stringResource(R.string.chat_projecteditor_connectors)) }
                    item(key = "connectors") {
                        CardGroup {
                            state.connectors.forEachIndexed { i, c ->
                                if (i > 0) GroupDivider()
                                SwitchRow(
                                    title = c.name,
                                    checked = c.allowed,
                                    onCheckedChange = { viewModel.setConnectorAllowed(c.id, it) },
                                    subtitle = stringResource(if (c.allowed) R.string.chat_projecteditor_connector_allowed else R.string.chat_projecteditor_connector_denied),
                                )
                            }
                        }
                    }
                    item(key = "connectors-note") { SectionNote(stringResource(R.string.chat_projecteditor_connectors_note), Modifier.padding(top = Spacing.sm)) }
                }
                item(key = "delete") {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = Spacing.lg)) {
                        Text(stringResource(R.string.chat_projecteditor_delete), color = Locus.colors.critical)
                    }
                }
            } else {
                item(key = "perm-later") { SectionNote(stringResource(R.string.chat_projecteditor_later), Modifier.padding(top = Spacing.md)) }
            }
        }
    }

    state.remoteSheet?.let { step ->
        RemoteBackendSheet(
            step, state.remoteAlias, state.remoteHosts,
            onDismiss = viewModel::closeRemoteSheet,
            onChoosePhone = viewModel::choosePhone,
            onChooseHost = viewModel::chooseHost,
            onFolder = viewModel::setRemoteFolder,
            onPreview = viewModel::previewRemote,
            onLink = viewModel::linkRemote,
            onUnlink = viewModel::unlink,
            onCancelLink = viewModel::cancelLink,
        )
    }

    confirmWiden?.let { row ->
        ConfirmSheet(
            title = stringResource(R.string.chat_projecteditor_allow_title, row.name),
            message = stringResource(R.string.chat_projecteditor_allow_body, row.global.name.lowercase()),
            confirmLabel = stringResource(R.string.chat_projecteditor_allow_confirm),
            tone = Tone.Caution,
            onConfirm = { viewModel.setToolPolicy(row.name, ToolPolicy.ALLOW, widen = true); confirmWiden = null },
            onDismiss = { confirmWiden = null },
        ) {
            SupportingText(row.description, style = LocusType.rowSupporting)
        }
    }
    if (confirmDelete) {
        ConfirmSheet(
            title = stringResource(R.string.chat_projecteditor_delete_title, state.name),
            message = stringResource(R.string.chat_projecteditor_delete_body, state.folder),
            confirmLabel = stringResource(R.string.chat_projecteditor_delete_confirm),
            tone = Tone.Critical,
            onConfirm = { confirmDelete = false; viewModel.delete(onDeleted) },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** "12 tools · 2 ask · 1 denied": how a permission group stands in this project. */
@Composable
internal fun toolGroupSummary(rows: List<ProjectToolRow>): String = listOfNotNull(
    pluralStringResource(R.plurals.chat_projecteditor_tools, rows.size, rows.size),
    rows.count { it.effective == ToolPolicy.ASK }.takeIf { it > 0 }?.let { stringResource(R.string.chat_projecteditor_n_ask, it) },
    rows.count { it.effective == ToolPolicy.DENY }.takeIf { it > 0 }?.let { stringResource(R.string.chat_projecteditor_n_denied, it) },
    rows.count { it.effective == ToolPolicy.ALLOW }.takeIf { it > 0 }?.let { stringResource(R.string.chat_projecteditor_n_allowed, it) },
).joinToString(" · ")

/** One tool: same as everywhere, or Ask / Deny / Allow in this project, with what applies. */
@Composable
private fun ToolPolicyRow(row: ProjectToolRow, onSet: (ToolPolicy?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(row.name, style = LocusType.rowSupporting)
        SupportingText(stringResource(R.string.chat_projecteditor_policy, row.global.name.lowercase(), row.effective.name.lowercase()))
        val selected = when (row.own) {
            null -> 0
            ToolPolicy.ASK -> 1
            ToolPolicy.DENY -> 2
            ToolPolicy.ALLOW -> 3
        }
        Segmented(
            listOf(
                stringResource(R.string.chat_projecteditor_policy_same),
                stringResource(R.string.chat_projecteditor_policy_ask),
                stringResource(R.string.chat_projecteditor_policy_deny),
                stringResource(R.string.chat_projecteditor_policy_allow),
            ),
            selected = selected, compact = true, modifier = Modifier.fillMaxWidth(),
            onSelect = { i -> onSet(listOf(null, ToolPolicy.ASK, ToolPolicy.DENY, ToolPolicy.ALLOW)[i]) },
        )
    }
}

/** Choose a provider (or the global default) and optionally a model id. */
@Composable
private fun ProviderChoice(
    title: String,
    hint: String,
    providers: List<Pair<String, String>>,
    providerId: String?,
    model: String,
    onChange: (String?, String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val name = providers.firstOrNull { it.first == providerId }?.second
    ListRow(
        title = title,
        subtitleMaxLines = 2,
        subtitle = if (name == null) stringResource(R.string.chat_projecteditor_same_hint, hint) else listOf(name, model.ifBlank { stringResource(R.string.chat_projecteditor_its_default) }).joinToString(" · "),
        onClick = { open = true },
    )
    if (open) {
        var typed by remember { mutableStateOf(model) }
        LocusSheet(title = title, subtitle = hint, onDismiss = { open = false }) {
            Column {
                ListRow(title = stringResource(R.string.chat_projecteditor_same), onClick = { onChange(null, ""); open = false }, trailing = { if (providerId == null) Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_remote_selected)) })
                providers.forEach { (id, label) ->
                    ListRow(
                        title = label,
                        modifier = Modifier.clickable { onChange(id, typed); open = false },
                        trailing = { if (providerId == id) Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_remote_selected)) },
                    )
                }
                OutlinedTextField(
                    typed, { typed = it }, Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                    label = { Text(stringResource(R.string.chat_projecteditor_model_id)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.chat_projecteditor_model_id_help)) },
                )
            }
        }
    }
}
