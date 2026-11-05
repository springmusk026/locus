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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                if (state.isNew) "New project" else "Edit project",
                onBack = onBack,
                // A project needs a name (its folder defaults from it): Save waits for one, and the field says so.
                actions = { TextButton(onClick = { viewModel.save(onSaved) }, enabled = !state.loading && state.name.isNotBlank()) { Text("Save") } },
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
            state.error?.let { error -> item(key = "error") { Banner(error, Tone.Critical, Modifier.padding(bottom = Spacing.sm)) } }
            item(key = "name") {
                OutlinedTextField(
                    state.name, viewModel::setName, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
                    supportingText = if (state.name.isBlank()) ({ Text(PROJECT_NAME_NEEDED) }) else null,
                )
            }
            item(key = "folder") {
                if (state.isNew) {
                    OutlinedTextField(
                        state.folder, viewModel::setFolder, Modifier.fillMaxWidth().padding(top = Spacing.sm),
                        label = { Text("Workspace folder") },
                        placeholder = { Text(dev.locus.core.model.ProjectPolicy.defaultFolder(state.name.ifBlank { "my project" })) },
                        singleLine = true,
                        supportingText = { Text("An existing folder or a new one. Chats in the project see only this folder. It can't be changed later.") },
                    )
                } else {
                    CardGroup(Modifier.padding(top = Spacing.sm)) {
                        ListRow(title = "Workspace folder", subtitle = state.folder, leading = { Icon(LocusIcons.Folder, null) })
                    }
                }
            }

            item(key = "instructions-label") { SectionLabel("Instructions") }
            item(key = "instructions") {
                // The card is the field, as for the other instructions and prompts in the app.
                LocusCard {
                    TextArea(
                        state.instructions, viewModel::setInstructions, size = TextAreaSize.Tall,
                        placeholder = "How to work in this project: goals, conventions, what to avoid. Like an AGENTS.md file.",
                    )
                }
            }
            item(key = "instructions-note") { SectionNote(NEW_CHATS_NOTE, Modifier.padding(top = Spacing.sm)) }

            item(key = "memory-label") { SectionLabel("Memory") }
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
                        MemoryScope.PROJECT_AND_GLOBAL -> "Chats read this project's memory first, then your general memory. What they learn is saved to the project."
                        MemoryScope.PROJECT_ONLY -> "Chats see only this project's memory and chats, nothing from outside it."
                    },
                    Modifier.padding(top = Spacing.sm),
                )
            }

            item(key = "model-label") { SectionLabel("Models") }
            item(key = "models") {
                CardGroup {
                    ProviderChoice("Default model", "Used by new chats in this project", state.providers, state.providerId, state.model) { id, m -> viewModel.setModel(id, m) }
                    GroupDivider()
                    ProviderChoice("Helper model", "Titles, suggestions and summaries for this project", state.providers, state.helperProviderId, state.helperModel) { id, m -> viewModel.setHelper(id, m) }
                }
            }

            // Where commands run: shown while creating too. Choosing a machine saves the new project first.
            item(key = "remote-label") { SectionLabel("Where commands run") }
            item(key = "remote") { RemoteBackendRow(state.remoteAlias, state.remoteFolder, onClick = viewModel::openRemoteSheet, busy = state.savingForRemote) }
            item(key = "remote-note") {
                SectionNote(
                    when {
                        state.remoteAlias != null -> "Files sync before and after each command. Applies at once, also to running chats; results say where they ran."
                        state.isNew -> "Long builds can run on one of your own machines over SSH instead. Choosing one saves the project first, " +
                            "so its folder exists to copy. Files are copied there; secrets never are."
                        else -> "Long builds can run on one of your own machines over SSH instead. Files are copied there; secrets never are."
                    },
                    Modifier.padding(top = Spacing.sm),
                )
            }
            if (!state.isNew) {
                item(key = "perm-label") { SectionLabel("Permissions") }
                item(key = "perm-note") {
                    SettingsLink(
                        SettingsDestination.Permissions,
                        Modifier.padding(bottom = Spacing.sm),
                        hint = "These can only make a tool stricter in this project; Allow needs your confirmation. Changes apply at once, " +
                            "also to running chats. Everywhere else, tools follow",
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
                                badge = if (changed > 0) "$changed set" else null,
                                badgeTone = Tone.Brand,
                            ) {
                                rows.forEach { row -> ToolPolicyRow(row, onSet = { p -> if (p == ToolPolicy.ALLOW) confirmWiden = row else viewModel.setToolPolicy(row.name, p) }) }
                            }
                        }
                    }
                }
                if (state.connectors.isNotEmpty()) {
                    item(key = "connectors-label") { SectionLabel("Connectors") }
                    item(key = "connectors") {
                        CardGroup {
                            state.connectors.forEachIndexed { i, c ->
                                if (i > 0) GroupDivider()
                                SwitchRow(
                                    title = c.name,
                                    checked = c.allowed,
                                    onCheckedChange = { viewModel.setConnectorAllowed(c.id, it) },
                                    subtitle = if (c.allowed) "Allowed in this project" else "Not used in this project",
                                )
                            }
                        }
                    }
                    item(key = "connectors-note") { SectionNote("New chats list only allowed connectors; a call to any other is refused.", Modifier.padding(top = Spacing.sm)) }
                }
                item(key = "delete") {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = Spacing.lg)) {
                        Text("Delete project", color = Locus.colors.critical)
                    }
                }
            } else {
                item(key = "perm-later") { SectionNote("Set permissions and connectors after saving.", Modifier.padding(top = Spacing.md)) }
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
            title = "Allow ${row.name} without asking?",
            message = "Everywhere else it's ${row.global.name.lowercase()}. In this project's chats it would run without asking you.",
            confirmLabel = "Allow in this project",
            tone = Tone.Caution,
            onConfirm = { viewModel.setToolPolicy(row.name, ToolPolicy.ALLOW, widen = true); confirmWiden = null },
            onDismiss = { confirmWiden = null },
        ) {
            SupportingText(row.description, style = LocusType.rowSupporting)
        }
    }
    if (confirmDelete) {
        ConfirmSheet(
            title = "Delete “${state.name}”?",
            message = "Its chats move out of the project and keep working as they are. Files in ${state.folder} stay. Its instructions and memory are kept in history.",
            confirmLabel = "Delete",
            tone = Tone.Critical,
            onConfirm = { confirmDelete = false; viewModel.delete(onDeleted) },
            onDismiss = { confirmDelete = false },
        )
    }
}

private const val NEW_CHATS_NOTE = "Instructions and memory apply to new chats; existing chats keep their settings."

/** "12 tools · 2 ask · 1 denied": how a permission group stands in this project. */
internal fun toolGroupSummary(rows: List<ProjectToolRow>): String = listOfNotNull(
    if (rows.size == 1) "1 tool" else "${rows.size} tools",
    rows.count { it.effective == ToolPolicy.ASK }.takeIf { it > 0 }?.let { "$it ask" },
    rows.count { it.effective == ToolPolicy.DENY }.takeIf { it > 0 }?.let { "$it denied" },
    rows.count { it.effective == ToolPolicy.ALLOW }.takeIf { it > 0 }?.let { "$it allowed" },
).joinToString(" · ")

private val POLICY_OPTIONS = listOf("Same", "Ask", "Deny", "Allow")

/** One tool: same as everywhere, or Ask / Deny / Allow in this project, with what applies. */
@Composable
private fun ToolPolicyRow(row: ProjectToolRow, onSet: (ToolPolicy?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(row.name, style = LocusType.rowSupporting)
        SupportingText("Everywhere: ${row.global.name.lowercase()} · here: ${row.effective.name.lowercase()}")
        val selected = when (row.own) {
            null -> 0
            ToolPolicy.ASK -> 1
            ToolPolicy.DENY -> 2
            ToolPolicy.ALLOW -> 3
        }
        Segmented(
            POLICY_OPTIONS, selected = selected, compact = true, modifier = Modifier.fillMaxWidth(),
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
        subtitle = if (name == null) "Same as everywhere · $hint" else listOf(name, model.ifBlank { "its default model" }).joinToString(" · "),
        onClick = { open = true },
    )
    if (open) {
        var typed by remember { mutableStateOf(model) }
        LocusSheet(title = title, subtitle = hint, onDismiss = { open = false }) {
            Column {
                ListRow(title = "Same as everywhere", onClick = { onChange(null, ""); open = false }, trailing = { if (providerId == null) Icon(LocusIcons.Check, contentDescription = "Selected") })
                providers.forEach { (id, label) ->
                    ListRow(
                        title = label,
                        modifier = Modifier.clickable { onChange(id, typed); open = false },
                        trailing = { if (providerId == id) Icon(LocusIcons.Check, contentDescription = "Selected") },
                    )
                }
                OutlinedTextField(
                    typed, { typed = it }, Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                    label = { Text("Model id (optional)") }, singleLine = true,
                    supportingText = { Text("Empty uses the provider's default. Pick the provider after typing.") },
                )
            }
        }
    }
}

/** Under an empty Name: what Save is waiting for. */
internal const val PROJECT_NAME_NEEDED = "Add a name to save the project."
