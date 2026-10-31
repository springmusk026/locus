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
import com.mikepenz.markdown.m3.Markdown
import dev.locus.core.agent.home.AgentHome
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
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoute(onBack: () -> Unit, initialTab: Int = 0, viewModel: LibraryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var addingMcp by rememberSaveable { mutableStateOf(false) }

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
                        if (pendingAgents > 0) "Agents · $pendingAgents" else "Agents",
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

private val listPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xxl)

@Composable
private fun MemoryTab(library: Library, viewModel: LibraryViewModel, modifier: Modifier) {
    var editing by remember { mutableStateOf<AgentHome.MemoryTopic?>(null) }
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
                        TextButton(onClick = { viewModel.forgetMemory(topic.name) }) { Text("Forget", color = Locus.colors.critical) }
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
}

@Composable
private fun SkillsTab(library: Library, viewModel: LibraryViewModel, modifier: Modifier) {
    if (library.skills.isEmpty()) {
        EmptyState(
            "No skills yet",
            "Skills are reusable how-tos. Ask Locus to “save that as a skill” after a task, and it drafts one for you to review here.",
            modifier,
            icon = LocusIcons.Library,
        )
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = listPadding) {
        item { SectionNote("Reusable how-tos Locus follows. Ones it drafts wait here for your review.", Modifier.padding(start = Spacing.xs)) }
        item { CardGroup {
        library.skills.forEachIndexed { i, skill ->
            if (i > 0) GroupDivider(inset = ROW_INSET)
            ExpandableRow(
                title = skill.name,
                subtitle = skill.description,
                icon = LocusIcons.Library,
                badge = if (skill.draft) "Needs review" else null,
                startExpanded = skill.draft,
            ) {
                Markdown(
                    content = skill.body,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(Radius.control)).padding(Spacing.md),
                )
                if (skill.files.isNotEmpty()) {
                    Text("Files: ${skill.files.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
                    if (skill.draft) {
                        OutlinedButton(onClick = { viewModel.rejectSkill(skill.name) }) { Text("Reject") }
                        Button(onClick = { viewModel.approveSkill(skill.name) }) { Text("Approve") }
                    } else {
                        TextButton(onClick = { viewModel.removeSkill(skill.name) }) { Text("Remove", color = Locus.colors.critical) }
                    }
                }
            }
        }
        } }
    }
}

@Composable
private fun ConnectorsTab(
    library: Library,
    viewModel: LibraryViewModel,
    modifier: Modifier,
    addingMcp: Boolean,
    onAddingMcp: (Boolean) -> Unit,
) {
    var secretFor by remember { mutableStateOf<ConnectorEntry?>(null) }
    val signIn by viewModel.signInStatus.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxSize(), contentPadding = listPadding) {
        signIn?.let { status ->
            item {
                when (status) {
                    is SignInStatus.Working -> Banner(
                        "Finish signing in to “${status.label}” in your browser, then come back here.",
                        Tone.Caution,
                        Modifier.padding(bottom = Spacing.md),
                        title = "Signing in",
                    ) { TextButton(onClick = viewModel::cancelSignIn) { Text("Cancel") } }
                    is SignInStatus.Failed -> Banner(status.message, Tone.Critical, Modifier.padding(bottom = Spacing.md), title = "Couldn't sign in") {
                        TextButton(onClick = viewModel::dismissSignIn) { Text("OK") }
                    }
                }
            }
        }
        item {
            SectionNote(
                "Online services Locus can use. Add an MCP server, or ask Locus to “connect to <service>”: it drafts a connector and you add the key here, so the AI never sees it.",
                Modifier.padding(start = Spacing.xs),
            )
        }
        item { CardGroup {
            library.connectors.forEachIndexed { i, entry ->
                if (i > 0) GroupDivider(inset = ROW_INSET)
                val c = entry.connector
                ExpandableRow(
                    title = c.name,
                    subtitle = if (c.kind == "mcp") "MCP server · ${c.host}" else "${c.host} · ${c.operations.size} operations",
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
                            TextButton(onClick = { viewModel.signIn(c.id) }) { Text(if (entry.hasSecret) "Sign in again" else "Sign in") }
                        } else if (c.auth.type != "none") {
                            TextButton(onClick = { secretFor = entry }) { Text(if (entry.hasSecret) "Replace key" else "Add key") }
                        }
                        if (entry.draft) {
                            OutlinedButton(onClick = { viewModel.rejectConnector(c.id) }) { Text("Reject") }
                            Button(onClick = { viewModel.approveConnector(c.id) }) { Text("Approve") }
                        } else {
                            TextButton(onClick = { viewModel.removeConnector(c.id) }) { Text("Remove", color = Locus.colors.critical) }
                        }
                    }
                }
            }
            if (library.connectors.isNotEmpty()) GroupDivider(inset = ROW_INSET)
            Row(
                Modifier.fillMaxWidth().clickable { onAddingMcp(true) }.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(LocusIcons.Add, tint = MaterialTheme.colorScheme.primary)
                Text("Add MCP server", Modifier.padding(start = Spacing.md), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        } }
    }
    // Outside the sheet: the sheet closes on Add while the permission question may still be open.
    val localNetwork = rememberLocalNetworkGate()
    if (addingMcp) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        var token by remember { mutableStateOf("") }
        LocusSheet(
            title = "Add MCP server",
            subtitle = "A Model Context Protocol server reachable over HTTP (Streamable HTTP).",
            onDismiss = { onAddingMcp(false) },
            action = "Add",
            actionEnabled = name.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://")) && url.length > 10,
            onAction = {
                val (n, u, t) = Triple(name, url, token.ifBlank { null })
                // Adding connects right away (to ask whether the server needs a sign-in).
                localNetwork.run(u) { viewModel.addMcpServer(n, u, t) }
                onAddingMcp(false)
            },
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it.trim() }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LocalNetworkNote(url, localNetwork)
            OutlinedTextField(
                token, { token = it }, label = { Text("Bearer token (optional)") }, supportingText = { Text("Leave empty to sign in with your account if the server asks for it.") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    secretFor?.let { entry ->
        var value by remember { mutableStateOf("") }
        LocusSheet(
            title = "Key for ${entry.connector.name}",
            subtitle = "Stored encrypted on this phone and added to requests to ${entry.connector.host}. The AI never sees it.",
            onDismiss = { secretFor = null },
            action = "Save",
            actionEnabled = value.isNotBlank(),
            onAction = { viewModel.setSecret(entry.connector.auth.secret, value); secretFor = null },
        ) {
            OutlinedTextField(value, { value = it }, label = { Text(entry.connector.auth.secret) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
    }
}

private val ROW_INSET = Spacing.lg + 36.dp + Spacing.md

@Composable
private fun ExpandableRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badge: String? = null,
    meta: String? = null,
    startExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title, badge) { mutableStateOf(startExpanded) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) 6 else 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                badge != null -> StatusPill(badge, Tone.Caution)
                meta != null -> Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
        }
    }
}
