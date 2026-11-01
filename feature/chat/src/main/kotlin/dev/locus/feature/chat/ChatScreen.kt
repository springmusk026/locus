package dev.locus.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.designsystem.rememberLocalNetworkGate
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.FloatingLayer
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.TerminalColors
import dev.locus.core.designsystem.rememberCopyFeedback
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
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
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
    /** Opens Activity narrowed to one task's entries (from a task receipt). */
    onOpenActivity: (dev.locus.core.model.TaskActivityFilter) -> Unit = {},
    /** Opens a project's page (from the project chip under the title). */
    onOpenProject: (String) -> Unit = {},
) {
    val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = conversationId) { it.create(conversationId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val project by viewModel.projectInfo.collectAsStateWithLifecycle()
    val projectNotice by viewModel.projectNotice.collectAsStateWithLifecycle()
    var projectSheet by remember { mutableStateOf(false) }
    val empty = !state.loading && state.items.isEmpty()
    // The chip under the title: the chat's project, or (when there are projects) a way to put a new chat in one.
    val projectHeader: (@Composable () -> Unit)? = when {
        project.current != null -> { { ProjectChip(project.current!!.name, onClick = { projectSheet = true }) } }
        project.all.isNotEmpty() -> { { ProjectChip(if (empty) "Add to a project" else "No project", onClick = { projectSheet = true }) } }
        else -> null
    }
    if (projectSheet) {
        ChatProjectSheet(
            project, empty,
            onDismiss = { projectSheet = false },
            onOpenProject = onOpenProject,
            onMove = viewModel::moveToProject,
            onContinueIn = { id -> viewModel.continueInProject(id) { chat, text -> onOpenChat(chat, text) } },
        )
    }
    projectNotice?.let { note ->
        dev.locus.core.designsystem.LocusSheet(title = "Not moved", subtitle = note, onDismiss = viewModel::dismissProjectNotice) {}
    }
    // Read only inside the live bubble (through the lambda), so deltas don't recompose this screen.
    val streaming = viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingText = remember(streaming) { { streaming.value } }
    val reasoning = viewModel.reasoningText.collectAsStateWithLifecycle()
    val reasoningText = remember(reasoning) { { reasoning.value } }
    val sent by viewModel.sent.collectAsStateWithLifecycle()
    val aside by viewModel.aside.collectAsStateWithLifecycle()
    // Android 17 blocks LAN providers (Ollama, LM Studio…) until the user allows local network access.
    val localNetwork = rememberLocalNetworkGate()
    val send = remember(viewModel, localNetwork) {
        { text: String -> localNetwork.run(viewModel.uiState.value.provider?.baseUrl.orEmpty()) { viewModel.send(text) } }
    }
    ChatScreen(
        state = state,
        sent = sent,
        onShowSent = viewModel::showSent,
        sentKept = viewModel::sentKept,
        onHideSent = viewModel::hideSent,
        streamingText = streamingText,
        onBack = onBack,
        onOpenBrowser = onOpenBrowser,
        onSend = send,
        onStop = viewModel::stop,
        onAnswer = viewModel::answer,
        onAnswerQuestion = viewModel::answerQuestion,
        onDismissMessages = viewModel::dismissMessages,
        onResume = viewModel::resume,
        onAttach = viewModel::attach,
        onRemoveAttachment = viewModel::removeAttachment,
        onCompact = viewModel::compactNow,
        onSaveExport = viewModel::saveExport,
        projectHeader = projectHeader,
        reasoningText = reasoningText,
        controls = ChatControlActions(
            setModel = viewModel::setModel,
            prepareModels = viewModel::prepareModels,
            toggleFavorite = { viewModel.toggleFavorite(it) },
            enableCatalog = viewModel::enableCatalog,
            setEffort = { viewModel.setEffort(it) },
            setPlanMode = { viewModel.setPlanMode(it) },
            setToolEnabled = { key, on -> viewModel.setToolEnabled(key, on) },
            setBudget = { viewModel.setBudget(it) },
            answerBudget = { viewModel.answerBudget(it) },
            setMode = { viewModel.setMode(it) },
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
        queue = QueueActions(
            edit = viewModel::editQueued,
            cancel = viewModel::cancelQueued,
            discard = viewModel::discardQueued,
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
        aside = aside,
        asideActions = remember(viewModel) { AsideActions(ask = viewModel::askAside, dismiss = viewModel::dismissAside, clear = viewModel::clearAside) },
        draft = draft,
        focusMessageId = focusMessageId,
        onOpenActivity = onOpenActivity,
        changes = remember(viewModel) {
            ChangeActions(
                check = viewModel::checkUndo,
                checkTurn = viewModel::checkUndoTurn,
                undo = { ids -> viewModel.undo(ids = ids) },
                undoTurn = { turn -> viewModel.undo(turnId = turn) },
            )
        },
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
    /** Under the title: the chat's project chip; null when there are no projects. */
    projectHeader: (@Composable () -> Unit)? = null,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
    /** The reply streaming in now; a lambda so only the live bubble reads (and recomposes on) it. */
    streamingText: () -> String = { state.run.streamingText },
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    onAnswerQuestion: (String, dev.locus.core.agent.tools.QuestionAnswer) -> Unit = { _, _ -> },
    onDismissMessages: () -> Unit,
    onResume: () -> Unit,
    onAttach: (List<android.net.Uri>) -> Unit = {},
    onRemoveAttachment: (Part.Attachment) -> Unit = {},
    onCompact: () -> Unit = {},
    browserView: (@Composable (Modifier) -> Unit)? = null,
    draft: String? = null,
    /** The live reasoning; a lambda for the same reason as [streamingText]. */
    reasoningText: () -> String = { state.run.reasoning },
    controls: ChatControlActions = ChatControlActions(),
    branches: BranchActions = BranchActions(),
    queue: QueueActions = QueueActions(),
    /** The "What was sent" sheet; null when closed. */
    sent: SentState? = null,
    /** Opens it for a reply's request (its message id), or the chat's latest request when null. */
    onShowSent: (String?) -> Unit = {},
    /** Whether the request behind a reply (its message id) is still kept in memory, so the sheet can show it. */
    sentKept: (String) -> Boolean = { true },
    onHideSent: () -> Unit = {},
    /** Copies a chat export into the workspace; returns its workspace path. */
    onSaveExport: suspend (name: String, file: java.io.File) -> String = { name, _ -> name },
    focusMessageId: String? = null,
    /** This chat's side questions (`/btw`); null before the first. */
    aside: dev.locus.core.data.agent.SideThread? = null,
    asideActions: AsideActions = AsideActions(),
    changes: ChangeActions = ChangeActions(),
    /** Opens Activity on a task's entries (a receipt's approval counts). */
    onOpenActivity: (dev.locus.core.model.TaskActivityFilter) -> Unit = {},
) {
    // Opening at a search result: until it's been shown, keep the list where we put it.
    var focus by rememberSaveable { mutableStateOf(focusMessageId) }
    var flashKey by remember { mutableStateOf<String?>(null) }
    var panel by rememberSaveable { mutableStateOf<LivePanelKind?>(null) }
    // Hoisted so the empty-state suggestions can fill the composer.
    var text by rememberSaveable { mutableStateOf(draft.orEmpty()) }
    var sheet by rememberSaveable { mutableStateOf<ControlSheet?>(null) }
    // The composer's Aside switch: the next Send asks a side question (as `/btw`) instead of messaging the task.
    var asideOn by rememberSaveable { mutableStateOf(false) }
    // Plans the user cancelled here; Cancel also turns plan mode off, which is what persists.
    var cancelledPlans by rememberSaveable { mutableStateOf(listOf<String>()) }
    // The user message being edited: sending makes a branch beside it. Null sends a new message.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // The queued message being edited (steer and queue): sending changes it before it goes.
    var editingQueued by rememberSaveable { mutableStateOf<String?>(null) }
    // Branch actions wait while the agent works: a run continues the branch it started on.
    val canBranch = !state.run.running
    // Read aloud: one reply at a time; a new message (any run starting) or leaving the chat stops it.
    val readAloud = rememberReadAloud()
    LaunchedEffect(state.run.running) { if (state.run.running) readAloud.stop() }
    var showTokens by remember { mutableStateOf(false) }
    if (showTokens) {
        TokensSheet(
            state.tokens,
            state.provider,
            canCompact = !state.run.running && state.items.size >= 4,
            onCompact = { showTokens = false; onCompact() },
            onShowSent = { showTokens = false; onShowSent(null) },
            onDismiss = { showTokens = false },
        )
    }
    sent?.let { WhatWasSentSheet(it, onDismiss = onHideSent) }
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
                flashKey = rows[index].key
                focus = null
            }
            !askedBranch -> { askedBranch = true; branches.switchTo(target) }
            else -> focus = null // not in this chat any more (deleted): just show the chat
        }
    }
    // Its own effect: clearing focus above restarts that one, which would cancel the wait.
    LaunchedEffect(flashKey) {
        if (flashKey != null) {
            delay(1_600)
            flashKey = null
        }
    }

    // A queued message being edited was sent meanwhile: there's nothing left to change, so stop
    // editing (the typed text stays in the box, to send as a new message or clear).
    LaunchedEffect(editingQueued, state.run.queued, state.loading) {
        val id = editingQueued ?: return@LaunchedEffect
        if (!state.loading && state.run.queued.none { it.id == id }) editingQueued = null
    }

    // A panel button shows only when its panel has something to show: commands this chat ran (or one
    // running now), and a page actually open in the browser that this chat used (or browsing now).
    // An open panel keeps its button so it can be closed.
    val activeTool = state.run.activeTool
    val toggles = remember(state.items, activeTool, state.browserHasPage, panel) {
        panelToggles(state.items, activeTool, state.browserHasPage, panel)
    }
    val browsingNow = activeTool?.isBrowserTool() == true
    // Nothing to show any more (e.g. the app restarted, so no page is open): close the browser panel.
    LaunchedEffect(state.browserHasPage, browsingNow) {
        if (panel == LivePanelKind.Browser && !state.browserHasPage && !browsingNow) panel = null
    }

    // A receipt's links: its files and checks scroll to their cards (and wash them briefly); approvals open Activity.
    val currentRows by rememberUpdatedState(rows)
    val receiptActions = remember(listState, onOpenActivity) {
        ReceiptActions(
            showCard = { key ->
                val index = currentRows.indexOfFirst { it.containsKey(key) }
                if (index >= 0) {
                    scope.launch { listState.animateScrollToItem(index) }
                    flashKey = currentRows[index].key
                }
            },
            openActivity = onOpenActivity,
        )
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
                                    onEdit = { editingQueued = null; editing = item.key; text = item.text },
                                    planMode = item.planMode,
                                    midTask = item.midTask,
                                    branch = item.branch,
                                    branchEnabled = canBranch,
                                    onSwitch = branches.switchTo,
                                    onFork = { branches.fork(item.key, true) },
                                )
                                is ChatItem.Assistant -> if (item.text.isNotBlank()) {
                                    AssistantText(
                                        item.text, item.usage,
                                        speed = item.speed,
                                        fallbackNote = item.fallbackNote,
                                        turn = item.turn,
                                        branchEnabled = canBranch,
                                        onRegenerate = branches.regenerate,
                                        onSwitch = branches.switchTo,
                                        onFork = { branches.fork(item.key, false) },
                                        onWhatWasSent = { onShowSent(item.key) },
                                        whatWasSentKept = { sentKept(item.key) },
                                        reading = readAloud.reading == item.key,
                                        onReadAloud = { readAloud.toggle(item.key, item.text) },
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
                                is ChatItem.Changes -> ChangesCard(item, changes)
                                is ChatItem.Verification -> VerificationCard(item)
                                is ChatItem.Receipt -> ReceiptCard(item, receiptActions)
                            }
                            is Row.Steps -> StepsGroup(row.steps, activeTool = activeTool)
                        }
                    }
                }
                if (showLive) item(key = "live") {
                    Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { LiveTurn(state, streamed = { shownStream.value }, reasoning = reasoningText) }
                }
                // Sent while the agent works: they join the chat at its next step (or start the next turn).
                items(state.run.queued, key = { "queued:${it.id}" }) { queued ->
                    Box(Modifier.animateItem()) {
                        QueuedBubble(
                            queued,
                            running = state.run.running,
                            editing = editingQueued == queued.id,
                            onEdit = { editing = null; editingQueued = queued.id; text = queued.text },
                            onCancel = {
                                if (editingQueued == queued.id) { editingQueued = null; text = "" }
                                queue.cancel(queued.id)
                            },
                        )
                    }
                }
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
                    // The task's spend toward its budget, while one is set and the task runs. Tap for its use
                    // and limits (changes there apply to the next task).
                    state.run.budget?.takeIf { state.run.running }?.let { BudgetMeterChip(it, paused = state.run.budgetPause != null, onClick = { sheet = ControlSheet.Budget }) }
                    if (state.tokens.context > 0) ContextMeter(state.tokens, onClick = { showTokens = true })
                    ShareChatButton(state.title, state.createdAt, state.items, onSaveExport)
                },
                // The model is shown (and changed) in the composer's bar, so the header has just the title.
                title = {
                    Column {
                        Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        projectHeader?.invoke()
                    }
                },
            )
        },
        bottomBar = {
            // Floats over the transcript: no band behind it, but each card on it is opaque.
            FloatingLayer { Column(Modifier.navigationBarsPadding().imePadding()) {
                val bannerModifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                // The cards above the composer scroll within at most ~45% of what the keyboard leaves, so
                // on a small phone they can't push the text box off screen.
                val imeHeight = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
                val cardsMax = (LocalConfiguration.current.screenHeightDp.dp - imeHeight) * 0.45f
                Column(Modifier.heightIn(max = cardsMax).verticalScroll(rememberScrollState())) {
                    // After an error or an interrupted run, the transcript is intact: run the model again from where it ends.
                    val canResume = !state.run.running && (state.run.interrupted || state.run.error != null)
                    // Queued messages that weren't sent (Stop, an error, or Locus closed): send them now or drop them.
                    val unsent = if (state.run.running) 0 else state.run.queued.size
                    val discardQueued = {
                        if (editingQueued != null) text = ""
                        editingQueued = null
                        queue.discard()
                    }
                    if (canResume && unsent > 0) {
                        // One banner for both: Continue runs the chat again and sends the queued messages with it.
                        Banner(
                            stoppedWithQueuedText(state.run.error, unsent),
                            if (state.run.error != null) Tone.Critical else Tone.Brand,
                            bannerModifier,
                            actionsBelow = true,
                        ) {
                            TextButton(onClick = discardQueued) { Text("Discard") }
                            TextButton(onClick = onResume) { Text("Continue") }
                        }
                    } else {
                        MessagesBanner(
                            state.run.error,
                            state.run.notice,
                            onDismissMessages,
                            bannerModifier,
                            onContinue = if (canResume) onResume else null,
                            continueLabel = if (state.run.error != null) "Try again" else "Continue",
                        )
                        if (unsent > 0) {
                            Banner(queuedNotSentText(unsent), Tone.Brand, bannerModifier, actionsBelow = true) {
                                TextButton(onClick = discardQueued) { Text("Discard") }
                                TextButton(onClick = onResume) { Text("Send") }
                            }
                        }
                    }
                    // The chat ends on the user's message with nothing running (the error was dismissed, or the
                    // reply never came): offer to run it again, e.g. after switching to another model.
                    if (!canResume && !state.run.running && state.run.notice == null && state.run.queued.isEmpty() && state.items.lastOrNull() is ChatItem.User) {
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
                    editingQueued?.let {
                        Banner("Sending changes the queued message before it goes.", Tone.Brand, bannerModifier, title = "Editing a queued message") {
                            IconButton(onClick = { editingQueued = null; text = "" }) { Icon(LocusIcons.Close, contentDescription = "Stop editing") }
                        }
                    }
                    ReviewerBlockChips(state.run.reviewerBlocks, controls.runAnyway, controls.dismissReviewerBlock, bannerModifier)
                    state.run.budgetPause?.let { pause -> BudgetPauseCard(pause, state.run.budget, controls.answerBudget, bannerModifier) }
                    state.pendingApproval?.let { ApprovalCard(it, onAnswer, bannerModifier) }
                    state.pendingQuestion?.let { QuestionCard(it, onAnswerQuestion, bannerModifier) }
                    // The current turn's checklist, while there's something left to do or it's being worked on.
                    state.todos?.takeIf { todos -> state.run.running || todos.any { it.status != dev.locus.core.model.TodoStatus.COMPLETED } }?.let { todos ->
                        ChecklistCard(todos, running = state.run.running, modifier = bannerModifier)
                    }
                    aside?.takeIf { it.open }?.let { thread ->
                        SideQuestionCard(thread, running = state.run.running, onDismiss = asideActions.dismiss, onClear = asideActions.clear, modifier = bannerModifier)
                    }
                    if (!state.run.running && state.pendingApproval == null && state.pendingQuestion == null && text.isBlank() && state.run.suggestions.isNotEmpty()) {
                        FollowUps(state.run.suggestions, onPick = { onSend(it) })
                    }
                }
                LocusComposer(
                    text = text,
                    onTextChange = { text = it },
                    settings = ComposerSettings(
                        state.provider, state.providers, state.controls, state.connectors, state.picker,
                        taskMeter = state.run.budget?.takeIf { state.run.running },
                    ),
                    actions = remember(controls, onAttach, onRemoveAttachment, onCompact) {
                        ComposerActions(
                            setModel = controls.setModel,
                            prepareModels = controls.prepareModels,
                            toggleFavorite = controls.toggleFavorite,
                            enableCatalog = controls.enableCatalog,
                            setEffort = controls.setEffort,
                            setPlanMode = controls.setPlanMode,
                            setToolEnabled = controls.setToolEnabled,
                            setBudget = controls.setBudget,
                            setMode = controls.setMode,
                            onAttach = onAttach,
                            onRemoveAttachment = onRemoveAttachment,
                            onCompact = onCompact,
                        )
                    },
                    sheet = sheet,
                    onSheet = { sheet = it },
                    // The live panels' toggles, at the composer's top right while the chat has something in them.
                    panels = listOfNotNull(
                        ComposerPanel(
                            LocusIcons.Terminal, "Terminal",
                            open = panel == LivePanelKind.Terminal,
                            live = activeTool in TERMINAL_TOOLS,
                            onToggle = { panel = if (panel == LivePanelKind.Terminal) null else LivePanelKind.Terminal },
                        ).takeIf { toggles.terminal },
                        ComposerPanel(
                            LocusIcons.Globe, "Browser",
                            open = panel == LivePanelKind.Browser,
                            live = browsingNow,
                            onToggle = { panel = if (panel == LivePanelKind.Browser) null else LivePanelKind.Browser },
                        ).takeIf { toggles.browser },
                    ),
                    attachments = state.pendingAttachments,
                    running = state.run.running,
                    // While the agent works the box stays open: a message queues for its next step, even with an
                    // approval waiting. A background helper waiting for approval must not lock the user out either.
                    enabled = state.run.running || state.pendingApproval.let { it == null || it.origin != null },
                    // A typed message answers the agent's open question.
                    answering = state.pendingQuestion != null,
                    canCompact = !state.run.running && state.items.size >= 4,
                    aside = asideOn,
                    onAsideChange = { asideOn = it },
                    onSend = {
                        val target = editing
                        val queuedTarget = editingQueued
                        // A side question (`/btw …` or the Aside switch) is answered apart: it never edits,
                        // queues or sends anything to the task.
                        val side = sideCommandFor(text, asideOn)
                        if (side != null) {
                            asideActions.ask(side)
                            asideOn = false
                        } else {
                            when {
                                queuedTarget != null -> queue.edit(queuedTarget, text)
                                target != null && canBranch -> branches.edit(target, text)
                                else -> onSend(text)
                            }
                            editing = null
                            editingQueued = null
                        }
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

/** Side questions (`/btw`): ask (or reopen), close the card, forget them. */
internal class AsideActions(
    val ask: (SideCommand) -> Unit = {},
    val dismiss: () -> Unit = {},
    val clear: () -> Unit = {},
)

/** A queued message's actions (steer and queue). */
internal class QueueActions(
    val edit: (id: String, text: String) -> Unit = { _, _ -> },
    val cancel: (id: String) -> Unit = {},
    val discard: () -> Unit = {},
)

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
    val setBudget: (dev.locus.core.model.TaskBudget?) -> Unit = {},
    /** Continue (true) or Stop (false) at a budget pause. */
    val answerBudget: (Boolean) -> Unit = {},
    val setMode: (dev.locus.core.model.ChatMode) -> Unit = {},
    val approvePlan: () -> Unit = {},
    val cancelPlan: () -> Unit = {},
    /** Overrides the safety reviewer for a blocked call (by call id). */
    val runAnyway: (String) -> Unit = {},
    val dismissReviewerBlock: (String) -> Unit = {},
)

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
    /** Told to the agent while it worked: a lighter, smaller bubble with a label, inside the task rather than a new turn. */
    midTask: Boolean = false,
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
        if (midTask) Text(MID_TASK_LABEL, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (text.isNotBlank()) {
            Surface(
                color = if (midTask) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = UserBubbleShape,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                // Long-press for actions; history is append-only, so "edit" adds a branch beside this message.
                Box {
                    Text(
                        text,
                        Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = "Message actions")
                            .padding(horizontal = Spacing.lg, vertical = if (midTask) Spacing.sm else Spacing.md),
                        style = if (midTask) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
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
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(LocusIcons.Edit, null) }, enabled = branchEnabled, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Fork into new chat") }, leadingIcon = { Icon(LocusIcons.Branch, null) }, onClick = { menu = false; onFork() })
                    }
                }
            }
        }
        branch?.let { BranchSwitcher(it, enabled = branchEnabled, onSwitch = onSwitch) }
    }
}

/** The user's bubbles (sent and queued): rounded, with the corner nearest the edge tucked in. */
private val UserBubbleShape = RoundedCornerShape(Radius.card + 4.dp, Radius.card + 4.dp, 6.dp, Radius.card + 4.dp)

/**
 * A message the user sent while the agent worked, waiting to join the chat: dimmed, with when it goes,
 * and Edit and Cancel until then.
 */
@Composable
private fun QueuedBubble(
    queued: dev.locus.core.data.agent.QueuedMessage,
    running: Boolean,
    editing: Boolean,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        queued.attachments.forEach { AttachmentPreview(it, onRemove = null) }
        if (queued.text.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = UserBubbleShape,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = if (editing) 0.8f else 0.25f),
                ),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(queued.text, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                queuedLabel(running),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** ‹ 2/3 › under a message that has alternatives: which one is shown, and the neighbours to switch to. */
@Composable
private fun BranchSwitcher(branch: dev.locus.core.model.Branch, enabled: Boolean, onSwitch: (String) -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { branch.previous?.let(onSwitch) }, enabled = enabled && branch.previous != null, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
            Icon(LocusIcons.ChevronLeft, contentDescription = "Previous version", modifier = Modifier.size(16.dp), tint = tint)
        }
        Text(
            "${branch.index + 1}/${branch.count}",
            style = MonoCaption,
            color = tint,
            modifier = Modifier.semantics { contentDescription = "Version ${branch.index + 1} of ${branch.count}" },
        )
        IconButton(onClick = { branch.next?.let(onSwitch) }, enabled = enabled && branch.next != null, modifier = Modifier.minimumInteractiveComponentSize().size(32.dp)) {
            Icon(LocusIcons.ChevronRight, contentDescription = "Next version", modifier = Modifier.size(16.dp), tint = tint)
        }
    }
}

/** A turn that ended without reply text: its branch controls on their own. */
@Composable
private fun TurnEndRow(turn: TurnBranch, onRegenerate: (String) -> Unit, onSwitch: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onRegenerate(turn.from) }, modifier = Modifier.offset(x = (-16).dp).minimumInteractiveComponentSize().size(32.dp)) {
            Icon(LocusIcons.Regenerate, contentDescription = "Regenerate", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        turn.branch?.let { BranchSwitcher(it, enabled = true, onSwitch = onSwitch) }
    }
}

/** Image thumbnail (decoded small, off the main thread) or a file chip. */
@Composable
private fun AttachmentPreview(a: Part.Attachment, onRemove: (() -> Unit)?) {
    val thumb = rememberThumbnail(a)
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
 * A reply, with quiet actions under it: copy, regenerate, a menu (read aloud, fork, what was sent;
 * long-press on the reply itself selects text), and the ‹ i/n › switcher when the turn has alternatives.
 */
@Composable
private fun AssistantText(
    text: String,
    usage: TurnUsage? = null,
    speed: String? = null,
    /** A backup model wrote this reply: says which, under the reply. */
    fallbackNote: String? = null,
    turn: TurnBranch? = null,
    branchEnabled: Boolean = true,
    onRegenerate: (String) -> Unit = {},
    onSwitch: (String) -> Unit = {},
    onFork: () -> Unit = {},
    onWhatWasSent: () -> Unit = {},
    /** Read when the menu opens: false after a restart or for older replies, whose request isn't kept. */
    whatWasSentKept: () -> Boolean = { true },
    reading: Boolean = false,
    onReadAloud: () -> Unit = {},
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val copied = rememberCopyFeedback()
    var menu by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    // 48 dp targets around 16 dp icons; pulled left so the first icon lines up with the reply's text.
    val action = Modifier.offset(x = (-16).dp).minimumInteractiveComponentSize().size(32.dp)
    Column {
        AssistantMarkdown(text)
        fallbackNote?.let {
            Text(
                it,
                style = MonoCaption,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Locus", text)))
                        copied.copied()
                    }
                },
                modifier = action,
            ) {
                Icon(
                    if (copied.shown) LocusIcons.Check else LocusIcons.Copy,
                    contentDescription = if (copied.shown) "Copied" else "Copy reply",
                    modifier = Modifier.size(16.dp),
                    tint = tint,
                )
            }
            if (turn != null && branchEnabled) {
                IconButton(onClick = { onRegenerate(turn.from) }, modifier = action) {
                    Icon(LocusIcons.Regenerate, contentDescription = "Regenerate", modifier = Modifier.size(16.dp), tint = tint)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = action) {
                    Icon(LocusIcons.More, contentDescription = "More reply actions", modifier = Modifier.size(16.dp), tint = tint)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (reading) "Stop reading" else "Read aloud") },
                        leadingIcon = { Icon(if (reading) LocusIcons.Stop else LocusIcons.Speaker, null) },
                        onClick = { menu = false; onReadAloud() },
                    )
                    DropdownMenuItem(text = { Text("Fork into new chat") }, leadingIcon = { Icon(LocusIcons.Branch, null) }, onClick = { menu = false; onFork() })
                    // Still opens when not kept: the sheet says why it can't be shown.
                    val kept = remember(menu) { menu && whatWasSentKept() }
                    DropdownMenuItem(
                        text = { Text(if (kept) "What was sent" else "What was sent (not kept)") },
                        leadingIcon = { Icon(LocusIcons.Code, null) },
                        onClick = { menu = false; onWhatWasSent() },
                    )
                }
            }
            turn?.branch?.let { BranchSwitcher(it, enabled = branchEnabled, onSwitch = onSwitch) }
            // What this turn cost: the last request's prompt, everything the turn wrote, and how much
            // of all its prompts came from cache.
            // On-device replies show the backend and speed instead (or as well).
            if (usage != null || speed != null) {
                Text(
                    buildString {
                        usage?.let { u ->
                            append("${compactCount(u.lastInput.toLong())} in · ${compactCount(u.output)} out")
                            if (u.cacheRead > 0 && u.input > 0) append(" · ${u.cacheRead * 100 / u.input}% cached")
                        }
                        speed?.let { if (isNotEmpty()) append(" · "); append(it) }
                    },
                    // Wraps to a second line on narrow phones rather than cutting "% cached" off.
                    modifier = Modifier.weight(1f, fill = false),
                    style = MonoCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // On its own line, so the usage line beside the actions keeps its room.
        if (reading) ReadingChip(onStop = onReadAloud)
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
                        item.name == dev.locus.core.model.AgentControlTools.ASK_USER -> "No answer"
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
        if (item.name == dev.locus.core.model.AgentControlTools.ASK_USER && item.result != null) {
            val answer = if (item.result.isError) null else dev.locus.core.agent.tools.AskUser.shownAnswer(item.result.content)
            Text(
                answer?.let { "Answered: $it" } ?: "Not answered",
                Modifier.padding(start = 24.dp + Spacing.sm, top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
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
        liveStatus(state, thinking, hasText)?.let { (text, tone) -> StatusLine(text, tone) }
    }
}

/** The live line under the turn in progress, or null when there's nothing to add. */
internal fun liveStatus(state: ChatUiState, thinking: Boolean, hasText: Boolean): Pair<String, Tone>? = when {
    !state.run.running -> null
    state.run.budgetPause != null -> "Paused at the task budget" to Tone.Caution
    state.pendingApproval != null -> "Waiting for your approval" to Tone.Caution
    state.pendingQuestion != null -> "Waiting for your answer" to Tone.Caution
    state.run.activeTool != null -> liveActivity(state.run.activeTool.orEmpty()) to Tone.Live
    state.run.progress.isNotBlank() -> state.run.progress.trim().lines().last() to Tone.Live
    // The thinking block already says it's thinking.
    thinking -> null
    !hasText -> "Thinking" to Tone.Live
    else -> null
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
        IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize().size(36.dp)) { Icon(LocusIcons.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp)) }
    }
}

/** Above a message the agent read mid-task: it went into the running task, not a new turn. */
internal const val MID_TASK_LABEL = "Told while working"

/** Under a queued message: when the agent will read it. */
internal fun queuedLabel(running: Boolean): String =
    if (running) "Locus reads this after the current step" else "Not sent yet"

/** "2 queued messages weren't sent." */
internal fun queuedNotSentText(count: Int): String =
    if (count == 1) "1 queued message wasn't sent." else "$count queued messages weren't sent."

/** The one banner when a run stopped (an error, or Locus closed) with messages still queued. */
internal fun stoppedWithQueuedText(error: String?, queued: Int): String =
    "${error ?: "Locus stopped."} ${queuedNotSentText(queued)}"

private val prettyPrinter = Json { prettyPrint = true }

private fun prettyJson(json: String): String =
    runCatching { prettyPrinter.encodeToString(JsonObject.serializer(), Json.parseToJsonElement(json) as JsonObject) }.getOrDefault(json)

/** "38k / 200k" in the header while a task runs with a budget; tap for this task's use and limits. */
@Composable
private fun BudgetMeterChip(meter: dev.locus.core.agent.BudgetMeter, paused: Boolean, onClick: () -> Unit) {
    val text = dev.locus.core.agent.BudgetText.chip(meter) ?: return
    val spoken = dev.locus.core.agent.BudgetText.spokenChip(meter) ?: text
    val near = meter.tokenLimit?.let { meter.tokens >= it * 0.8 } == true
    Text(
        text,
        Modifier.clip(RoundedCornerShape(Radius.chip)).clickable(onClickLabel = "Task budget", onClick = onClick)
            .semantics { contentDescription = "Task budget: $spoken" }
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        style = MonoCaption,
        color = if (paused || near) Locus.colors.caution else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
    )
}

/** "Paused at the task budget": which limit, what's been used, with Stop and Continue under it. */
@Composable
private fun BudgetPauseCard(pause: dev.locus.core.data.agent.BudgetPause, meter: dev.locus.core.agent.BudgetMeter?, onAnswer: (Boolean) -> Unit, modifier: Modifier) {
    Banner(budgetPauseText(pause.message, meter), Tone.Caution, modifier, title = "Paused at the task budget", actionsBelow = true) {
        TextButton(onClick = { onAnswer(false) }) { Text("Stop") }
        Button(onClick = { onAnswer(true) }) { Text("Continue") }
    }
}

/**
 * "Reached the 200k-token budget for this task. Used …. Continue allows one more of the same." The
 * runner's "Paused: " prefix is dropped, since the card's title already says it paused.
 */
internal fun budgetPauseText(message: String, meter: dev.locus.core.agent.BudgetMeter?): String {
    val reached = message.removePrefix("Paused: ").replaceFirstChar { it.uppercase() }
    val used = meter?.let { "Used ${dev.locus.core.agent.BudgetText.used(it)}." }
    return listOfNotNull(reached, used, "Continue allows one more of the same.").joinToString(" ")
}

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
private fun TokensSheet(tokens: ChatTokens, provider: ChatProvider?, canCompact: Boolean, onCompact: () -> Unit, onShowSent: () -> Unit, onDismiss: () -> Unit) {
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
        if (tokens.totalCacheWritten > 0) TokenStat("Written to cache", tokens.totalCacheWritten)
        // Per request: a warm cache reads most of the prompt and writes only the newest messages.
        if (tokens.recentSteps.any { it.read > 0 || it.written > 0 }) {
            Text("Cache, latest requests", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            tokens.recentSteps.forEach { s ->
                Text(
                    "#${s.number}  ${compactCount(s.input.toLong())} in · ${compactCount(s.read.toLong())} read · ${compactCount(s.written.toLong())} written" +
                        if (s.written1h > 0) " (${compactCount(s.written1h.toLong())} 1 h)" else "",
                    style = MonoCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GroupDivider(inset = 0.dp)
        CardGroup {
            ListRow(
                title = "What was sent",
                subtitle = "The last request: system prompt, tools and messages",
                onClick = onShowSent,
                leading = { IconTile(LocusIcons.Code) },
                trailing = { Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
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
                    .clickable(role = Role.Button) { onPick(idea) }
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

/** Which live panel toggles the composer shows. */
internal data class PanelToggles(val terminal: Boolean, val browser: Boolean)

/**
 * A panel toggle shows only when its panel has something to show: commands this chat ran (or one running
 * now), and a page actually open in the browser that this chat used (or browsing now). An open terminal
 * panel keeps its toggle so it can be closed.
 */
internal fun panelToggles(items: List<ChatItem>, activeTool: String?, browserHasPage: Boolean, open: LivePanelKind?): PanelToggles {
    val terminal = items.any { (it is ChatItem.ToolCall && it.name in TERMINAL_TOOLS) || it is ChatItem.Verification } ||
        activeTool in TERMINAL_TOOLS || open == LivePanelKind.Terminal
    val chatUsedBrowser = items.any { it is ChatItem.ToolCall && it.name.isBrowserTool() }
    val browser = (chatUsedBrowser && browserHasPage) || activeTool?.isBrowserTool() == true
    return PanelToggles(terminal, browser)
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
        modifier = Modifier.minimumInteractiveComponentSize().size(36.dp),
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

private val TERMINAL_TOOLS = setOf("run_command", "ssh_exec", "ssh_job_start", "ssh_job_status", "ssh_job_stop", "linux_service_start", "linux_service_stop", "linux_service_list", "linux_service_log", "git_inspect", "git_commit")

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
            IconButton(onClick = onClose, modifier = Modifier.minimumInteractiveComponentSize().size(36.dp)) { Icon(LocusIcons.Close, contentDescription = "Close panel", modifier = Modifier.size(18.dp)) }
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
    val runs = state.items.mapNotNull { if (it is ChatItem.Verification) it.call else it as? ChatItem.ToolCall }.filter { it.name in TERMINAL_TOOLS }
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
    val terminalBg = if (androidx.compose.foundation.isSystemInDarkTheme()) TerminalColors.backgroundDark else TerminalColors.background
    val prompt = TerminalColors.prompt
    val output = TerminalColors.output
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
                "linux_service_log" -> "# log of ${arg("name")}"
                "git_inspect" -> "git ${arg("action") ?: "status"}"
                "git_commit" -> "git commit -m \"${arg("message")?.lineSequence()?.firstOrNull().orEmpty()}\""
                else -> arg("command") ?: run.name
            }
            Column {
                Text("$where \$ $command", style = CodeTextStyle, color = prompt)
                val result = run.result
                when {
                    result == null && state.run.activeTool == run.name -> Text("…running", style = CodeTextStyle, color = output.copy(alpha = 0.6f))
                    result == null -> Text("(not run)", style = CodeTextStyle, color = output.copy(alpha = 0.6f))
                    else -> Text(
                        dev.locus.core.agent.UntrustedContent.forDisplay(result.content).take(6_000),
                        style = CodeTextStyle,
                        color = if (result.isError) TerminalColors.error else output,
                    )
                }
            }
        }
    }
}
