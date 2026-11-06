package dev.locus.feature.library

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
    var addingMcp by rememberSaveable { mutableStateOf(initialTemplate?.takeIf { McpTemplates.byId(it) != null }?.let { McpStep.template(it) }) }

    val library = (state as? LibraryUiState.Ready)?.library
    val pendingSkills = library?.skills?.count { it.draft } ?: 0
    val toolChanges by viewModel.pendingTools.collectAsStateWithLifecycle()
    val pendingConnectors = library?.connectors?.count { it.draft || toolChanges[it.connector.id].orEmpty().isNotEmpty() } ?: 0
    val pendingAgents = library?.agents?.count { it.draft } ?: 0
    LocusScreen("Library", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
        // Plain tab names fit at 360dp; what waits for review is said once, under the tabs.
        Segmented(
            listOf("Memory", "Skills", "Connectors", "Helpers"),
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
                "Couldn't open the Library",
                s.message,
                modifier,
                icon = LocusIcons.Library,
                action = { Button(onClick = viewModel::retryLoad) { Text("Try again") } },
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
internal fun pendingReviewLine(skills: Int, connectors: Int, helpers: Int): String? {
    fun count(n: Int, one: String, many: String) = if (n > 0) "$n ${if (n == 1) one else many}" else null
    val parts = listOfNotNull(count(skills, "skill", "skills"), count(connectors, "connector", "connectors"), count(helpers, "helper", "helpers"))
    return if (parts.isEmpty()) null else "To review: " + parts.joinToString(", ")
}

/** A Library action that failed: what and why, with Try again when it can be retried. */
@Composable
private fun ActionErrorBanner(error: LibraryError, onDismiss: () -> Unit) {
    Banner(
        error.message,
        Tone.Critical,
        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        title = error.title,
        actionsBelow = dev.locus.core.designsystem.hasSettingsAction(error.message),
    ) {
        // "… a GitHub token in Settings → Integrations → GitHub": a button that opens it.
        dev.locus.core.designsystem.SettingsActionFor(error.message)
        TextButton(onClick = onDismiss) { Text("Dismiss") }
        error.retry?.let { retry -> TextButton(onClick = retry) { Text("Try again") } }
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
            "Nothing remembered yet",
            "As you work together, Locus saves your preferences, facts you share and lessons from tasks here. Chats in a project save to that project's memory. You can edit or delete anything.",
            modifier,
            icon = LocusIcons.Note,
        )
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = listPadding) {
            item { SectionNote("What Locus has learned about you and your work. It reads the index at the start of every chat.") }
            if (library.projectMemory.isNotEmpty()) item { SectionLabel("General") }
            item {
                if (library.memory.isEmpty()) {
                    SectionNote("Nothing in general memory yet. Notes from chats outside projects go here.")
                } else {
                    MemoryCard(library.memory.map { MemoryTarget(it) }, library, onOpenChat, onEdit = { editing = it }, onForget = { forgetting = it }, onMove = null)
                }
            }
            library.projectMemory.forEach { project ->
                item(key = "project-${project.projectId}") {
                    Column {
                        SectionLabel(project.projectName)
                        SectionNote(
                            "Only chats in this project read these. Move a note to general memory to use it everywhere.",
                        )
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
            subtitle = target.project?.let { "In ${it.projectName}'s memory. Locus reads the summary first and the note when it needs it." }
                ?: "What Locus remembers. It reads the summary first and the note when it needs it.",
            onDismiss = { editing = null },
            action = "Save",
            actionEnabled = content.isNotBlank(),
            onAction = { viewModel.saveMemory(topic.name, summary, content, target.project?.projectId); editing = null },
        ) {
            OutlinedTextField(summary, { summary = it }, label = { Text("Summary") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(content, { content = it }, label = { Text("Note") }, minLines = 5, modifier = Modifier.fillMaxWidth())
        }
    }
    forgetting?.let { target ->
        ConfirmRemove(
            title = "Forget “${target.topic.name.replace('-', ' ')}”?",
            body = target.project?.let { "Locus deletes this note from ${it.projectName}'s memory and won't know it in the project's new chats." }
                ?: "Locus deletes this note from its memory and won't know it in new chats.",
            action = "Forget",
            onDismiss = { forgetting = null },
            onConfirm = { viewModel.forgetMemory(target.topic.name, target.project?.projectId); forgetting = null },
        )
    }
    moving?.let { target ->
        val project = target.project ?: return@let
        val replaces = library.memory.any { it.name == target.topic.name }
        LocusSheet(
            title = "Move “${target.topic.name.replace('-', ' ')}” to general memory?",
            subtitle = "Chats outside ${project.projectName} can use it then, and so can projects that read general memory. It leaves the project's memory." +
                if (replaces) " General memory already has a note with this name: it's replaced, and the old version is kept in history." else "",
            onDismiss = { moving = null },
            action = "Move",
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
    CardGroup {
        topics.forEachIndexed { i, target ->
            if (i > 0) GroupDivider(inset = Insets.iconRow)
            val topic = target.topic
            val origin = topic.provenance?.let { memoryOriginView(it, it.chatId?.let(library.memoryChats::get)) }
            ExpandableRow(
                title = topic.name.replace('-', ' '),
                subtitle = topic.summary.ifBlank { "No summary" },
                icon = LocusIcons.Note,
                iconDescription = if (target.project != null) "Project memory note" else "Memory note",
                // Only the time fits beside the title; who saved it is in the open row.
                meta = relativeTime(topic.modified),
                badge = origin?.warning?.let { "Needs a look" },
                expandKey = null,
            ) {
                origin?.let { MemoryOrigin(it, onOpenChat) }
                SelectionContainer { Text(topic.content, style = LocusType.rowSupporting) }
                // Actions wrap to a second line at a large font instead of running off the card.
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    onMove?.let { move -> TextButton(onClick = { move(target) }) { Text("Move to general") } }
                    TextButton(onClick = { onEdit(target) }) { Text("Edit") }
                    TextButton(onClick = { onForget(target) }) { Text("Forget", color = Locus.colors.critical) }
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
        SupportingText("${origin.who} · ${origin.date}")
        origin.chat?.let { chat ->
            Text(
                "From “${chat.title}”",
                Modifier.clickable(onClickLabel = "Open the chat") { onOpenChat(chat.id) }.padding(vertical = Spacing.xs),
                style = LocusType.supporting,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (origin.chatGone) {
            SupportingText("The chat it was saved in has been deleted.")
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
                        "Finish signing in to “${status.label}” in your browser, then come back here.",
                        Tone.Live,
                        Modifier.padding(bottom = Spacing.md),
                        title = "Signing in",
                    ) { TextButton(onClick = viewModel::cancelSignIn) { Text("Cancel") } }
                    is SignInStatus.Failed -> Banner(status.message, Tone.Critical, Modifier.padding(bottom = Spacing.md), title = status.title) {
                        TextButton(onClick = viewModel::dismissSignIn) { Text("OK") }
                    }
                }
            }
        }
        item {
            SectionNote(
                "Services Locus can use. Add an MCP server (online, or local in Linux), or ask Locus to “connect to <service>”: it drafts a connector and you add the key here, so the AI never sees it.",
            )
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
                    subtitle = if (c.kind == "mcp") "MCP server · ${c.host}" else "${c.host} · ${c.operations.size} ${if (c.operations.size == 1) "operation" else "operations"}",
                    leading = { ConnectorTile(c) },
                    badge = when {
                        entry.draft -> "Needs review"
                        toolChanges[c.id].orEmpty().isNotEmpty() -> toolChangesBadge(toolChanges[c.id].orEmpty())
                        !entry.hasSecret && c.auth.type == "oauth" -> "Sign in"
                        entry.key == KeyState.Unconfirmed -> "Confirm key"
                        !entry.hasSecret -> "Needs key"
                        else -> null
                    },
                    startExpanded = entry.draft || toolChanges[c.id].orEmpty().isNotEmpty(),
                ) {
                    toolChanges[c.id]?.takeIf { it.isNotEmpty() }?.let { pending -> ToolChangesBanner(pending, onReview = { reviewingTools = c.id }) }
                    Text(c.description, style = LocusType.rowSupporting)
                    SupportingText("Only talks to ${c.host}.")
                    c.operations.forEach { op ->
                        Text(
                            "${op.method.uppercase()} ${op.path} — ${op.name}" + when (op.sideEffect) {
                                "read" -> ""
                                "destructive" -> " (deletes; asks first)"
                                else -> " (changes data; asks first)"
                            },
                            style = CodeTextStyle,
                        )
                    }
                    connectorKeyNote(entry)?.let { Text(it, style = LocusType.supporting) }
                    // Actions wrap to a second line at a large font instead of running off the card.
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (c.auth.type == "oauth") {
                            if (!entry.draft) TextButton(onClick = { viewModel.signIn(c.id, c.name) }) { Text(if (entry.hasSecret) "Sign in again" else "Sign in") }
                        } else if (c.auth.type != "none") {
                            if (entry.key == KeyState.Unconfirmed) TextButton(onClick = { viewModel.confirmKey(c.id) }) { Text("Use for ${c.host}") }
                            TextButton(onClick = { secretFor = entry to c.auth.secret }) { Text(if (entry.hasSecret || entry.key == KeyState.Unconfirmed) "Replace key" else "Add key") }
                        }
                        if (entry.draft) {
                            OutlinedButton(onClick = { viewModel.rejectConnector(c.id) }) { Text("Reject") }
                            Button(onClick = { viewModel.approveConnector(c.id) }) { Text("Approve") }
                        } else {
                            TextButton(onClick = { removing = entry }) { Text("Remove", color = Locus.colors.critical) }
                        }
                    }
                }
            }
            if (library.connectors.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
            AddRow("Add an MCP server", LocusIcons.Add) { onAddingMcp(McpStep.PICK) }
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
    McpStep.templateId(addingMcp)?.let(McpTemplates::byId)?.let { template ->
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
            title = "Add an MCP server",
            subtitle = if (local == 0) "A Model Context Protocol server reachable over HTTP (Streamable HTTP)."
            else "A server Locus starts inside its Linux system when a chat needs it, and stops when idle.",
            onDismiss = { onAddingMcp(null) },
            action = "Add",
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
            Segmented(listOf("Remote (HTTP)", "Local (Linux)"), selected = local, onSelect = { local = it }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (local == 0) {
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                // Plain http:// is meant for servers on this phone or the home network; say so for anything else.
                dev.locus.core.model.LocalNetwork.cleartextWarning(url)?.let { warning ->
                    Text(warning, style = LocusType.supporting, color = MaterialTheme.colorScheme.error)
                }
                LocalNetworkNote(url, localNetwork)
                OutlinedTextField(
                    token, { token = it }, label = { Text("Bearer token (optional)") }, supportingText = { Text("Leave empty to sign in with your account if the server asks for it.") }, singleLine = true,
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
            title = "Key for ${entry.connector.name}",
            subtitle = if (entry.connector.local != null) "Stored encrypted on this phone and given to the server's process when it starts. The AI never sees it."
            else "Stored encrypted on this phone and added to requests to ${entry.connector.host}. The AI never sees it.",
            onDismiss = { secretFor = null },
            action = "Save",
            actionEnabled = value.isNotBlank(),
            onAction = { viewModel.setSecret(entry.connector.id, secretName, value); secretFor = null },
        ) {
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
            title = "Remove ${c.name}?",
            body = when {
                c.local != null -> "Locus stops the server and deletes it, with its saved keys. Chats can't use it any more."
                c.kind == "mcp" -> "Locus deletes the server and its saved key or sign-in from this phone. Chats can't use it any more."
                else -> "Locus deletes the connector and its saved key from this phone. Chats can't use it any more."
            },
            action = "Remove",
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
            connector.local != null -> "Local MCP server"
            connector.kind == "mcp" -> "MCP server"
            else -> "Connector"
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
    private const val TEMPLATE = "template:"

    fun template(id: String) = TEMPLATE + id

    fun templateId(step: String?): String? = step?.takeIf { it.startsWith(TEMPLATE) }?.removePrefix(TEMPLATE)
}
