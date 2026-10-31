package dev.locus.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.FloatingLayer
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.accentFor
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.entrance
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.Part
import dev.locus.core.model.toolActivity
import dev.locus.core.model.toolLabel
import dev.locus.core.designsystem.ContextRing
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.compactCount
import dev.locus.core.designsystem.levelColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Composable
fun ChatRoute(
    conversationId: String,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
    draft: String? = null,
    /** Opens another chat (a fork of this one), optionally with a draft in its composer. */
    onOpenChat: (id: String, draft: String?) -> Unit = { _, _ -> },
    /** Open at this message (a search result), switching to its branch when it isn't shown. */
    focusMessageId: String? = null,
) {
    val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = conversationId) { it.create(conversationId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Read only inside the live bubble (through the lambda), so deltas don't recompose this screen.
    val streaming = viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingText = remember(streaming) { { streaming.value } }
    val reasoning = viewModel.reasoningText.collectAsStateWithLifecycle()
    val reasoningText = remember(reasoning) { { reasoning.value } }
    ChatScreen(
        state = state,
        streamingText = streamingText,
        onBack = onBack,
        onOpenBrowser = onOpenBrowser,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onAnswer = viewModel::answer,
        onDismissMessages = viewModel::dismissMessages,
        onResume = viewModel::resume,
        onSetProvider = viewModel::setProvider,
        onAttach = viewModel::attach,
        onRemoveAttachment = viewModel::removeAttachment,
        onCompact = viewModel::compactNow,
        reasoningText = reasoningText,
        controls = ChatControlActions(
            setModel = viewModel::setModel,
            prepareModels = viewModel::prepareModels,
            toggleFavorite = { viewModel.toggleFavorite(it) },
            enableCatalog = viewModel::enableCatalog,
            setEffort = { viewModel.setEffort(it) },
            setPlanMode = { viewModel.setPlanMode(it) },
            setToolEnabled = { key, on -> viewModel.setToolEnabled(key, on) },
            approvePlan = viewModel::approvePlan,
            cancelPlan = { viewModel.cancelPlan() },
            runAnyway = viewModel::runAnyway,
            dismissReviewerBlock = viewModel::dismissReviewerBlock,
        ),
        branches = BranchActions(
            regenerate = { viewModel.regenerate(it) },
            edit = viewModel::edit,
            switchTo = { viewModel.switchBranch(it) },
            fork = { id, user -> viewModel.fork(id, user, onOpenChat) },
        ),
        browserView = { modifier ->
            val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
            if (activity != null) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { dev.locus.core.browser.WebViewHost(activity, viewModel.browser) },
                    modifier = modifier,
                    // Detach on close so the browser keeps working off screen.
                    onRelease = { host -> host.release() },
                )
            }
        },
        draft = draft,
        focusMessageId = focusMessageId,
    )
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
    /** The reply streaming in now; a lambda so only the live bubble reads (and recomposes on) it. */
    streamingText: () -> String = { state.run.streamingText },
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    onDismissMessages: () -> Unit,
    onResume: () -> Unit,
    onSetProvider: (String) -> Unit,
    onAttach: (List<android.net.Uri>) -> Unit = {},
    onRemoveAttachment: (Part.Attachment) -> Unit = {},
    onCompact: () -> Unit = {},
    browserView: (@Composable (Modifier) -> Unit)? = null,
    draft: String? = null,
    /** The live reasoning; a lambda for the same reason as [streamingText]. */
    reasoningText: () -> String = { state.run.reasoning },
    controls: ChatControlActions = ChatControlActions(),
    branches: BranchActions = BranchActions(),
    focusMessageId: String? = null,
) {
    // Opening at a search result: until it's been shown, keep the list where we put it.
    var focus by rememberSaveable { mutableStateOf(focusMessageId) }
    var flashKey by remember { mutableStateOf<String?>(null) }
    var panel by rememberSaveable { mutableStateOf<LivePanelKind?>(null) }
    // Hoisted so the empty-state suggestions can fill the composer.
    var text by rememberSaveable { mutableStateOf(draft.orEmpty()) }
    var sheet by rememberSaveable { mutableStateOf<ControlSheet?>(null) }
    // Plans the user cancelled here; Cancel also turns plan mode off, which is what persists.
    var cancelledPlans by rememberSaveable { mutableStateOf(listOf<String>()) }
    // The user message being edited: sending makes a branch beside it. Null sends a new message.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // Branch actions wait while the agent works: a run continues the branch it started on.
    val canBranch = !state.run.running
    var showTokens by remember { mutableStateOf(false) }
    if (showTokens) {
        TokensSheet(
            state.tokens,
            state.provider,
            canCompact = !state.run.running && state.items.size >= 4,
            onCompact = { showTokens = false; onCompact() },
            onDismiss = { showTokens = false },
        )
    }
    val rows = remember(state.items) { groupSteps(state.items) }
    val listState = rememberLazyListState()

    // The runner clears the streamed text as soon as the message is saved, but the saved message reaches
    // us a moment later through the database. Keep showing the streamed text until the transcript changes
    // (or briefly, if it was discarded by a retry), so the reply doesn't vanish and reappear.
    val held = remember { HeldStream() }
    val signature = rememberUpdatedState(state.items.size to state.items.lastOrNull()?.key)
    val expiredHold = remember { mutableStateOf<String?>(null) }
    // What the live bubble shows. Computed in a derived state (not read here) so per-delta changes only
    // reach the composables that read it.
    val shownStream = remember {
        derivedStateOf {
            val live = streamingText()
            when {
                live.isNotEmpty() -> live.also { held.text = it; held.signature = signature.value }
                held.text.isNotEmpty() && held.signature == signature.value && expiredHold.value !== held.text -> held.text
                else -> "".also { held.text = "" }
            }
        }
    }
    val hasStream by remember { derivedStateOf { shownStream.value.isNotEmpty() } }
    val holding by remember { derivedStateOf { streamingText().isEmpty() && shownStream.value.isNotEmpty() } }
    LaunchedEffect(holding) {
        if (holding) {
            val text = held.text
            delay(1_500)
            expiredHold.value = text
        }
    }
    val showLive = state.run.running || hasStream
    val itemCount = rows.size + if (showLive) 1 else 0
    val follower = rememberTranscriptFollower(
        list = listState,
        ready = !state.loading && itemCount > 0,
        contentVersion = Triple(rows.size, showLive, state.run.activeTool),
        streamLength = { shownStream.value.length },
        lastUserKey = remember(state.items) { state.items.lastOrNull { it is ChatItem.User }?.key },
        holdPosition = focus != null,
    )
    val scope = rememberCoroutineScope()

    // Bring a search result's message into view: switch to its branch if it's on another one (once),
    // then scroll to its row and wash it briefly so the eye finds it.
    var askedBranch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focus, rows, state.loading) {
        val target = focus ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        val index = rows.indexOfFirst { row -> row.containsKey(target) }
        when {
            index >= 0 -> {
                listState.scrollToItem(index)
                focus = null
                flashKey = rows[index].key
                kotlinx.coroutines.delay(1_600)
                flashKey = null
            }
            !askedBranch -> { askedBranch = true; branches.switchTo(target) }
            else -> focus = null // not in this chat any more (deleted): just show the chat
        }
    }

    // A panel button shows only when its panel has something to show: commands this chat ran (or one
    // running now), and a page actually open in the browser that this chat used (or browsing now).
    // An open panel keeps its button so it can be closed.
    val activeTool = state.run.activeTool
    val usedTerminal = remember(state.items) { state.items.any { it is ChatItem.ToolCall && it.name in TERMINAL_TOOLS } } ||
        activeTool in TERMINAL_TOOLS || panel == LivePanelKind.Terminal
    val chatUsedBrowser = remember(state.items) { state.items.any { it is ChatItem.ToolCall && it.name.isBrowserTool() } }
    val browsingNow = activeTool?.isBrowserTool() == true
    val usedBrowser = (chatUsedBrowser && state.browserHasPage) || browsingNow
    // Nothing to show any more (e.g. the app restarted, so no page is open): close the browser panel.
    LaunchedEffect(state.browserHasPage, browsingNow) {
        if (panel == LivePanelKind.Browser && !state.browserHasPage && !browsingNow) panel = null
    }

    // The transcript list; a lambda so it can share the screen with a live panel.
    val transcript: @Composable (Modifier, androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp) -> Unit = { modifier, top, bottom ->
        Box(modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = top + Spacing.sm,
                    bottom = bottom + Spacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(rows, key = { it.key }) { row ->
                    // Replies swap in for the streamed turn in the same frame: no fade, so nothing flashes.
                    val swap = row is Row.Single && row.item is ChatItem.Assistant
                    val wash by androidx.compose.animation.animateColorAsState(
                        if (row.key == flashKey) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent,
                        label = "search result",
                    )
                    Box(
                        (if (swap) Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null) else Modifier.animateItem())
                            .background(wash, RoundedCornerShape(Radius.card)),
                    ) {
                        when (row) {
                            is Row.Single -> when (val item = row.item) {
                                is ChatItem.User -> UserBubble(
                                    item.text, item.attachments,
                                    onEdit = { editing = item.key; text = item.text },
                                    planMode = item.planMode,
                                    branch = item.branch,
                                    branchEnabled = canBranch,
                                    onSwitch = branches.switchTo,
                                    onFork = { branches.fork(item.key, true) },
                                )
                                is ChatItem.Assistant -> if (item.text.isNotBlank()) {
                                    AssistantText(
                                        item.text, item.usage,
                                        turn = item.turn,
                                        branchEnabled = canBranch,
                                        onRegenerate = branches.regenerate,
                                        onSwitch = branches.switchTo,
                                        onFork = { branches.fork(item.key, false) },
                                    )
                                }
                                is ChatItem.TurnEnd -> if (canBranch) TurnEndRow(item.turn, onRegenerate = branches.regenerate, onSwitch = branches.switchTo)
                                is ChatItem.Checkpoint -> CheckpointCard(item)
                                is ChatItem.Thought -> if (item.inSteps) StepsGroup(listOf(item), activeTool = activeTool) else ThoughtRow(item)
                                is ChatItem.Plan -> PlanCard(
                                    item,
                                    actionable = item.status == PlanStatus.Waiting && item.key !in cancelledPlans && state.pendingApproval == null,
                                    onApprove = controls.approvePlan,
                                    onEdit = { text = "Change the plan: " },
                                    onCancel = { cancelledPlans = cancelledPlans + item.key; controls.cancelPlan() },
                                )
                                is ChatItem.ToolCall, is ChatItem.Note -> StepsGroup(listOf(item), activeTool = activeTool)
                                is ChatItem.SentFile -> SentFileCard(item)
                            }
                            is Row.Steps -> StepsGroup(row.steps, activeTool = activeTool)
                        }
                    }
                }
                if (showLive) item(key = "live") {
                    Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { LiveTurn(state, streamed = { shownStream.value }, reasoning = reasoningText) }
                }
            }
            // Live panel toggles: small round buttons on the right edge, above the composer.
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = Spacing.sm, bottom = bottom + Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (usedTerminal) PanelToggle(
                    LocusIcons.Terminal, "Terminal",
                    selected = panel == LivePanelKind.Terminal,
                    live = activeTool in TERMINAL_TOOLS,
                    onClick = { panel = if (panel == LivePanelKind.Terminal) null else LivePanelKind.Terminal },
                )
                if (usedBrowser) PanelToggle(
                    LocusIcons.Globe, "Browser",
                    selected = panel == LivePanelKind.Browser,
                    live = activeTool?.isBrowserTool() == true,
                    onClick = { panel = if (panel == LivePanelKind.Browser) null else LivePanelKind.Browser },
                )
            }
            AnimatedVisibility(
                visible = follower.hasUnseen && !follower.following,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + Spacing.md),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                JumpToLatest(onClick = { scope.launch { follower.resume() } })
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(LocusIcons.Back, contentDescription = "Back") } },
                actions = {
                    if (state.tokens.context > 0) ContextMeter(state.tokens, onClick = { showTokens = true })
                },
                title = {
                    Column {
                        Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        state.provider?.let { ModelChip(it, enabled = !state.run.running && state.providers.isNotEmpty(), onClick = { sheet = ControlSheet.Model }) }
                    }
                },
            )
        },
        bottomBar = {
            // Floats over the transcript: no band behind it, but each card on it is opaque.
            FloatingLayer { Column(Modifier.navigationBarsPadding().imePadding()) {
                val bannerModifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                // After an error or an interrupted run, the transcript is intact: run the model again from where it ends.
                val canResume = !state.run.running && (state.run.interrupted || state.run.error != null)
                MessagesBanner(
                    state.run.error,
                    state.run.notice,
                    onDismissMessages,
                    bannerModifier,
                    onContinue = if (canResume) onResume else null,
                    continueLabel = if (state.run.error != null) "Try again" else "Continue",
                )
                // The chat ends on the user's message with nothing running (the error was dismissed, or the
                // reply never came): offer to run it again, e.g. after switching to another model.
                if (!canResume && !state.run.running && state.run.notice == null && state.items.lastOrNull() is ChatItem.User) {
                    Banner("No reply to your last message.", Tone.Neutral, bannerModifier) { TextButton(onClick = onResume) { Text("Run again") } }
                }
                state.browserHandoff?.let {
                    Banner(it, Tone.Caution, bannerModifier, title = "Locus needs you in the browser") { Button(onClick = onOpenBrowser) { Text("Open") } }
                }
                editing?.let {
                    Banner(
                        "Sending makes a new branch; the original and its replies stay. Close to send it as a new message instead.",
                        Tone.Brand,
                        bannerModifier,
                        title = "Editing a message",
                    ) {
                        IconButton(onClick = { editing = null }) { Icon(LocusIcons.Close, contentDescription = "Stop editing") }
                    }
                }
                ReviewerBlockChips(state.run.reviewerBlocks, controls.runAnyway, controls.dismissReviewerBlock, bannerModifier)
                state.pendingApproval?.let { ApprovalCard(it, onAnswer, bannerModifier) }
                // The current turn's checklist, while there's something left to do or it's being worked on.
                state.todos?.takeIf { todos -> state.run.running || todos.any { it.status != dev.locus.core.model.TodoStatus.COMPLETED } }?.let { todos ->
                    ChecklistCard(todos, running = state.run.running, modifier = bannerModifier)
                }
                if (!state.run.running && state.pendingApproval == null && text.isBlank() && state.run.suggestions.isNotEmpty()) {
                    FollowUps(state.run.suggestions, onPick = { onSend(it) })
                }
                LocusComposer(
                    text = text,
                    onTextChange = { text = it },
                    settings = ComposerSettings(state.provider, state.providers, state.controls, state.connectors, state.picker),
                    actions = remember(controls, onAttach, onRemoveAttachment, onCompact) {
                        ComposerActions(
                            setModel = controls.setModel,
                            prepareModels = controls.prepareModels,
                            toggleFavorite = controls.toggleFavorite,
                            enableCatalog = controls.enableCatalog,
                            setEffort = controls.setEffort,
                            setPlanMode = controls.setPlanMode,
                            setToolEnabled = controls.setToolEnabled,
                            onAttach = onAttach,
                            onRemoveAttachment = onRemoveAttachment,
                            onCompact = onCompact,
                        )
                    },
                    sheet = sheet,
                    onSheet = { sheet = it },
                    attachments = state.pendingAttachments,
                    running = state.run.running,
                    // A background helper waiting for approval must not lock the user out of their own chat.
                    enabled = state.pendingApproval.let { it == null || (it.origin != null && !state.run.running) },
                    canCompact = !state.run.running && state.items.size >= 4,
                    onSend = {
                        val target = editing
                        if (target != null && canBranch) branches.edit(target, text) else onSend(text)
                        editing = null
                        text = ""
                    },
                    onStop = onStop,
                )
            } }
        },
    ) { padding ->
        if (!state.loading && state.items.isEmpty() && !state.run.running && panel == null) {
            Suggestions(Modifier.padding(padding), onPick = { text = it })
            return@Scaffold
        }
        // The transcript, optionally beside a live view: above it on phones, beside it on wide screens.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val panelHeight = maxHeight * 0.42f
            val openPanel = panel
            when {
                openPanel == null -> transcript(Modifier.fillMaxSize(), padding.calculateTopPadding(), padding.calculateBottomPadding())
                wide -> Row(Modifier.fillMaxSize()) {
                    transcript(Modifier.weight(1f).fillMaxHeight(), padding.calculateTopPadding(), padding.calculateBottomPadding())
                    LivePanel(
                        openPanel, state, onClose = { panel = null }, onOpenBrowser = onOpenBrowser, browserView = browserView,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(), end = Spacing.lg),
                    )
                }
                else -> Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                    LivePanel(
                        openPanel, state, onClose = { panel = null }, onOpenBrowser = onOpenBrowser, browserView = browserView,
                        modifier = Modifier.fillMaxWidth().height(panelHeight).padding(horizontal = Spacing.lg),
                    )
                    transcript(Modifier.weight(1f), 0.dp, padding.calculateBottomPadding())
                }
            }
        }
    }
}


/** Callbacks for branches: regenerate (from a message id), edit, switch to a sibling, fork (message id, is a user message). */
internal class BranchActions(
    val regenerate: (String) -> Unit = {},
    val edit: (messageId: String, text: String) -> Unit = { _, _ -> },
    val switchTo: (String) -> Unit = {},
    val fork: (messageId: String, userMessage: Boolean) -> Unit = { _, _ -> },
)

/** Callbacks for the composer's controls and the plan card. */
internal class ChatControlActions(
    val setModel: (providerId: String, model: String?) -> Unit = { _, _ -> },
    val prepareModels: () -> Unit = {},
    val toggleFavorite: (dev.locus.core.model.ModelRef) -> Unit = {},
    val enableCatalog: () -> Unit = {},
    val setEffort: (dev.locus.core.model.Effort?) -> Unit = {},
    val setPlanMode: (Boolean) -> Unit = {},
    val setToolEnabled: (key: String, enabled: Boolean) -> Unit = { _, _ -> },
    val approvePlan: () -> Unit = {},
    val cancelPlan: () -> Unit = {},
    /** Overrides the safety reviewer for a blocked call (by call id). */
    val runAnyway: (String) -> Unit = {},
    val dismissReviewerBlock: (String) -> Unit = {},
)

/** The model answering this chat: its badge and name; tap to open the model sheet. */
@Composable
private fun ModelChip(current: ChatProvider, enabled: Boolean, onClick: () -> Unit) {
    val canSwitch = enabled
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(Radius.chip)).clickable(enabled = canSwitch, onClickLabel = "Change model", onClick = onClick).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderBadge(current.name, current.baseUrl, current.onDevice, size = 16.dp, model = current.model)
            Text(
                current.model.ifBlank { current.name },
                Modifier.padding(start = 6.dp).widthIn(max = 150.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canSwitch) Text(" ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A transcript row: one item, or a run of a turn's work (tool calls and the narration between them). */
private sealed interface Row {
    val key: String

    data class Single(val item: ChatItem) : Row {
        override val key get() = item.key
    }

    /** Keyed by its first step, so the group keeps its identity (and expanded state) as steps are added. */
    data class Steps(val steps: List<ChatItem>) : Row {
        override val key get() = "steps:" + steps.first().key
    }

    /** Whether this row shows the message with [key] (its own item, or one of its steps). */
    fun containsKey(key: String): Boolean = when (this) {
        is Single -> item.key == key
        is Steps -> steps.any { it.key == key }
    }
}

private fun ChatItem.isStep() = this is ChatItem.ToolCall || this is ChatItem.Note || (this is ChatItem.Thought && inSteps)

private fun groupSteps(items: List<ChatItem>): List<Row> {
    val rows = mutableListOf<Row>()
    val run = mutableListOf<ChatItem>()
    fun flush() {
        if (run.isNotEmpty()) rows += Row.Steps(run.toList())
        run.clear()
    }
    items.forEach { if (it.isStep()) run += it else { flush(); rows += Row.Single(it) } }
    flush()
    return rows
}

/**
 * A turn's work in one card: tool calls in order, with the model's narration between them as quiet
 * notes. Longer runs collapse to a summary (one tile per kind of tool, and the latest note).
 */
@Composable
private fun StepsGroup(steps: List<ChatItem>, activeTool: String?) {
    val calls = steps.filterIsInstance<ChatItem.ToolCall>()
    fun running(call: ChatItem.ToolCall) = activeTool == call.name && call.result == null

    @Composable
    fun StepList(items: List<ChatItem>, leadingDivider: Boolean) {
        items.forEachIndexed { i, step ->
            if (i > 0 || leadingDivider) GroupDivider(inset = Spacing.md)
            when (step) {
                // A helper (sub-agent) is a nested row with its own live state and transcript.
                is ChatItem.ToolCall -> if (step.name == dev.locus.core.model.SubagentTools.SPAWN || step.name == dev.locus.core.model.SubagentTools.SEND) SubagentRow(step) else ToolCallRow(step, running = running(step))
                is ChatItem.Note -> StepNote(step.text)
                is ChatItem.Thought -> ThoughtRow(step, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
                else -> Unit
            }
        }
    }

    if (calls.size <= 2) {
        // A step that only thought (its tool calls were the checklist) needs no card around it.
        if (calls.isEmpty() && steps.all { it is ChatItem.Thought }) {
            Column { steps.forEach { ThoughtRow(it as ChatItem.Thought) } }
            return
        }
        // Short runs are shown in full; a summary would hide as much as it saves.
        LocusCard(contentPadding = PaddingValues(0.dp)) { StepList(steps, leadingDivider = false) }
        return
    }
    var expanded by rememberSaveable(steps.first().key) { mutableStateOf(false) }
    val failed = calls.count { it.result?.isError == true && it.result.decision != ApprovalDecision.USER_DENIED }
    val anyRunning = calls.any(::running)
    val latestNote = steps.lastOrNull { it is ChatItem.Note } as ChatItem.Note?
    LocusCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // One tile per kind of tool used, so the summary shows what sort of work it was.
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                calls.map { accentFor(it.name) }.distinctBy { it.first }.take(4).forEach { (accent, icon) ->
                    AccentTile(accent, icon, size = 24.dp, modifier = Modifier.border(1.5.dp, Locus.colors.card, RoundedCornerShape(7.dp)))
                }
            }
            Text(
                "${calls.size} steps",
                modifier = Modifier.padding(start = Spacing.sm).weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (anyRunning && !expanded) StatusDot(Tone.Live, Modifier.padding(end = Spacing.xs), size = 6.dp)
            if (failed > 0) Text("$failed failed  ", style = MaterialTheme.typography.labelMedium, color = Locus.colors.critical)
            Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (!expanded && latestNote != null) {
            Text(
                latestNote.text.asNote(),
                Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(expanded) {
            Column { StepList(steps, leadingDivider = true) }
        }
    }
}

/** The model's narration between steps: muted and compact, so it reads as a step, not a reply. */
@Composable
private fun StepNote(text: String) {
    Text(
        text.asNote(),
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Narration is shown as plain text: drop the emphasis and code markers it sometimes carries. */
private fun String.asNote(): String = trim().replace("**", "").replace("__", "").replace("`", "")

private data class Suggestion(val text: String, val tool: String)

private val SUGGESTIONS = listOf(
    Suggestion("Search the web for this week's top tech news and summarise it", "web_search"),
    Suggestion("Make a packing list for a 3-day trip and save it to my workspace", "write_file"),
    Suggestion("Write a Python script that renames my photos by date, then run it", "run_command"),
    Suggestion("Plan my week: ask me what's on, then add the events to my calendar", "device_calendar_event"),
)

@Composable
private fun Suggestions(modifier: Modifier, onPick: (String) -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        Text("What should we work on?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.entrance(0))
        Text(
            "Locus can use the web, files, a Linux shell and your phone's apps. It asks before anything risky.",
            Modifier.padding(bottom = Spacing.md).entrance(1),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SUGGESTIONS.forEachIndexed { i, suggestion ->
            LocusCard(Modifier.entrance(i + 2), onClick = { onPick(suggestion.text) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (accent, icon) = accentFor(suggestion.tool)
                    AccentTile(accent, icon, size = 32.dp)
                    Text(suggestion.text, Modifier.padding(start = Spacing.md), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun UserBubble(
    text: String,
    attachments: List<Part.Attachment>,
    onEdit: () -> Unit,
    planMode: Boolean = false,
    branch: dev.locus.core.model.Branch? = null,
    branchEnabled: Boolean = true,
    onSwitch: (String) -> Unit = {},
    onFork: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        attachments.forEach { AttachmentPreview(it, onRemove = null) }
        if (planMode) Text("Plan mode", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (text.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(Radius.card + 4.dp, Radius.card + 4.dp, 6.dp, Radius.card + 4.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                // Long-press for actions; history is append-only, so "edit" adds a branch beside this message.
                Box {
                    Text(
                        text,
                        Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = "Message actions")
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            leadingIcon = { Icon(LocusIcons.Copy, null) },
                            onClick = {
                                menu = false
                                scope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Locus", text))) }
                            },
                        )
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(LocusIcons.Note, null) }, enabled = branchEnabled, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Fork from here") }, leadingIcon = { Icon(LocusIcons.Branch, null) }, onClick = { menu = false; onFork() })
                    }
                }
            }
        }
        branch?.let { BranchSwitcher(it, enabled = branchEnabled, onSwitch = onSwitch) }
    }
}

/** ‹ 2/3 › under a message that has alternatives: which one is shown, and the neighbours to switch to. */
@Composable
private fun BranchSwitcher(branch: dev.locus.core.model.Branch, enabled: Boolean, onSwitch: (String) -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { branch.previous?.let(onSwitch) }, enabled = enabled && branch.previous != null, modifier = Modifier.size(32.dp)) {
            Icon(LocusIcons.ChevronLeft, contentDescription = "Previous version", modifier = Modifier.size(16.dp), tint = tint)
        }
        Text(
            "${branch.index + 1}/${branch.count}",
            style = MonoCaption,
            color = tint,
            modifier = Modifier.semantics { contentDescription = "Version ${branch.index + 1} of ${branch.count}" },
        )
        IconButton(onClick = { branch.next?.let(onSwitch) }, enabled = enabled && branch.next != null, modifier = Modifier.size(32.dp)) {
            Icon(LocusIcons.ChevronRight, contentDescription = "Next version", modifier = Modifier.size(16.dp), tint = tint)
        }
    }
}

/** A turn that ended without reply text: its branch controls on their own. */
@Composable
private fun TurnEndRow(turn: TurnBranch, onRegenerate: (String) -> Unit, onSwitch: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onRegenerate(turn.from) }, modifier = Modifier.offset(x = (-8).dp).size(32.dp)) {
            Icon(LocusIcons.Regenerate, contentDescription = "Regenerate", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        turn.branch?.let { BranchSwitcher(it, enabled = true, onSwitch = onSwitch) }
    }
}

/** Image thumbnail (decoded small, off the main thread) or a file chip. */
@Composable
private fun AttachmentPreview(a: Part.Attachment, onRemove: (() -> Unit)?) {
    val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.path) {
        if (a.mimeType.startsWith("image/")) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                android.graphics.BitmapFactory.decodeFile(a.path, opts)?.asImageBitmap()
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(Spacing.xs)) {
            val bitmap = thumb
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = a.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(if (onRemove == null) 160.dp else 48.dp).clip(MaterialTheme.shapes.small),
                )
            } else {
                Icon(LocusIcons.File, contentDescription = null, modifier = Modifier.padding(Spacing.sm).size(20.dp))
                Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(LocusIcons.Close, contentDescription = "Remove ${a.name}", modifier = Modifier.size(18.dp)) }
        }
    }
}

/**
 * A reply, with quiet actions under it: copy, regenerate, a menu with Fork (long-press on the reply
 * itself selects text), and the ‹ i/n › switcher when the turn has alternatives.
 */
@Composable
private fun AssistantText(
    text: String,
    usage: TurnUsage? = null,
    turn: TurnBranch? = null,
    branchEnabled: Boolean = true,
    onRegenerate: (String) -> Unit = {},
    onSwitch: (String) -> Unit = {},
    onFork: () -> Unit = {},
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        AssistantMarkdown(text)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Locus", text)))
                        copied = true
                    }
                },
                modifier = Modifier.offset(x = (-8).dp).size(32.dp),
            ) {
                Icon(
                    if (copied) LocusIcons.Check else LocusIcons.Copy,
                    contentDescription = if (copied) "Copied" else "Copy reply",
                    modifier = Modifier.size(16.dp),
                    tint = tint,
                )
            }
            if (turn != null && branchEnabled) {
                IconButton(onClick = { onRegenerate(turn.from) }, modifier = Modifier.offset(x = (-8).dp).size(32.dp)) {
                    Icon(LocusIcons.Regenerate, contentDescription = "Regenerate", modifier = Modifier.size(16.dp), tint = tint)
                }
            }
            Box(Modifier.offset(x = (-8).dp)) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(LocusIcons.More, contentDescription = "More reply actions", modifier = Modifier.size(16.dp), tint = tint)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Fork into new chat") }, leadingIcon = { Icon(LocusIcons.Branch, null) }, onClick = { menu = false; onFork() })
                }
            }
            turn?.branch?.let { BranchSwitcher(it, enabled = branchEnabled, onSwitch = onSwitch) }
            // What this turn cost: the last request's prompt, everything the turn wrote, and how much
            // of all its prompts came from cache.
            usage?.let { u ->
                Text(
                    buildString {
                        append("${compactCount(u.lastInput.toLong())} in · ${compactCount(u.output)} out")
                        if (u.cacheRead > 0 && u.input > 0) append(" · ${u.cacheRead * 100 / u.input}% cached")
                    },
                    // One line: next to the branch switcher it shortens rather than wrapping under itself.
                    modifier = Modifier.weight(1f, fill = false),
                    style = MonoCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One tool call: its kind's tile, what it did in plain words, and its state. Tap for the details. */
@Composable
private fun ToolCallRow(item: ChatItem.ToolCall, running: Boolean) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val decision = item.result?.decision
    val refused = decision == ApprovalDecision.USER_DENIED || decision == ApprovalDecision.POLICY_DENIED || decision == ApprovalDecision.REVIEWER_DENIED
    val failed = item.result?.isError == true && !refused
    val done = item.result != null && !failed && !refused
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (accent, accentIcon) = accentFor(item.name)
            AccentTile(accent, accentIcon, size = 24.dp)
            Text(
                toolLabel(item.name, item.argumentsJson, done = done),
                modifier = Modifier.padding(start = Spacing.sm).weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (refused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                running -> StatusDot(Tone.Live)
                done -> Icon(LocusIcons.Check, contentDescription = "Done", modifier = Modifier.size(16.dp), tint = Locus.colors.live)
                else -> Text(
                    when {
                        item.result == null -> "Not run"
                        decision == ApprovalDecision.USER_DENIED -> "You declined"
                        decision == ApprovalDecision.POLICY_DENIED -> "Blocked"
                        decision == ApprovalDecision.REVIEWER_DENIED -> "Reviewer blocked"
                        else -> "Failed"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) Locus.colors.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                CodeBlock("Input", prettyJson(item.argumentsJson))
                item.result?.let { CodeBlock("Result", it.content.take(4000)) }
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, code: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SelectionContainer {
        Text(
            code,
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(Radius.chip)).padding(Spacing.sm),
            style = CodeTextStyle,
        )
    }
}

/** The turn in progress: streamed text so far, then one live line saying what it's doing. */
@Composable
private fun LiveTurn(state: ChatUiState, streamed: () -> String, reasoning: () -> String = { state.run.reasoning }) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val since = state.run.thinkingSince
        val thinking = since != null && state.run.thoughtMs == null
        if (since != null && state.run.running) LiveThinking(reasoning, since, state.run.thoughtMs)
        // Its own scope: only this reads the text, so a delta recomposes just the bubble.
        LiveText(streamed)
        val hasText by remember { derivedStateOf { streamed().isNotEmpty() } }
        when {
            !state.run.running -> Unit
            state.pendingApproval != null -> StatusLine("Waiting for your approval", Tone.Caution)
            state.run.activeTool != null -> StatusLine(liveActivity(state.run.activeTool.orEmpty()), Tone.Live)
            state.run.progress.isNotBlank() -> StatusLine(state.run.progress.trim().lines().last(), Tone.Live)
            // The thinking block already says it's thinking.
            thinking -> Unit
            !hasText -> StatusLine("Thinking", Tone.Live)
            else -> Unit
        }
    }
}

@Composable
private fun LiveText(streamed: () -> String) {
    val text = streamed()
    if (text.isNotEmpty()) StreamingAssistantMarkdown(text)
}

/** The one decision the user has to make right now; caution-toned so it can't be missed. */
@Composable
private fun ApprovalCard(request: ApprovalRequest, onAnswer: (String, Boolean) -> Unit, modifier: Modifier = Modifier) {
    var showDetails by remember(request.callId) { mutableStateOf(false) }
    val caution = Locus.colors.caution
    LocusCard(modifier, glow = caution, outline = caution.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (accent, icon) = accentFor(request.toolName)
            AccentTile(accent, icon, size = 32.dp)
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                // A helper's request says which helper is asking.
                Text(request.origin?.let { "${it.label} wants to…" } ?: "Allow this?", style = MaterialTheme.typography.titleSmall)
                Text(request.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                // What the safety reviewer made of it, when it was asked.
                request.reviewerNote?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodySmall, color = Locus.colors.caution, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        AnimatedVisibility(showDetails) {
            Column(Modifier.padding(top = Spacing.sm)) { CodeBlock(request.toolName, prettyJson(request.argumentsJson).take(3000)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showDetails = !showDetails }, contentPadding = PaddingValues(horizontal = Spacing.sm)) {
                Text(if (showDetails) "Hide details" else "Details")
            }
            Box(Modifier.weight(1f))
            OutlinedButton(onClick = { onAnswer(request.callId, false) }) { Text("Deny") }
            Button(onClick = { onAnswer(request.callId, true) }, modifier = Modifier.padding(start = Spacing.sm)) { Text("Allow") }
        }
    }
}

@Composable
private fun MessagesBanner(
    error: String?,
    notice: String?,
    onDismiss: () -> Unit,
    modifier: Modifier,
    onContinue: (() -> Unit)? = null,
    continueLabel: String = "Continue",
) {
    val text = error ?: notice ?: return
    Banner(text, if (error != null) Tone.Critical else Tone.Neutral, modifier) {
        onContinue?.let { TextButton(onClick = it) { Text(continueLabel) } }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) { Icon(LocusIcons.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp)) }
    }
}

private val prettyPrinter = Json { prettyPrint = true }

private fun prettyJson(json: String): String =
    runCatching { prettyPrinter.encodeToString(JsonObject.serializer(), Json.parseToJsonElement(json) as JsonObject) }.getOrDefault(json)

/** Context use in the header: a ring and the count. Tap for the breakdown. */
@Composable
private fun ContextMeter(tokens: ChatTokens, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(Radius.chip)).clickable(onClickLabel = "Context and tokens", onClick = onClick).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextRing(tokens.fraction, size = 16.dp)
        Text(
            tokens.fraction?.let { "${(it * 100).toInt()}%" } ?: compactCount(tokens.context.toLong()),
            Modifier.padding(start = 6.dp),
            style = MonoCaption,
            color = tokens.fraction?.takeIf { it >= 0.8f }?.let { levelColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun TokensSheet(tokens: ChatTokens, provider: ChatProvider?, canCompact: Boolean, onCompact: () -> Unit, onDismiss: () -> Unit) {
    LocusSheet(
        title = "Context and tokens",
        subtitle = provider?.let { "${it.name} · ${it.model}" },
        onDismiss = onDismiss,
        action = "Summarise now".takeIf { canCompact },
        onAction = onCompact,
    ) {
        val fraction = tokens.fraction
        Text("In context now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(compactCount(tokens.context.toLong()), style = MaterialTheme.typography.headlineMedium)
            Text(
                tokens.window?.let { " of ${compactCount(it.toLong())} tokens" } ?: " tokens",
                Modifier.padding(start = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fraction != null) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = levelColor(fraction),
                trackColor = Locus.colors.track,
                drawStopIndicator = {},
            )
            Text(
                when {
                    fraction >= 0.8f -> "The context is nearly full. Locus summarises older messages automatically before it runs out."
                    else -> "${(fraction * 100).toInt()}% used. The whole chat is sent with every request; cached parts are cheaper and faster."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "This model's context window isn't known. Pick the model from the list in Settings → Models, or enter it there, to see how full it is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GroupDivider(inset = 0.dp)
        Text("This chat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TokenStat("Requests", tokens.requests.toLong())
        TokenStat("Tokens in", tokens.totalInput, tokens.totalCached.takeIf { it > 0 && tokens.totalInput > 0 }?.let { "${it * 100 / tokens.totalInput}% from cache" })
        TokenStat("Tokens out", tokens.totalOutput)
    }
}

@Composable
private fun TokenStat(label: String, value: Long, note: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (note != null) Text(note, Modifier.padding(end = Spacing.sm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (value < 100_000) "%,d".format(value) else compactCount(value), style = MonoCaption.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize))
    }
}

/** Tappable follow-ups after a reply; tapping one sends it. */
@Composable
private fun FollowUps(ideas: List<String>, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ideas.forEachIndexed { i, idea ->
            Text(
                idea,
                Modifier.entrance(i)
                    .clip(RoundedCornerShape(50))
                    .background(Locus.colors.card)
                    .border(0.5.dp, Locus.colors.hairline, RoundedCornerShape(50))
                    .clickable { onPick(idea) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
    }
}

/** A compaction checkpoint: older messages above stay readable, but the model now works from this summary. */
@Composable
private fun CheckpointCard(item: ChatItem.Checkpoint) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    LocusCard(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LocusIcons.Note, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                "Summarised ${item.replaced} earlier messages",
                Modifier.weight(1f).padding(start = Spacing.sm),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Locus works from this summary now; the messages above are kept for you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(open) { Column(Modifier.padding(top = Spacing.sm)) { AssistantMarkdown(item.summary) } }
    }
}

/** What a live panel beside the chat shows. */
internal enum class LivePanelKind { Terminal, Browser }

private fun String.isBrowserTool() = startsWith("browser_")

/** The streamed text kept on screen after the runner clears it, until the saved message arrives. */
private class HeldStream {
    var text: String = ""
    var signature: Any? = null
}

/** A small round toggle for a live panel, floating at the chat's edge; a live dot while its tool runs. */
@Composable
private fun PanelToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, live: Boolean, onClick: () -> Unit) {
    Box {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(if (selected) 1.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Locus.colors.hairline),
            shadowElevation = 2.dp,
            modifier = Modifier.size(40.dp).semantics { stateDescription = if (selected) "Open" else "Closed" },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp)) }
        }
        if (live) StatusDot(Tone.Live, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp), size = 6.dp)
    }
}

/** Shown when the user has scrolled up and something new arrived below. */
@Composable
private fun JumpToLatest(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(0.5.dp, Locus.colors.hairline),
        shadowElevation = 2.dp,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // The send arrow, pointing down.
            Icon(LocusIcons.Send, contentDescription = "Jump to latest", modifier = Modifier.size(18.dp).rotate(180f))
        }
    }
}

/** What the live line says while a tool runs; the checklist and plan tools aren't "working with files". */
private fun liveActivity(tool: String): String = when (tool) {
    dev.locus.core.model.AgentControlTools.TODO_WRITE -> "Updating the checklist"
    dev.locus.core.model.AgentControlTools.PLAN_PROPOSE -> "Writing the plan"
    else -> toolActivity(tool)
}

private val TERMINAL_TOOLS = setOf("run_command", "ssh_exec", "ssh_job_start", "ssh_job_status", "ssh_job_stop", "linux_service_start", "linux_service_stop", "linux_service_list")

/**
 * A live view next to the chat: the browser the agent drives, or a terminal log of every Linux and
 * SSH command in this chat with its output, newest at the bottom.
 */
@Composable
private fun LivePanel(
    kind: LivePanelKind,
    state: ChatUiState,
    onClose: () -> Unit,
    onOpenBrowser: () -> Unit,
    browserView: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LocusCard(modifier, contentPadding = PaddingValues(0.dp)) {
        // The edge toggles pick the view, so the header just names it: "Terminal", or the page and its address.
        Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (kind == LivePanelKind.Browser) LocusIcons.Globe else LocusIcons.Terminal,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(horizontal = Spacing.sm)) {
                val url = state.browserUrl.substringAfter("://").removeSuffix("/")
                val title = when {
                    kind == LivePanelKind.Terminal -> "Terminal"
                    !state.browserHasPage -> "Browser"
                    else -> state.browserTitle.ifBlank { url.substringBefore('/').ifBlank { "Browser" } }
                }
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (kind == LivePanelKind.Browser && state.browserHasPage && url.isNotEmpty()) {
                    Text(url, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (kind == LivePanelKind.Browser) {
                TextButton(onClick = onOpenBrowser) { Text("Full screen") }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) { Icon(LocusIcons.Close, contentDescription = "Close panel", modifier = Modifier.size(18.dp)) }
        }
        GroupDivider(inset = 0.dp)
        when (kind) {
            LivePanelKind.Terminal -> TerminalLog(state, Modifier.fillMaxSize())
            LivePanelKind.Browser -> if (browserView != null && state.browserHasPage) {
                Box(Modifier.fillMaxSize().clipToBounds()) { browserView(Modifier.fillMaxSize()) }
            } else {
                Text("The browser opens here when Locus visits a page.", Modifier.padding(Spacing.lg), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TerminalLog(state: ChatUiState, modifier: Modifier) {
    val runs = state.items.filterIsInstance<ChatItem.ToolCall>().filter { it.name in TERMINAL_TOOLS }
    val listState = rememberLazyListState()
    LaunchedEffect(runs.size, runs.lastOrNull()?.result != null) { if (runs.isNotEmpty()) listState.animateScrollToItem(runs.size - 1) }
    if (runs.isEmpty()) {
        Text(
            "Commands Locus runs in its Linux environment or on your SSH machines show up here, with their output.",
            modifier.padding(Spacing.lg),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val terminalBg = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.ui.graphics.Color(0xFF0B0D0C) else androidx.compose.ui.graphics.Color(0xFF15181A)
    val prompt = androidx.compose.ui.graphics.Color(0xFF6FD6A0)
    val output = androidx.compose.ui.graphics.Color(0xFFD7DBD8)
    LazyColumn(modifier.background(terminalBg), state = listState, contentPadding = PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(runs, key = { it.key }) { run ->
            val args = runCatching { Json.parseToJsonElement(run.argumentsJson) as JsonObject }.getOrNull()
            fun arg(k: String) = (args?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
            val where = arg("host")?.let { "$it" } ?: "locus"
            val command = when (run.name) {
                "ssh_job_status" -> "# status of job ${arg("id") ?: "(all)"}"
                "ssh_job_stop" -> "# stop job ${arg("id")}"
                "linux_service_list" -> "# services"
                "linux_service_stop" -> "# stop service ${arg("name")}"
                else -> arg("command") ?: run.name
            }
            Column {
                Text("$where \$ $command", style = CodeTextStyle, color = prompt)
                val result = run.result
                when {
                    result == null && state.run.activeTool == run.name -> Text("…running", style = CodeTextStyle, color = output.copy(alpha = 0.6f))
                    result == null -> Text("(not run)", style = CodeTextStyle, color = output.copy(alpha = 0.6f))
                    else -> Text(
                        result.content.take(6_000),
                        style = CodeTextStyle,
                        color = if (result.isError) androidx.compose.ui.graphics.Color(0xFFFF8A80) else output,
                    )
                }
            }
        }
    }
}
