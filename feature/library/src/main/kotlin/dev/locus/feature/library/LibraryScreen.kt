package dev.locus.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.data.repository.ConnectorEntry
import dev.locus.core.data.repository.Library
import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.rememberLocalNetworkGate
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.locus.core.designsystem.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoute(
    onBack: () -> Unit,
    initialTab: Int = 0,
    /** A template id from the catalogue to open right away (Settings → Integrations → Use the official MCP server). */
    initialTemplate: String? = null,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(if (initialTemplate != null) 2 else initialTab) }
    // Where Add an MCP server is: null (closed), the picker, the custom form, or a template's id.
    var addingMcp by rememberSaveable { mutableStateOf(initialTemplate?.takeIf { McpTemplates.byId(it) != null }?.let { McpStep.template(it) }) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                LocusTopBar("Library", onBack = onBack)
                val library = (state as? LibraryUiState.Ready)?.library
                val pendingSkills = library?.skills?.count { it.draft } ?: 0
                val pendingConnectors = library?.connectors?.count { it.draft } ?: 0
                val pendingAgents = library?.agents?.count { it.draft } ?: 0
                Segmented(
                    listOf(
                        "Memory",
                        if (pendingSkills > 0) "Skills · $pendingSkills" else "Skills",
                        if (pendingConnectors > 0) "Connectors · $pendingConnectors" else "Connectors",
                        if (pendingAgents > 0) "Helpers · $pendingAgents" else "Helpers",
                    ),
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    selectedTint = { i ->
                        if ((i == 1 && pendingSkills > 0) || (i == 2 && pendingConnectors > 0) || (i == 3 && pendingAgents > 0)) Locus.colors.caution else null
                    },
                )
            }
        },
    ) { padding ->
        val library = (state as? LibraryUiState.Ready)?.library ?: return@Scaffold
        val modifier = Modifier.padding(top = padding.calculateTopPadding())
        when (tab) {
            0 -> MemoryTab(library, viewModel, modifier)
            1 -> SkillsTab(library, viewModel, modifier)
            2 -> ConnectorsTab(library, viewModel, modifier, addingMcp, onAddingMcp = { addingMcp = it })
            else -> AgentsTab(library, viewModel, modifier)
        }
    }
}

internal val listPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xxl)

@Composable
private fun MemoryTab(library: Library, viewModel: LibraryViewModel, modifier: Modifier) {
    var editing by remember { mutableStateOf<AgentHome.MemoryTopic?>(null) }
    var forgetting by remember { mutableStateOf<AgentHome.MemoryTopic?>(null) }
    if (library.memory.isEmpty()) {
        EmptyState(
            "Nothing remembered yet",
            "As you work together, Locus saves your preferences, facts you share and lessons from tasks here. You can edit or delete anything.",
            modifier,
            icon = LocusIcons.Note,
        )
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = listPadding) {
            item { SectionNote("What Locus has learned about you and your work. It reads the index at the start of every chat.", Modifier.padding(start = Spacing.xs)) }
            item { CardGroup {
            library.memory.forEachIndexed { i, topic ->
                if (i > 0) GroupDivider(inset = ROW_INSET)
                ExpandableRow(
                    title = topic.name.replace('-', ' '),
                    subtitle = topic.summary.ifBlank { "No summary" },
                    icon = LocusIcons.Note,
                    meta = relativeTime(topic.modified),
                ) {
                    SelectionContainer { Text(topic.content, style = MaterialTheme.typography.bodyMedium) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = topic }) { Text("Edit") }
                        TextButton(onClick = { forgetting = topic }) { Text("Forget", color = Locus.colors.critical) }
                    }
                }
            }
            } }
        }
    }
    editing?.let { topic ->
        var summary by remember(topic.name) { mutableStateOf(topic.summary) }
        var content by remember(topic.name) { mutableStateOf(topic.content) }
        LocusSheet(
            title = topic.name.replace('-', ' '),
            subtitle = "What Locus remembers. It reads the summary first and the note when it needs it.",
            onDismiss = { editing = null },
            action = "Save",
            onAction = { viewModel.saveMemory(topic.name, summary, content); editing = null },
        ) {
            OutlinedTextField(summary, { summary = it }, label = { Text("Summary") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(content, { content = it }, label = { Text("Note") }, minLines = 5, modifier = Modifier.fillMaxWidth())
        }
    }
    forgetting?.let { topic ->
        ConfirmRemove(
            title = "Forget “${topic.name.replace('-', ' ')}”?",
            body = "Locus deletes this note from its memory and won't know it in new chats.",
            action = "Forget",
            onDismiss = { forgetting = null },
            onConfirm = { viewModel.forgetMemory(topic.name); forgetting = null },
        )
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
    val signIn by viewModel.signInStatus.collectAsStateWithLifecycle()
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
                Modifier.padding(start = Spacing.xs),
            )
        }
        item { CardGroup {
            library.connectors.forEachIndexed { i, entry ->
                if (i > 0) GroupDivider(inset = ROW_INSET)
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
                    )
                    return@forEachIndexed
                }
                ExpandableRow(
                    title = c.name,
                    subtitle = if (c.kind == "mcp") "MCP server · ${c.host}" else "${c.host} · ${c.operations.size} ${if (c.operations.size == 1) "operation" else "operations"}",
                    icon = if (c.kind == "mcp") LocusIcons.Server else LocusIcons.Plug,
                    badge = when {
                        entry.draft -> "Needs review"
                        !entry.hasSecret && c.auth.type == "oauth" -> "Sign in"
                        !entry.hasSecret -> "Needs key"
                        else -> null
                    },
                    startExpanded = entry.draft,
                ) {
                    Text(c.description, style = MaterialTheme.typography.bodyMedium)
                    Text("Only talks to ${c.host}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    if (c.auth.type == "oauth") {
                        Text(
                            if (entry.hasSecret) "Signed in. Locus keeps the sign-in encrypted on this phone and renews it automatically."
                            else "This server needs you to sign in with your account in the browser.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (c.auth.type != "none") {
                        Text(
                            "Key: “${c.auth.secret}” sent as ${if (c.auth.type == "bearer") "a bearer token" else "${c.auth.type} ${c.auth.name}"}." +
                                if (entry.hasSecret) " Saved." else " Not added yet.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
                        if (c.auth.type == "oauth") {
                            TextButton(onClick = { viewModel.signIn(c.id, c.name) }) { Text(if (entry.hasSecret) "Sign in again" else "Sign in") }
                        } else if (c.auth.type != "none") {
                            TextButton(onClick = { secretFor = entry to c.auth.secret }) { Text(if (entry.hasSecret) "Replace key" else "Add key") }
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
            if (library.connectors.isNotEmpty()) GroupDivider(inset = ROW_INSET)
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
            onAdd = { form ->
                val (url, key, env) = Triple(form.url, form.key, form.envPairs())
                // Adding connects right away (to sign in), so a local-network address asks first.
                if (template.local != null) viewModel.addMcpTemplate(template, url, key, env)
                else localNetwork.run(url) { viewModel.addMcpTemplate(template, url, key, env) }
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
            onAction = { viewModel.setSecret(secretName, value); secretFor = null },
        ) {
            OutlinedTextField(value, { value = it }, label = { Text(secretName) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
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

/** Asks before something is removed or forgotten for good. */
@Composable
internal fun ConfirmRemove(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    LocusSheet(title = title, subtitle = body, onDismiss = onDismiss, action = action, actionTone = Tone.Critical, onAction = onConfirm) {}
}

/** The last row of a group: adds or imports an item. */
@Composable
internal fun AddRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.padding(start = Spacing.md), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The background of a code or markdown well inside a card. */
@Composable
internal fun Modifier.codeWell(): Modifier =
    background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Radius.control)).padding(Spacing.md)

internal val ROW_INSET = Spacing.lg + 36.dp + Spacing.md

@Composable
internal fun ExpandableRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badge: String? = null,
    meta: String? = null,
    startExpanded: Boolean = false,
    badgeTone: Tone = Tone.Caution,
    /** When this changes the row returns to [startExpanded]; by default when the badge changes. */
    expandKey: String? = badge,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title, expandKey) { mutableStateOf(startExpanded) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) 6 else 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                badge != null -> StatusPill(badge, badgeTone)
                meta != null -> Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
        }
    }
}

/** The steps of Add an MCP server, saved as a string so they survive rotation. */
internal object McpStep {
    const val PICK = "pick"
    const val CUSTOM = "custom"
    private const val TEMPLATE = "template:"

    fun template(id: String) = TEMPLATE + id

    fun templateId(step: String?): String? = step?.takeIf { it.startsWith(TEMPLATE) }?.removePrefix(TEMPLATE)
}
