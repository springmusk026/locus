package dev.locus.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.data.search.ChatSearchHit
import dev.locus.core.data.search.Highlighted
import dev.locus.core.data.search.MessageMatch
import dev.locus.core.data.search.SearchQuery
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.FileTypeIcon
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.FloatingLayer
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.cardSegment
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.entrance
import dev.locus.core.designsystem.pressable
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.model.Role
import dev.locus.core.model.toolActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ConversationsRoute(
    onOpenConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenAnalytics: () -> Unit = {},
    /** Opens a chat at a message (a search result). */
    onOpenMessage: (conversationId: String, messageId: String) -> Unit = { id, _ -> onOpenConversation(id) },
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val composerActions = remember(viewModel) {
        ComposerActions(
            setModel = { id, model -> viewModel.setModel(id, model) },
            prepareModels = viewModel::prepareModels,
            toggleFavorite = { viewModel.toggleFavorite(it) },
            enableCatalog = viewModel::enableCatalog,
            setEffort = viewModel::setEffort,
            setPlanMode = viewModel::setPlanMode,
            setToolEnabled = viewModel::setToolEnabled,
            setMode = viewModel::setMode,
            onAttach = { viewModel.attach(it) },
            onRemoveAttachment = viewModel::removeAttachment,
        )
    }
    ConversationsScreen(
        state = state,
        composer = composer,
        composerActions = composerActions,
        actions = HomeActions(
            onStart = { text -> viewModel.start(text, onOpenConversation) },
            onNewChat = { viewModel.newConversation(onOpenConversation) },
            onOpenConversation = onOpenConversation,
            onOpenMessage = onOpenMessage,
            onDelete = viewModel::delete,
            onPin = viewModel::setPinned,
            onRename = viewModel::rename,
            onSearch = viewModel::search,
            onOpenSettings = onOpenSettings,
            onOpenLibrary = onOpenLibrary,
            onOpenTasks = onOpenTasks,
            onOpenWorkspace = onOpenWorkspace,
            onOpenActivity = onOpenActivity,
            onOpenAnalytics = onOpenAnalytics,
        ),
    )
}

internal class HomeActions(
    val onStart: (String) -> Unit,
    val onNewChat: () -> Unit,
    val onOpenConversation: (String) -> Unit,
    val onOpenMessage: (conversationId: String, messageId: String) -> Unit,
    val onDelete: (String) -> Unit,
    val onPin: (String, Boolean) -> Unit,
    val onRename: (String, String) -> Unit,
    val onSearch: (String?) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenLibrary: () -> Unit,
    val onOpenTasks: () -> Unit,
    val onOpenWorkspace: () -> Unit,
    val onOpenActivity: () -> Unit,
    val onOpenAnalytics: () -> Unit,
)

/** How many chats home shows before "All chats" takes over. */
private const val HOME_RECENT = 5
private const val HOME_PINNED = 3

/** Scheduled runs are titled this way by TaskWorker; their news reaches home as the task result card. */
private val ConversationRow.isScheduledRun get() = conversation.title.startsWith("Scheduled: ")

/**
 * Home is one calm overview (status, the active chat, a launcher strip, a few recent chats and
 * files, the ask bar). Everything else lives one tap away: the full chat list is a second mode of
 * this screen with search, pinned on top and date sections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationsScreen(
    state: ConversationsUiState,
    actions: HomeActions,
    composer: HomeComposer = HomeComposer(),
    composerActions: ComposerActions = ComposerActions(),
) {
    var pendingDelete by remember { mutableStateOf<ConversationRow?>(null) }
    var pendingRename by remember { mutableStateOf<ConversationRow?>(null) }
    var allChats by rememberSaveable { mutableStateOf(false) }
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    val ready = state as? ConversationsUiState.Ready
    val rowActions = RowActions(
        open = { actions.onOpenConversation(it.conversation.id) },
        openAt = { row, messageId -> actions.onOpenMessage(row.conversation.id, messageId) },
        pin = { actions.onPin(it.conversation.id, !it.conversation.pinned) },
        rename = { pendingRename = it },
        delete = { pendingDelete = it },
    )
    val closeAll = { allChats = false; focusSearch = false; actions.onSearch(null) }

    if (allChats && ready != null && ready.hasProvider) {
        BackHandler(onBack = closeAll)
        AllChats(ready, focusSearch, rowActions, onSearch = actions.onSearch, onBack = closeAll)
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    title = {
                        Column {
                            Text("Locus", style = MaterialTheme.typography.headlineSmall)
                            if (ready != null && ready.hasProvider) HomeStatus(ready)
                        }
                    },
                    actions = {
                        if (ready != null && ready.rows.isNotEmpty()) {
                            IconButton(onClick = { allChats = true; focusSearch = true; actions.onSearch("") }) { Icon(LocusIcons.Search, contentDescription = "Search chats") }
                        }
                        IconButton(onClick = actions.onOpenSettings) { Icon(LocusIcons.Settings, contentDescription = "Settings") }
                    },
                )
            },
            bottomBar = {
                if (ready != null && ready.hasProvider) AskBar(composer, composerActions, onStart = actions.onStart)
            },
        ) { padding ->
            when {
                ready == null -> Box(Modifier.fillMaxSize().padding(padding))
                !ready.hasProvider -> EmptyState(
                    title = "Connect a model",
                    body = "Locus runs on your phone and talks to the model you choose: your own API key, a model server on your network, or a model that runs fully offline on this phone.",
                    icon = LocusIcons.Sparkle,
                    modifier = Modifier.padding(padding),
                ) { Button(onClick = actions.onOpenSettings) { Text("Choose a model") } }
                else -> HomeList(ready, padding, actions, rowActions, onAllChats = { allChats = true; focusSearch = false })
            }
        }
    }

    pendingDelete?.let { row ->
        LocusSheet(
            title = "Delete this chat?",
            subtitle = "“${row.conversation.title}” and its history will be removed. Files in the workspace are not affected.",
            onDismiss = { pendingDelete = null },
            action = "Delete",
            actionTone = Tone.Critical,
            onAction = { actions.onDelete(row.conversation.id); pendingDelete = null },
        ) {}
    }
    pendingRename?.let { row ->
        var title by rememberSaveable(row.conversation.id) { mutableStateOf(row.conversation.title) }
        LocusSheet(
            title = "Rename chat",
            onDismiss = { pendingRename = null },
            action = "Save",
            actionEnabled = title.isNotBlank(),
            onAction = { actions.onRename(row.conversation.id, title); pendingRename = null },
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (title.isNotBlank()) { actions.onRename(row.conversation.id, title); pendingRename = null } }),
            )
        }
    }
}

private class RowActions(
    val open: (ConversationRow) -> Unit,
    /** A search result: open the chat at its matching message. */
    val openAt: (ConversationRow, String) -> Unit,
    val pin: (ConversationRow) -> Unit,
    val rename: (ConversationRow) -> Unit,
    val delete: (ConversationRow) -> Unit,
)

/** One line under the title answering "what's happening right now?", most urgent first. */
@Composable
private fun HomeStatus(state: ConversationsUiState.Ready) {
    val approvals = state.rows.count { it.needsApproval }
    val running = state.rows.count { it.running }
    when {
        approvals > 0 -> StatusLine(if (approvals == 1) "1 action needs your approval" else "$approvals actions need your approval", Tone.Caution)
        running > 0 -> StatusLine(if (running == 1) "Working on 1 chat" else "Working on $running chats", Tone.Live)
        else -> {
            val model = state.defaultModel
            StatusLine(if (model != null) "Ready · ${model.model.ifBlank { model.providerName }}" else "Ready", Tone.Neutral)
        }
    }
}

@Composable
private fun HomeList(
    state: ConversationsUiState.Ready,
    padding: PaddingValues,
    actions: HomeActions,
    rowActions: RowActions,
    onAllChats: () -> Unit,
) {
    // The chat that needs the user most, featured on top while it's active.
    val featured = state.rows.firstOrNull { it.needsApproval } ?: state.rows.firstOrNull { it.running }
    val pinned = state.rows.filter { it !== featured && it.conversation.pinned }
    // Scheduled runs stay out of Recent: the task result card carries their news, All chats keeps them.
    val recent = state.rows.filter { it !== featured && !it.conversation.pinned && !it.isScheduledRun }
    val shown = pinned.take(HOME_PINNED) + recent.take(HOME_RECENT)
    val hidden = state.rows.size - shown.size - (if (featured != null) 1 else 0)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = padding.calculateTopPadding() + Spacing.sm,
            bottom = padding.calculateBottomPadding() + Spacing.lg,
        ),
    ) {
        featured?.let { row ->
            item(key = "featured") { ActiveChatCard(row, onClick = { rowActions.open(row) }, modifier = Modifier.animateItem().padding(bottom = Spacing.md)) }
        }
        item(key = "launcher") { Launcher(state.counts, actions, Modifier.entrance(0)) }
        state.counts.latestResult?.let { result ->
            item(key = "task-result") { TaskResultCard(result, actions.onOpenTasks, Modifier.padding(top = Spacing.md).entrance(1)) }
        }
        if (pinned.isNotEmpty()) {
            item(key = "pinned-label") { SectionLabel("Pinned", Modifier.padding(start = 0.dp)) }
            item(key = "pinned") { ChatGroup(pinned.take(HOME_PINNED), rowActions, Modifier.entrance(2)) }
        }
        if (state.rows.isNotEmpty()) {
            item(key = "recent-label") {
                SectionLabel(
                    "Recent",
                    Modifier.padding(start = 0.dp),
                    action = if (hidden > 0) "All ${state.rows.size}" else null,
                    onAction = onAllChats,
                )
            }
            item(key = "recent") {
                ChatGroup(recent.take(HOME_RECENT), rowActions, Modifier.entrance(2), footer = { AllChatsRow(state.rows.size, onAllChats) })
            }
        }
        if (state.counts.recentFiles.isNotEmpty()) {
            item(key = "files-label") { SectionLabel("Recent files", Modifier.padding(start = 0.dp), action = "All", onAction = actions.onOpenWorkspace) }
            item(key = "files") { RecentFiles(state, actions.onOpenWorkspace, Modifier.entrance(3)) }
        }
        if (state.rows.isEmpty()) {
            item(key = "first") {
                Text(
                    "No chats yet. Ask anything below; Locus can search the web, work with files, run Linux commands and open your phone's apps.",
                    Modifier.padding(top = Spacing.xl, start = Spacing.xs, end = Spacing.xs).entrance(2),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatGroup(rows: List<ConversationRow>, actions: RowActions, modifier: Modifier = Modifier, footer: (@Composable () -> Unit)? = null) {
    CardGroup(modifier) {
        rows.forEachIndexed { i, row ->
            if (i > 0) GroupDivider(inset = Spacing.lg + 36.dp + Spacing.md)
            ConversationRowItem(row, actions)
        }
        if (footer != null) {
            if (rows.isNotEmpty()) GroupDivider(inset = 0.dp)
            footer()
        }
    }
}

/** The way into every chat (search, pinned, by date), at the foot of Recent. */
@Composable
private fun AllChatsRow(total: Int, onClick: () -> Unit) {
    ListRow(
        title = "All chats",
        onClick = onClick,
        leading = { IconTile(LocusIcons.Chats, size = 36.dp) },
        trailing = {
            Text("$total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.xs).size(18.dp))
        },
    )
}

@Composable
private fun RecentFiles(state: ConversationsUiState.Ready, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    CardGroup(modifier) {
        state.counts.recentFiles.forEachIndexed { i, f ->
            if (i > 0) GroupDivider(inset = Spacing.lg + 32.dp + Spacing.md)
            ListRow(
                title = f.name,
                onClick = onOpen,
                leading = { FileTypeIcon(f.name, isDirectory = false, size = 32.dp) },
                meta = {
                    Text(
                        "${f.path.substringBeforeLast('/', "").ifEmpty { "workspace" }} · ${relativeTime(f.modified)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/** The featured card: the chat that's running or waiting on the user, with what it's doing now. */
@Composable
private fun ActiveChatCard(row: ConversationRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tone = if (row.needsApproval) Tone.Caution else Tone.Live
    LocusCard(modifier, onClick = onClick, glow = tone.color(), outline = tone.color().copy(alpha = 0.3f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderBadge(row.providerName, row.providerUrl, row.onDevice, size = 32.dp, model = row.model)
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(row.conversation.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLine(
                    when {
                        row.needsApproval -> "Waiting for your approval"
                        row.activeTool != null -> toolActivity(row.activeTool)
                        else -> "Thinking"
                    },
                    tone,
                )
            }
            Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class Door(val label: String, val icon: ImageVector, val onClick: () -> Unit, val description: String, val attention: Boolean = false)

/**
 * The rest of the app in one quiet row of five doors. Only something that needs the user gets a
 * mark (a caution dot); the details are one tap away.
 */
@Composable
private fun Launcher(counts: HomeCounts, actions: HomeActions, modifier: Modifier = Modifier) {
    fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
    val doors = listOf(
        Door(
            "Library", LocusIcons.Library, actions.onOpenLibrary,
            listOfNotNull(
                "Library",
                counts.memories.takeIf { it > 0 }?.let { plural(it, "note", "notes") },
                counts.skills.takeIf { it > 0 }?.let { plural(it, "skill", "skills") },
                counts.toReview.takeIf { it > 0 }?.let { "$it to review" },
            ).joinToString(", "),
            attention = counts.toReview > 0,
        ),
        Door(
            "Tasks", LocusIcons.Clock, actions.onOpenTasks,
            "Tasks, " + when {
                counts.tasksTotal == 0 -> "none yet"
                counts.tasksActive == 0 -> "all paused"
                else -> "${counts.tasksActive} scheduled"
            },
        ),
        Door("Files", LocusIcons.Folder, actions.onOpenWorkspace, "Workspace files and Linux"),
        Door("Activity", LocusIcons.Activity, actions.onOpenActivity, "Activity, everything it did"),
        Door("Insights", LocusIcons.Sparkle, actions.onOpenAnalytics, "Insights: tokens, speed and tools"),
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        doors.forEach { d ->
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(Radius.control))
                    .background(Locus.colors.card)
                    .border(0.5.dp, Locus.colors.hairline, RoundedCornerShape(Radius.control))
                    .pressable(d.onClick)
                    .semantics(mergeDescendants = true) { contentDescription = d.description }
                    .padding(vertical = Spacing.md, horizontal = Spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    Icon(d.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    if (d.attention) {
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(8.dp)
                                .background(Locus.colors.caution, CircleShape),
                        )
                    }
                }
                Text(d.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A chat in a list. Tap opens; long-press (or the menu button, where [showMenuButton]) offers pin,
 * rename and delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRowItem(row: ConversationRow, actions: RowActions, showMenuButton: Boolean = false, hit: ChatSearchHit? = null) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        ListRow(
            title = row.conversation.title,
            modifier = Modifier
                .combinedClickable(
                    onClick = { hit?.message?.let { actions.openAt(row, it.id) } ?: actions.open(row) },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                    onLongClickLabel = "Chat options",
                )
                .semantics { onLongClick(label = "Chat options") { menu = true; true } },
            leading = { ProviderBadge(row.providerName, row.providerUrl, row.onDevice, size = 36.dp, model = row.model) },
            meta = {
                val match = hit?.message
                when {
                    match != null -> SearchMatch(match)
                    row.needsApproval -> StatusLine("Needs your approval", Tone.Caution)
                    row.running -> StatusLine("Working", Tone.Live)
                    row.interrupted -> StatusLine("Stopped early · tap to continue", Tone.Caution)
                    else -> Text(
                        listOf(relativeTime(row.conversation.updatedAt), if (row.isScheduledRun) "Scheduled task" else row.providerName)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailing = if (showMenuButton) {
                {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(LocusIcons.More, contentDescription = "Chat options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                null
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            DropdownMenuItem(
                text = { Text(if (row.conversation.pinned) "Unpin" else "Pin to top") },
                leadingIcon = { Icon(LocusIcons.Pin, null) },
                onClick = { menu = false; actions.pin(row) },
            )
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(LocusIcons.Note, null) }, onClick = { menu = false; actions.rename(row) })
            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(LocusIcons.Trash, null) }, onClick = { menu = false; actions.delete(row) })
        }
    }
}

/** Under a search result's title: the matching words of its newest matching message, then whose and when. */
@Composable
private fun SearchMatch(match: MessageMatch) {
    Text(
        match.snippet.annotated(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        listOfNotNull(
            if (match.role == Role.USER) "You" else "Locus",
            relativeTime(match.createdAt),
            "On another branch".takeUnless { match.onShownBranch },
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The snippet with its matches in the text colour, semibold, on a light wash of the accent. */
@Composable
private fun Highlighted.annotated(): AnnotatedString {
    val style = SpanStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    )
    return remember(this, style) {
        buildAnnotatedString {
            append(text)
            matches.forEach { addStyle(style, it.first, it.last + 1) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// All chats
// ---------------------------------------------------------------------------------------------

/**
 * Every chat, built for hundreds: a search field on top, pinned first, then date sections
 * (Today, Yesterday, Previous 7 days, Previous 30 days, then by month). Each row is its own slice
 * of the section's card so the list stays lazy.
 */
@Composable
private fun AllChats(
    state: ConversationsUiState.Ready,
    focusSearch: Boolean,
    actions: RowActions,
    onSearch: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val query = state.query.orEmpty()
    val searching = query.isNotBlank()
    // Search results keep their own order (newest match first) instead of date sections.
    val sections = remember(state.rows, searching) { if (searching) emptyList() else chatSections(state.rows, includePinned = true) }
    val titlesOnly = remember(query) { searching && SearchQuery.parse(query).match == null }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                "Chats",
                onBack = onBack,
                status = {
                    Text(
                        if (state.rows.size == 1) "1 chat" else "${state.rows.size} chats",
                        style = MonoCaption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.xs, bottom = padding.calculateBottomPadding() + Spacing.xxl),
        ) {
            item(key = "search") { SearchField(query, focusSearch, onSearch) }
            if (searching && !state.searchPending && state.rows.isEmpty()) {
                item(key = "none") {
                    SectionNote(
                        if (titlesOnly) {
                            "No chat titles start with “${query.trim()}”. Type another letter to search messages too."
                        } else {
                            "No chats mention “${query.trim()}”. Words match from their start; put quotes around an exact phrase."
                        },
                        Modifier.padding(start = Spacing.xs, top = Spacing.lg),
                    )
                }
            }
            if (searching && state.rows.isNotEmpty()) {
                item(key = "results-label") { SectionLabel(if (titlesOnly) "Titles · type more to search messages" else "Newest first", Modifier.padding(start = 0.dp)) }
                state.rows.forEachIndexed { i, row ->
                    item(key = "hit-${row.conversation.id}") {
                        Box(Modifier.cardSegment(i, state.rows.size)) {
                            Column {
                                if (i > 0) GroupDivider(inset = Spacing.lg + 36.dp + Spacing.md)
                                ConversationRowItem(row, actions, showMenuButton = true, hit = state.hits[row.conversation.id])
                            }
                        }
                    }
                }
            }
            sections.forEach { (label, rows) ->
                item(key = "label-$label") { SectionLabel(label, Modifier.padding(start = 0.dp)) }
                rows.forEachIndexed { i, row ->
                    item(key = "$label-${row.conversation.id}") {
                        Box(Modifier.cardSegment(i, rows.size)) {
                            Column {
                                if (i > 0) GroupDivider(inset = Spacing.lg + 36.dp + Spacing.md)
                                ConversationRowItem(row, actions, showMenuButton = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun chatSections(rows: List<ConversationRow>, includePinned: Boolean, now: Long = System.currentTimeMillis()): List<Pair<String, List<ConversationRow>>> {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val month = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
    val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    fun label(millis: Long): String {
        val day: LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val ago = today.toEpochDay() - day.toEpochDay()
        return when {
            ago <= 0 -> "Today"
            ago == 1L -> "Yesterday"
            ago < 7 -> "Previous 7 days"
            ago < 30 -> "Previous 30 days"
            day.year == today.year -> day.format(month)
            else -> day.format(monthYear)
        }
    }
    val pinned = if (includePinned) rows.filter { it.conversation.pinned } else emptyList()
    val rest = (if (includePinned) rows.filterNot { it.conversation.pinned } else rows).sortedByDescending { it.conversation.updatedAt }
    // groupBy keeps first-seen order, which is newest first here.
    return listOfNotNull(pinned.takeIf { it.isNotEmpty() }?.let { "Pinned" to it }) +
        rest.groupBy { label(it.conversation.updatedAt) }.toList()
}

@Composable
private fun SearchField(query: String, focus: Boolean, onSearch: (String?) -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(focus) { if (focus) requester.requestFocus() }
    Row(
        Modifier.fillMaxWidth()
            .background(Locus.colors.card, RoundedCornerShape(Radius.sheet))
            .border(0.5.dp, Locus.colors.hairline, RoundedCornerShape(Radius.sheet))
            .padding(start = Spacing.lg, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LocusIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        BasicTextField(
            value = query,
            onValueChange = { onSearch(it) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.md, vertical = Spacing.md + 2.dp).focusRequester(requester),
            decorationBox = { inner ->
                if (query.isEmpty()) Text("Search titles and messages", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            },
        )
        if (query.isNotEmpty()) IconButton(onClick = { onSearch("") }) { Icon(LocusIcons.Close, contentDescription = "Clear search") }
    }
}

/**
 * The primary action, always in reach: the same composer as a chat, so the model, thinking, tools
 * and plan mode can be chosen before the chat exists; they apply to the chat the message starts.
 */
@Composable
private fun AskBar(composer: HomeComposer, actions: ComposerActions, onStart: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var sheet by rememberSaveable { mutableStateOf<ControlSheet?>(null) }
    FloatingLayer { LocusComposer(
        text = text,
        onTextChange = { text = it },
        settings = composer.settings,
        actions = actions,
        sheet = sheet,
        onSheet = { sheet = it },
        onSend = { onStart(text); text = "" },
        modifier = Modifier.navigationBarsPadding().imePadding(),
        attachments = composer.attachments,
        placeholder = "Ask Locus anything",
        imeSend = true,
    ) }
}

/** The latest answer from a scheduled task, so its news is on home rather than only in a notification. */
@Composable
private fun TaskResultCard(result: TaskResult, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    LocusCard(modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(LocusIcons.Clock, size = 24.dp, tint = MaterialTheme.colorScheme.primary)
            Text(result.title, Modifier.weight(1f).padding(start = Spacing.sm), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(relativeTime(result.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            result.summary.lineSequence().filter { it.isNotBlank() }.joinToString(" ").replace(Regex("[*_#`]"), ""),
            Modifier.padding(top = Spacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
