package dev.locus.feature.library

import dev.locus.core.designsystem.SecretContent
import dev.locus.core.designsystem.asString
import androidx.compose.ui.platform.LocalResources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.data.mcp.SavedKeys
import dev.locus.core.data.repository.ConnectorEntry
import dev.locus.core.data.repository.KeyState
import dev.locus.core.data.repository.Library
import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.rememberLocalNetworkGate
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusScreen
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Tone
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.ConnectorIcon
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.agent.home.Connector
import dev.locus.core.data.repository.ProjectMemory
import androidx.compose.foundation.layout.Box

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoute(
    onBack: () -> Unit,
    initialTab: Int = 0,
    /** A template id from the catalogue to open right away (Settings → Integrations → Use the official MCP server). */
    initialTemplate: String? = null,
    /** Opens a chat (a workflow run from Library starts one). */
    onOpenChat: (String) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(if (initialTemplate != null) 2 else initialTab) }
    // Where Add an MCP server is: null (closed), the picker, the custom form, or a template's id.
    var addingMcp by rememberSaveable {
        mutableStateOf(
            when {
                initialTemplate == dev.locus.core.data.connectors.ConnectorTemplates.PICKER -> McpStep.SERVICES
                initialTemplate != null && McpTemplates.byId(initialTemplate) != null -> McpStep.template(initialTemplate)
                else -> null
            },
        )
    }

    val library = (state as? LibraryUiState.Ready)?.library
    val pendingSkills = library?.skills?.count { it.draft } ?: 0
    val toolChanges by viewModel.pendingTools.collectAsStateWithLifecycle()
    val pendingConnectors = library?.connectors?.count { it.draft || toolChanges[it.connector.id].orEmpty().isNotEmpty() } ?: 0
    val pendingAgents = library?.agents?.count { it.draft } ?: 0
    LocusScreen(stringResource(R.string.library_title), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
        // Plain tab names fit at 360dp; what waits for review is said once, under the tabs.
        Segmented(
            listOf(
                stringResource(R.string.library_tab_memory),
                stringResource(R.string.library_tab_skills),
                stringResource(R.string.library_tab_connectors),
                stringResource(R.string.library_tab_helpers),
            ),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            selectedTint = { i ->
                if ((i == 1 && pendingSkills > 0) || (i == 2 && pendingConnectors > 0) || (i == 3 && pendingAgents > 0)) Locus.colors.onCautionContainer else null
            },
        )
        pendingReviewLine(pendingSkills, pendingConnectors, pendingAgents)?.let {
            Text(it, Modifier.padding(start = Spacing.lg + Spacing.lg, end = Spacing.lg, bottom = Spacing.xs), style = LocusType.supporting, color = Locus.colors.onCautionContainer)
        }
        val modifier = Modifier
        when (val s = state) {
            LibraryUiState.Loading -> LoadingState(modifier)
            is LibraryUiState.Failed -> EmptyState(
                stringResource(R.string.library_load_failed_title),
                s.message.asString(),
                modifier,
                icon = LocusIcons.Library,
                action = { Button(onClick = viewModel::retryLoad) { Text(stringResource(R.string.library_action_try_again)) } },
            )
            is LibraryUiState.Ready -> Column(modifier.fillMaxSize()) {
                val error by viewModel.error.collectAsStateWithLifecycle()
                error?.let { ActionErrorBanner(it, onDismiss = viewModel::dismissError) }
                Box(Modifier.weight(1f)) {
                    val library = s.library
                    when (tab) {
                        0 -> MemoryTab(library, viewModel, Modifier, onOpenChat = onOpenChat)
                        1 -> SkillsTab(library, viewModel, Modifier, onOpenChat = onOpenChat)
                        2 -> ConnectorsTab(library, viewModel, Modifier, addingMcp, onAddingMcp = { addingMcp = it })
                        else -> AgentsTab(library, viewModel, Modifier)
                    }
                }
            }
        }
    }    }
}

/** "To review: 1 skill, 2 connectors", or null when nothing waits. */
@Composable
internal fun pendingReviewLine(skills: Int, connectors: Int, helpers: Int): String? {
    val parts = listOfNotNull(
        skills.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.library_review_skills, it, it) },
        connectors.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.library_review_connectors, it, it) },
        helpers.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.library_review_helpers, it, it) },
    )
    return if (parts.isEmpty()) null else stringResource(R.string.library_review_line, parts.joinToString(", "))
}

/** A Library action that failed: what and why, with Try again when it can be retried. */
@Composable
private fun ActionErrorBanner(error: LibraryError, onDismiss: () -> Unit) {
    val message = error.message.asString()
    Banner(
        message,
        Tone.Critical,
        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        title = error.title.asString(),
        actionsBelow = dev.locus.core.designsystem.hasSettingsAction(message),
    ) {
        // "… a GitHub token in Settings → Integrations → GitHub": a button that opens it.
        dev.locus.core.designsystem.SettingsActionFor(message)
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_action_dismiss)) }
        error.retry?.let { retry -> TextButton(onClick = retry) { Text(stringResource(R.string.library_action_try_again)) } }
    }
}

internal val listPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xxl)

/** A memory topic and where it's saved: general memory ([project] null) or a project's own. */
private data class MemoryTarget(val topic: AgentHome.MemoryTopic, val project: ProjectMemory? = null)

@Composable
private fun MemoryTab(library: Library, viewModel: LibraryViewModel, modifier: Modifier, onOpenChat: (String) -> Unit) {
    var editing by remember { mutableStateOf<MemoryTarget?>(null) }
    var forgetting by remember { mutableStateOf<MemoryTarget?>(null) }
    var moving by remember { mutableStateOf<MemoryTarget?>(null) }
    if (library.memory.isEmpty() && library.projectMemory.isEmpty()) {
        EmptyState(
            stringResource(R.string.library_memory_empty_title),
            stringResource(R.string.library_memory_empty_body),
            modifier,
            icon = LocusIcons.Note,
        )
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = listPadding) {
            item { SectionNote(stringResource(R.string.library_memory_intro)) }
            if (library.projectMemory.isNotEmpty()) item { SectionLabel(stringResource(R.string.library_memory_general_label)) }
            item {
                if (library.memory.isEmpty()) {
                    SectionNote(stringResource(R.string.library_memory_general_empty))
                } else {
                    MemoryCard(library.memory.map { MemoryTarget(it) }, library, onOpenChat, onEdit = { editing = it }, onForget = { forgetting = it }, onMove = null)
                }
            }
            library.projectMemory.forEach { project ->
                item(key = "project-${project.projectId}") {
                    Column {
                        SectionLabel(project.projectName)
                        SectionNote(stringResource(R.string.library_memory_project_note))
                        MemoryCard(project.topics.map { MemoryTarget(it, project) }, library, onOpenChat, onEdit = { editing = it }, onForget = { forgetting = it }, onMove = { moving = it })
                    }
                }
            }
        }
    }
    editing?.let { target ->
        val topic = target.topic
        var summary by remember(target) { mutableStateOf(topic.summary) }
        var content by remember(target) { mutableStateOf(topic.content) }
        LocusSheet(
            title = topic.name.replace('-', ' '),
            subtitle = target.project?.let { stringResource(R.string.library_memory_edit_subtitle_project, it.projectName) }
                ?: stringResource(R.string.library_memory_edit_subtitle),
            onDismiss = { editing = null },
            action = stringResource(R.string.library_action_save),
            actionEnabled = content.isNotBlank(),
            onAction = { viewModel.saveMemory(topic.name, summary, content, target.project?.projectId); editing = null },
        ) {
            OutlinedTextField(summary, { summary = it }, label = { Text(stringResource(R.string.library_memory_summary_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(content, { content = it }, label = { Text(stringResource(R.string.library_memory_note_label)) }, minLines = 5, modifier = Modifier.fillMaxWidth())
        }
    }
    forgetting?.let { target ->
        ConfirmRemove(
            title = stringResource(R.string.library_memory_forget_title, target.topic.name.replace('-', ' ')),
            body = target.project?.let { stringResource(R.string.library_memory_forget_body_project, it.projectName) }
                ?: stringResource(R.string.library_memory_forget_body),
            action = stringResource(R.string.library_memory_forget),
            onDismiss = { forgetting = null },
            onConfirm = { viewModel.forgetMemory(target.topic.name, target.project?.projectId); forgetting = null },
        )
    }
    moving?.let { target ->
        val project = target.project ?: return@let
        val replaces = library.memory.any { it.name == target.topic.name }
        LocusSheet(
            title = stringResource(R.string.library_memory_move_title, target.topic.name.replace('-', ' ')),
            subtitle = stringResource(if (replaces) R.string.library_memory_move_body_replaces else R.string.library_memory_move_body, project.projectName),
            onDismiss = { moving = null },
            action = stringResource(R.string.library_memory_move),
            actionTone = if (replaces) Tone.Caution else Tone.Brand,
            onAction = { viewModel.moveMemoryToGeneral(project.projectId, target.topic.name); moving = null },
        ) {}
    }
}

/** One memory's topics in a card: each opens to its note, where it came from, and its actions. */
@Composable
private fun MemoryCard(
    topics: List<MemoryTarget>,
    library: Library,
    onOpenChat: (String) -> Unit,
    onEdit: (MemoryTarget) -> Unit,
    onForget: (MemoryTarget) -> Unit,
    /** Null for general memory, which has nowhere to move to. */
    onMove: ((MemoryTarget) -> Unit)?,
) {
    val resources = LocalResources.current
    CardGroup {
        topics.forEachIndexed { i, target ->
            if (i > 0) GroupDivider(inset = Insets.iconRow)
            val topic = target.topic
            val origin = topic.provenance?.let { memoryOriginView(resources, it, it.chatId?.let(library.memoryChats::get)) }
            ExpandableRow(
                title = topic.name.replace('-', ' '),
                subtitle = topic.summary.ifBlank { stringResource(R.string.library_memory_no_summary) },
                icon = LocusIcons.Note,
                iconDescription = stringResource(if (target.project != null) R.string.library_memory_icon_project_note else R.string.library_memory_icon_note),
                // Only the time fits beside the title; who saved it is in the open row.
                meta = relativeTime(topic.modified),
                badge = origin?.warning?.let { stringResource(R.string.library_memory_needs_look) },
                expandKey = null,
            ) {
                origin?.let { MemoryOrigin(it, onOpenChat) }
                SelectionContainer { Text(topic.content, style = LocusType.rowSupporting) }
                // Actions wrap to a second line at a large font instead of running off the card.
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    onMove?.let { move -> TextButton(onClick = { move(target) }) { Text(stringResource(R.string.library_memory_move_to_general)) } }
                    TextButton(onClick = { onEdit(target) }) { Text(stringResource(R.string.library_action_edit)) }
                    TextButton(onClick = { onForget(target) }) { Text(stringResource(R.string.library_memory_forget), color = Locus.colors.critical) }
                }
            }
        }
    }
}

/**
 * Who saved a memory topic, when, and in which chat (tap to open it); a note saved while reading
 * outside content is flagged for checking.
 */
@Composable
private fun MemoryOrigin(origin: MemoryOriginView, onOpenChat: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        SupportingText(stringResource(R.string.library_memory_origin, origin.who, origin.date))
        origin.chat?.let { chat ->
            Text(
                stringResource(R.string.library_memory_from_chat, chat.title),
                Modifier.clickable(onClickLabel = stringResource(R.string.library_memory_open_chat)) { onOpenChat(chat.id) }.padding(vertical = Spacing.xs),
                style = LocusType.supporting,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (origin.chatGone) {
            SupportingText(stringResource(R.string.library_memory_chat_deleted))
        }
        origin.warning?.let { Text(it, style = LocusType.supporting, color = Locus.colors.onCautionContainer) }
    }
}

@Composable
private fun ConnectorsTab(
    library: Library,
    viewModel: LibraryViewModel,
    modifier: Modifier,
    addingMcp: String?,
    onAddingMcp: (String?) -> Unit,
) {
    /** The connector and the vault secret name being set. */
    var secretFor by remember { mutableStateOf<Pair<ConnectorEntry, String>?>(null) }
    var removing by remember { mutableStateOf<ConnectorEntry?>(null) }
    /** The connector whose tool changes are being reviewed. */
    var reviewingTools by rememberSaveable { mutableStateOf<String?>(null) }
    /** The Home Assistant server whose always-confirm rules are being edited. */
    var editingRules by rememberSaveable { mutableStateOf<String?>(null) }
    val setup by viewModel.serviceSetup.collectAsStateWithLifecycle()
    val signIn by viewModel.signInStatus.collectAsStateWithLifecycle()
    val toolChanges by viewModel.pendingTools.collectAsStateWithLifecycle()
    // Look for tool changes on the remote servers already in use (their sign-in or key in place). Local
    // servers aren't started just for this; theirs show once a chat lists them.
    androidx.compose.runtime.LaunchedEffect(library.connectors) {
        viewModel.checkMcpTools(
            library.connectors.filter { it.connector.kind == "mcp" && it.connector.local == null && !it.draft && (it.hasSecret || it.connector.auth.type == "none") }
                .map { it.connector.id },
        )
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = listPadding) {
        signIn?.let { status ->
            item {
                when (status) {
                    is SignInStatus.Working -> Banner(
                        stringResource(R.string.library_connectors_signing_in_body, status.label),
                        Tone.Live,
                        Modifier.padding(bottom = Spacing.md),
                        title = stringResource(R.string.library_connectors_signing_in_title),
                    ) { TextButton(onClick = viewModel::cancelSignIn) { Text(stringResource(R.string.library_action_cancel)) } }
                    is SignInStatus.Failed -> Banner(status.message.asString(), Tone.Critical, Modifier.padding(bottom = Spacing.md), title = status.title.asString()) {
                        TextButton(onClick = viewModel::dismissSignIn) { Text(stringResource(R.string.library_action_ok)) }
                    }
                }
            }
        }
        item {
            SectionNote(stringResource(R.string.library_connectors_intro))
        }
        item { CardGroup {
            library.connectors.forEachIndexed { i, entry ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                val c = entry.connector
                if (c.local != null) {
                    LocalMcpRow(
                        entry,
                        onAddSecret = { name -> secretFor = entry to name },
                        onStart = { viewModel.startLocalMcp(c.id) },
                        onStop = { viewModel.stopLocalMcp(c.id) },
                        onApprove = { viewModel.approveConnector(c.id) },
                        onReject = { viewModel.rejectConnector(c.id) },
                        onRemove = { removing = entry },
                        toolChanges = toolChanges[c.id].orEmpty(),
                        onReviewTools = { reviewingTools = c.id },
                    )
                    return@forEachIndexed
                }
                ExpandableRow(
                    title = c.name,
                    subtitle = if (c.kind == "mcp") stringResource(R.string.library_connectors_subtitle_mcp, c.host)
                    else pluralStringResource(R.plurals.library_connectors_subtitle_operations, c.operations.size, c.host, c.operations.size),
                    leading = { ConnectorTile(c) },
                    badge = when {
                        entry.draft -> stringResource(R.string.library_badge_needs_review)
                        toolChanges[c.id].orEmpty().isNotEmpty() -> toolChangesBadge(toolChanges[c.id].orEmpty())
                        !entry.hasSecret && c.auth.type == "oauth" -> stringResource(R.string.library_connectors_badge_sign_in)
                        entry.key == KeyState.Unconfirmed -> stringResource(R.string.library_connectors_badge_confirm_key)
                        !entry.hasSecret -> stringResource(R.string.library_connectors_badge_needs_key)
                        else -> null
                    },
                    startExpanded = entry.draft || toolChanges[c.id].orEmpty().isNotEmpty(),
                ) {
                    toolChanges[c.id]?.takeIf { it.isNotEmpty() }?.let { pending -> ToolChangesBanner(pending, onReview = { reviewingTools = c.id }) }
                    Text(c.description, style = LocusType.rowSupporting)
                    SupportingText(stringResource(R.string.library_connectors_only_talks_to, c.host))
                    c.operations.forEach { op ->
                        Text(
                            stringResource(
                                when (op.sideEffect) {
                                    "read" -> R.string.library_connectors_op_read
                                    "destructive" -> R.string.library_connectors_op_destructive
                                    "outward" -> R.string.library_connectors_op_outward
                                    else -> R.string.library_connectors_op_writes
                                },
                                op.method.uppercase(), op.path, op.name,
                            ),
                            style = CodeTextStyle,
                        )
                    }
                    connectorKeyNote(LocalResources.current, entry)?.let { Text(it, style = LocusType.supporting) }
                    // Actions wrap to a second line at a large font instead of running off the card.
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (c.auth.type == "oauth") {
                            if (!entry.draft) TextButton(onClick = { viewModel.signIn(c.id, c.name) }) { Text(stringResource(if (entry.hasSecret) R.string.library_connectors_sign_in_again else R.string.library_connectors_sign_in)) }
                        } else if (c.auth.type != "none") {
                            if (entry.key == KeyState.Unconfirmed) TextButton(onClick = { viewModel.confirmKey(c.id) }) { Text(stringResource(R.string.library_connectors_use_for_host, c.host)) }
                            val fromTemplate = dev.locus.core.data.connectors.ConnectorTemplates.byId(c.template)?.takeIf { c.kind == "http" && !entry.draft }
                            TextButton(onClick = { if (fromTemplate != null) onAddingMcp(McpStep.service(fromTemplate.id, fromTemplate.addressOf(c.baseUrl))) else secretFor = entry to c.auth.secret }) {
                                Text(stringResource(if (entry.hasSecret || entry.key == KeyState.Unconfirmed) R.string.library_connectors_replace_key else R.string.library_connectors_add_key))
                            }
                        }
                        if (!entry.draft && dev.locus.core.data.mcp.HomeAssistantSafety.applies(c)) {
                            TextButton(onClick = { editingRules = c.id }) { Text(stringResource(R.string.library_connectors_what_always_asks)) }
                        }
                        if (entry.draft) {
                            OutlinedButton(onClick = { viewModel.rejectConnector(c.id) }) { Text(stringResource(R.string.library_action_reject)) }
                            Button(onClick = { viewModel.approveConnector(c.id) }) { Text(stringResource(R.string.library_action_approve)) }
                        } else {
                            TextButton(onClick = { removing = entry }) { Text(stringResource(R.string.library_action_remove), color = Locus.colors.critical) }
                        }
                    }
                }
            }
            if (library.connectors.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
            AddRow(stringResource(R.string.library_connectors_add_service), LocusIcons.Add) { onAddingMcp(McpStep.SERVICES) }
            GroupDivider(inset = Insets.iconRow)
            AddRow(stringResource(R.string.library_connectors_add_mcp), LocusIcons.Add) { onAddingMcp(McpStep.PICK) }
        } }
    }
    // Outside the sheet: the sheet closes on Add while the permission question may still be open.
    val localNetwork = rememberLocalNetworkGate()
    if (addingMcp == McpStep.PICK) {
        McpTemplatePicker(
            onPick = { onAddingMcp(McpStep.template(it.id)) },
            onCustom = { onAddingMcp(McpStep.CUSTOM) },
            onDismiss = { onAddingMcp(null) },
        )
    }
    if (addingMcp == McpStep.SERVICES) {
        ServicePicker(
            onPickService = { viewModel.resetServiceSetup(); onAddingMcp(McpStep.service(it.id)) },
            onPickMcp = { viewModel.resetServiceSetup(); onAddingMcp(McpStep.template(it.id)) },
            onDismiss = { onAddingMcp(null) },
        )
    }
    McpStep.serviceId(addingMcp)?.let { (id, address) ->
        dev.locus.core.data.connectors.ConnectorTemplates.byId(id)?.let { template ->
            ServiceTemplateSheet(
                template,
                setup = setup,
                localNetwork = localNetwork,
                initialAddress = address,
                onTest = { a, u, k -> viewModel.testService(template, a, u, k) },
                onAdd = { a, u, k -> viewModel.addService(template, a, u, k) },
                onEdited = { if (setup.result != null || setup.error != null) viewModel.resetServiceSetup() },
                onDismiss = { viewModel.resetServiceSetup(); onAddingMcp(null) },
            )
        }
    }
    McpStep.templateId(addingMcp)?.let(McpTemplates::byId)?.takeIf { it.selfHosted != null }?.let { template ->
        HomeAssistantSheet(
            template,
            setup = setup,
            localNetwork = localNetwork,
            onTest = { a, t -> viewModel.testHomeAssistant(a, t) },
            onAdd = { a, full, t, rules -> viewModel.addHomeAssistant(a, full, t, rules) },
            onEdited = { if (setup.result != null || setup.error != null) viewModel.resetServiceSetup() },
            onDismiss = { viewModel.resetServiceSetup(); onAddingMcp(null) },
        )
    }
    editingRules?.let { id ->
        library.connectors.firstOrNull { it.connector.id == id }?.connector?.let { c ->
            HomeRulesSheet(c, onSave = { viewModel.setHomeAssistantRules(id, it); editingRules = null }, onDismiss = { editingRules = null })
        } ?: run { editingRules = null }
    }
    McpStep.templateId(addingMcp)?.let(McpTemplates::byId)?.takeIf { it.selfHosted == null }?.let { template ->
        McpTemplateSheet(
            template,
            linuxInstalled = library.linuxInstalled,
            // Only the name of a built-in whose saved key fits this server; the key itself stays in the vault.
            savedKeyFrom = SavedKeys.source(template)?.takeIf { it.id in library.savedIntegrationKeys },
            onAdd = { form ->
                val (url, key, env) = Triple(form.url, form.key, form.envPairs())
                val saved = form.useSavedKey
                // Adding connects right away (to sign in), so a local-network address asks first.
                if (template.local != null) viewModel.addMcpTemplate(template, url, key, env)
                else localNetwork.run(url) { viewModel.addMcpTemplate(template, url, key.takeUnless { saved }, env, useSavedKey = saved) }
                onAddingMcp(null)
            },
            onDismiss = { onAddingMcp(null) },
        )
    }
    if (addingMcp == McpStep.CUSTOM) {
        var local by rememberSaveable { mutableStateOf(0) }
        // Saved across rotation, like the Remote/Local choice.
        var name by rememberSaveable { mutableStateOf("") }
        var url by rememberSaveable { mutableStateOf("https://") }
        var token by rememberSaveable { mutableStateOf("") }
        val form = rememberSaveable(saver = LocalMcpForm.Saver) { LocalMcpForm() }
        LocusSheet(
            title = stringResource(R.string.library_connectors_add_mcp),
            subtitle = stringResource(if (local == 0) R.string.library_connectors_custom_subtitle_remote else R.string.library_connectors_custom_subtitle_local),
            onDismiss = { onAddingMcp(null) },
            action = stringResource(R.string.library_action_add),
            actionEnabled = name.isNotBlank() && if (local == 0) (url.startsWith("https://") || url.startsWith("http://")) && url.length > 10 else form.isValid,
            onAction = {
                if (local == 0) {
                    val (n, u, t) = Triple(name, url, token.ifBlank { null })
                    // Adding connects right away (to ask whether the server needs a sign-in).
                    localNetwork.run(u) { viewModel.addMcpServer(n, u, t) }
                } else {
                    viewModel.addLocalMcpServer(name, form.command, form.argList(), form.envPairs())
                }
                onAddingMcp(null)
            },
        ) {
            Segmented(listOf(stringResource(R.string.library_connectors_custom_remote), stringResource(R.string.library_connectors_custom_local)), selected = local, onSelect = { local = it }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.library_connectors_custom_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (local == 0) {
                OutlinedTextField(url, { url = it.trim() }, label = { Text(stringResource(R.string.library_connectors_custom_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                // Plain http:// is meant for servers on this phone or the home network; say so for anything else.
                dev.locus.core.model.LocalNetwork.cleartextWarning(url)?.let { warning ->
                    Text(warning, style = LocusType.supporting, color = MaterialTheme.colorScheme.error)
                }
                LocalNetworkNote(url, localNetwork)
                SecretContent()
                OutlinedTextField(
                    token, { token = it }, label = { Text(stringResource(R.string.library_connectors_custom_token)) }, supportingText = { Text(stringResource(R.string.library_connectors_custom_token_hint)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LocalMcpFields(form, linuxInstalled = library.linuxInstalled, onExample = { ex -> form.command = ex.command; if (name.isBlank()) name = ex.name })
            }
        }
    }
    secretFor?.let { (entry, secretName) ->
        var value by remember { mutableStateOf("") }
        LocusSheet(
            title = stringResource(R.string.library_connectors_key_title, entry.connector.name),
            subtitle = if (entry.connector.local != null) stringResource(R.string.library_connectors_key_subtitle_local)
            else stringResource(R.string.library_connectors_key_subtitle_remote, entry.connector.host),
            onDismiss = { secretFor = null },
            action = stringResource(R.string.library_action_save),
            actionEnabled = value.isNotBlank(),
            onAction = { viewModel.setSecret(entry.connector.id, secretName, value); secretFor = null },
        ) {
            SecretContent()
            OutlinedTextField(value, { value = it }, label = { Text(secretName) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
    }
    reviewingTools?.let { id ->
        val pending = toolChanges[id].orEmpty()
        val name = library.connectors.firstOrNull { it.connector.id == id }?.connector?.name ?: id
        // Approved (or the server changed back) while open: nothing left to review.
        if (pending.isEmpty()) {
            reviewingTools = null
        } else {
            McpToolChangesSheet(name, pending, onApprove = { viewModel.approveTools(id); reviewingTools = null }, onDismiss = { reviewingTools = null })
        }
    }
    removing?.let { entry ->
        val c = entry.connector
        ConfirmRemove(
            title = stringResource(R.string.library_connectors_remove_title, c.name),
            body = stringResource(
                when {
                    c.local != null -> R.string.library_connectors_remove_body_local
                    c.kind == "mcp" -> R.string.library_connectors_remove_body_mcp
                    else -> R.string.library_connectors_remove_body_connector
                },
            ),
            action = stringResource(R.string.library_action_remove),
            onDismiss = { removing = null },
            onConfirm = { viewModel.removeConnector(c.id); removing = null },
        )
    }
}

/**
 * A connector's leading tile: the logo of the template it was added from (the same lookup and
 * tiles as the Add an MCP server picker), else its first letter.
 */
@Composable
internal fun ConnectorTile(connector: Connector) {
    ConnectorIcon(
        id = McpTemplates.iconId(connector),
        name = connector.name,
        contentDescription = when {
            connector.local != null -> stringResource(R.string.library_connectors_icon_local_mcp)
            connector.kind == "mcp" -> stringResource(R.string.library_connectors_icon_mcp)
            else -> stringResource(R.string.library_connectors_icon_connector)
        },
    )
}

/** Asks before something is removed or forgotten for good. */
@Composable
internal fun ConfirmRemove(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmSheet(title = title, message = body, confirmLabel = action, tone = Tone.Critical, onConfirm = onConfirm, onDismiss = onDismiss)
}

/** The background of a code or markdown well inside a card. */
@Composable
internal fun Modifier.codeWell(): Modifier =
    background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(Spacing.md)

/** The steps of Add an MCP server, saved as a string so they survive rotation. */
internal object McpStep {
    const val PICK = "pick"
    const val CUSTOM = "custom"
    /** The service picker (Add a service). */
    const val SERVICES = "services"
    private const val TEMPLATE = "template:"
    private const val SERVICE = "service:"

    /** A service template's setup sheet, with [address] filled in (Replace key) or empty. */
    fun service(id: String, address: String = "") = "$SERVICE$id\n$address"

    /** The service template id and address of a [service] step. */
    fun serviceId(step: String?): Pair<String, String>? =
        step?.takeIf { it.startsWith(SERVICE) }?.removePrefix(SERVICE)?.let { it.substringBefore('\n') to it.substringAfter('\n', "") }

    fun template(id: String) = TEMPLATE + id

    fun templateId(step: String?): String? = step?.takeIf { it.startsWith(TEMPLATE) }?.removePrefix(TEMPLATE)
}
