package dev.locus.feature.chat

import dev.locus.core.ui.ApprovalCard
import dev.locus.core.ui.QuestionCard
import dev.locus.core.ui.largeFont
import androidx.compose.runtime.getValue
import dev.locus.core.designsystem.SuggestionRow
import dev.locus.core.designsystem.Motion
import dev.locus.core.designsystem.LocusFeedbackHost
import dev.locus.core.designsystem.LocusFeedbackState
import dev.locus.core.designsystem.ProvideLocusFeedback
import dev.locus.core.designsystem.rememberLocusFeedbackState
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.wrapContentHeight
import dev.locus.core.designsystem.BottomDock
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import dev.locus.core.designsystem.AccentTileStack
import dev.locus.core.designsystem.ChatSizes
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.CompactIconButton
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.FloatingIconButton
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.SideOrBelow
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.MeterSize
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Stroke
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.InlineCode
import dev.locus.core.designsystem.codeSpans
import dev.locus.core.designsystem.UserBubbleShape
import dev.locus.core.designsystem.container
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.SettingsActionFor
import dev.locus.core.designsystem.hasSettingsAction
import dev.locus.core.model.SettingsDestination
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
    /** Opens the interactive terminal (the terminal panel's "Open terminal"). */
    onOpenTerminal: () -> Unit = {},
    draft: String? = null,
    /** Opens another chat (a fork of this one), optionally with a draft in its composer. */
    onOpenChat: (id: String, draft: String?) -> Unit = { _, _ -> },
    /** Open at this message (a search result), switching to its branch when it isn't shown. */
    focusMessageId: String? = null,
    /** Opens Activity narrowed to one task's entries (from a task receipt). */
    onOpenActivity: (dev.locus.core.model.TaskActivityFilter) -> Unit = {},
    /** Opens a project's page (from the project chip under the title). */
    onOpenProject: (String) -> Unit = {},
    /** Open voice mode on arrival (the Voice launcher shortcut). */
    startVoice: Boolean = false,
    /** Opens a workspace file in Locus's viewer (a sent file's Open); null sends Open to another app. */
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
) {
    val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = conversationId) { it.create(conversationId) }
    // Each chat has its own browser; the full-screen browser opened from here shows this chat's.
    LaunchedEffect(viewModel) { viewModel.focusBrowser() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The user signed in themselves in the live browser panel: offer to save it (docs/design/browser.md, "Saved logins").
    val loginOffer by viewModel.browser.loginOffer.collectAsStateWithLifecycle()
    val loginScope = androidx.compose.runtime.rememberCoroutineScope()
    loginOffer?.let { offer ->
        dev.locus.core.ui.SaveLoginSheet(
            host = dev.locus.core.model.SiteOrigin.host(offer.origin),
            username = offer.username,
            isUpdate = offer.isUpdate,
            onSave = { user -> loginScope.launch { runCatching { viewModel.browser.saveOffer(user) } } },
            onNever = { loginScope.launch { runCatching { viewModel.browser.neverOfferSite() } } },
            onDismiss = viewModel.browser::dismissOffer,
        )
    }
    val project by viewModel.projectInfo.collectAsStateWithLifecycle()
    val projectNotice by viewModel.projectNotice.collectAsStateWithLifecycle()
    var projectSheet by remember { mutableStateOf(false) }
    val remote by viewModel.remoteInfo.collectAsStateWithLifecycle()
    var remoteSheet by remember { mutableStateOf(false) }
    val empty = !state.loading && state.items.isEmpty()
    // The chip under the title: the chat's project, or (when there are projects) a way to put a new chat in one.
    // A temporary chat is never in a project: ChatScreen shows its Temporary line there instead.
    val projectHeader: (@Composable () -> Unit)? = when {
        state.controls.temporary -> null
        project.current != null -> {
            {
                // A project linked to an SSH machine says so next to its name: "Running on build-server".
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(dev.locus.core.designsystem.Spacing.md)) {
                    ProjectChip(project.current!!.name, onClick = { projectSheet = true })
                    remote?.let { RemoteChip(it, onClick = { remoteSheet = true }) }
                }
            }
        }
        project.all.isNotEmpty() -> { { ProjectChip(if (empty) "Add to a project" else "No project", onClick = { projectSheet = true }) } }
        else -> null
    }
    remote?.takeIf { remoteSheet }?.let { info ->
        ChatRemoteSheet(info, onDismiss = { remoteSheet = false }, onOpenProject = { onOpenProject(info.projectId) })
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
    val workflows = rememberChatWorkflows(conversationId, project.current, hiltViewModel<ChatWorkflowsViewModel>(key = "workflows-$conversationId"))
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
    val sendWarning by viewModel.sendWarning.collectAsStateWithLifecycle()
    val heatHold by viewModel.heatHold.collectAsStateWithLifecycle()
    val heatServices by viewModel.heatServices.collectAsStateWithLifecycle()
    // Android 17 blocks LAN providers (Ollama, LM Studio…) until the user allows local network access.
    val localNetwork = rememberLocalNetworkGate()
    val send = remember(viewModel, localNetwork) {
        { text: String -> localNetwork.run(viewModel.uiState.value.provider?.baseUrl.orEmpty()) { viewModel.send(text) } }
    }
    val artifactContext = androidx.compose.ui.platform.LocalContext.current
    // The chat screen's message host, made here so what the route sets up reports through it too.
    val feedbackState = rememberLocusFeedbackState()
    // What an artifact card can do here: save its source into the workspace, share a picture of it.
    val artifactHost = remember(viewModel, feedbackState) {
        dev.locus.core.markdown.artifact.ArtifactHost(
            saveToWorkspace = viewModel::saveArtifact,
            shareImage = { name, png -> shareArtifactImage(artifactContext, name, png, feedbackState) },
        )
    }
    // Voice mode (docs/design/voice-mode.md): over the chat, talking to this chat's agent.
    val voiceContext = androidx.compose.ui.platform.LocalContext.current
    val canVoice = remember { android.speech.SpeechRecognizer.isRecognitionAvailable(voiceContext) }
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    var voiceStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startVoice, canVoice) {
        if (startVoice && !voiceStarted) {
            voiceStarted = true
            voiceOpen = canVoice
        }
    }
    // The steps card's "Memory read" list: Forget a topic in place, and show ones no longer saved.
    val forgottenMemory by viewModel.forgottenMemory.collectAsStateWithLifecycle()
    val memoryReads = remember(viewModel, forgottenMemory) {
        MemoryReadActions(forgotten = forgottenMemory, forget = viewModel::forgetMemoryRead, check = viewModel::checkMemoryReads)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        dev.locus.core.markdown.artifact.LocalArtifactHost provides artifactHost,
        LocalMemoryReads provides memoryReads,
        LocalOpenWorkspaceFile provides onOpenWorkspaceFile,
    ) { // (body not re-indented, to keep the diff small)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    ChatScreen(
        onVoiceMode = if (canVoice) ({ voiceOpen = true }) else null,
        voiceActive = voiceOpen,
        state = state,
        sent = sent,
        onShowSent = viewModel::showSent,
        sentKept = viewModel::sentKept,
        onHideSent = viewModel::hideSent,
        streamingText = streamingText,
        onBack = onBack,
        onOpenBrowser = onOpenBrowser,
        onOpenTerminal = onOpenTerminal,
        onSend = send,
        onStop = viewModel::stop,
        onAnswer = viewModel::answer,
        onRemember = viewModel::answerRemembering,
        onAnswerQuestion = viewModel::answerQuestion,
        onDismissMessages = viewModel::dismissMessages,
        onResume = viewModel::resume,
        onAttach = viewModel::attach,
        onRemoveAttachment = viewModel::removeAttachment,
        onCompact = viewModel::compactNow,
        onSaveExport = viewModel::saveExport,
        projectHeader = projectHeader,
        feedbackState = feedbackState,
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
                    factory = { dev.locus.core.browser.WebViewHost(activity, viewModel.browser, conversationId) },
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
        sendWarning = sendWarning,
        heatHold = heatHold,
        heatServices = heatServices,
        heatActions = remember(viewModel) {
            HeatActions(
                light = viewModel::sendLight, sendAnyway = viewModel::sendDespiteHeat, dismiss = viewModel::dismissHeatHold,
                runDeferred = viewModel::runDeferredNow,
                stopServices = { viewModel.stopServicesForHeat() }, dismissServices = { viewModel.dismissHeatServices() },
            )
        },
        warningActions = remember(viewModel) {
            SendWarningActions(switchAndSend = viewModel::switchAndSend, sendAnyway = viewModel::sendAnyway, dismiss = viewModel::dismissSendWarning)
        },
        changes = remember(viewModel) {
            ChangeActions(
                check = viewModel::checkUndo,
                checkTurn = viewModel::checkUndoTurn,
                undo = { ids -> viewModel.undo(ids = ids) },
                undoTurn = { turn -> viewModel.undo(turnId = turn) },
            )
        },
        workflows = workflows,
    )
    if (voiceOpen) {
        dev.locus.feature.chat.voice.VoiceMode(
            state = state,
            settings = state.voice,
            onSend = send,
            onAnswer = viewModel::answer,
            blocked = when {
                sendWarning != null -> "The chat's model can't take that message."
                heatHold != null -> "The phone is hot or in battery saver, so Locus is asking before an on-device reply."
                else -> null
            },
            onExit = { voiceOpen = false },
        )
    }
    }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChatScreen(
    state: ChatUiState,
    /** Under the title: the chat's project chip; null when there are no projects. */
    projectHeader: (@Composable () -> Unit)? = null,
    /** Where the chat's short messages show (above the composer); the route shares it with what it sets up. */
    feedbackState: LocusFeedbackState = rememberLocusFeedbackState(),
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    /** The reply streaming in now; a lambda so only the live bubble reads (and recomposes on) it. */
    streamingText: () -> String = { state.run.streamingText },
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    /** "Allow for this chat" or "Always allow …" on an approval card that offers them. */
    onRemember: (String, dev.locus.core.agent.ApprovalAnswer) -> Unit = { _, _ -> },
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
    /** The chat's model can't take the message just sent ([SendWarning]); null when there's nothing to warn about. */
    sendWarning: SendWarning? = null,
    warningActions: SendWarningActions = SendWarningActions(),
    /** A message held before an on-device reply while the phone is hot or in battery saver ([HeatHold]). */
    heatHold: HeatHold? = null,
    heatActions: HeatActions = HeatActions(),
    /** Linux services running while the phone is critically hot, to suggest stopping (never stopped on its own). */
    heatServices: List<String> = emptyList(),
    /** Saving a task as a workflow and running one (docs/design/workflows.md). */
    workflows: WorkflowActions = WorkflowActions(),
    /** Opens voice mode over this chat (docs/design/voice-mode.md); null where there's no speech recognizer. */
    onVoiceMode: (() -> Unit)? = null,
    /** Voice mode is open: read-aloud stays quiet. */
    voiceActive: Boolean = false,
) {
    // Opening at a search result: until it's been shown, keep the list where we put it.
    var focus by rememberSaveable { mutableStateOf(focusMessageId) }
    var flashKey by remember { mutableStateOf<String?>(null) }
    var panel by rememberSaveable { mutableStateOf<LivePanelKind?>(null) }
    // Hoisted so the empty-state suggestions can fill the composer.
    var text by rememberSaveable { mutableStateOf(draft.orEmpty()) }
    var sheet by rememberSaveable { mutableStateOf<ControlSheet?>(null) }
    // What a temporary chat means, from the Temporary chip under the title.
    var temporarySheet by rememberSaveable { mutableStateOf(false) }
    if (temporarySheet) TemporarySheet(onDismiss = { temporarySheet = false })
    if (sendWarning != null) {
        SendWarningDialog(
            sendWarning,
            onSwitch = warningActions.switchAndSend,
            onSendAnyway = warningActions.sendAnyway,
            // Nothing was sent: the message goes back into the composer and the model sheet opens, so the
            // user picks a model and sends again.
            onChooseModel = {
                if (text.isBlank()) text = sendWarning.text
                warningActions.dismiss()
                sheet = ControlSheet.Model
            },
            // Nothing was sent: the message goes back into the composer.
            onCancel = {
                if (text.isBlank()) text = sendWarning.text
                warningActions.dismiss()
            },
        )
    }
    if (heatHold != null) {
        HeatHoldSheet(
            heatHold,
            onLight = heatActions.light,
            onSendAnyway = heatActions.sendAnyway,
            // Nothing was sent: the message goes back into the composer, to send once the phone cools.
            onNotNow = {
                if (text.isBlank()) text = heatHold.text
                heatActions.dismiss()
            },
        )
    }
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
    LaunchedEffect(voiceActive) { if (voiceActive) readAloud.stop() }
    readAloud.rate = state.voice.rate
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
    // The error banner's Details: what failed, with the provider's scrubbed answer.
    var showFailure by remember { mutableStateOf(false) }
    val failure = state.run.failure
    val failureMessage = state.run.error
    if (showFailure && failure != null && failureMessage != null) {
        FailureDetailsSheet(failure, failureMessage, onDismiss = { showFailure = false })
    }
    val rows = state.rows
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
    val receiptActions = remember(listState, onOpenActivity, workflows) {
        ReceiptActions(
            saveWorkflow = workflows.saveFromTask,
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
        // Links in the transcript ask first when they could carry data out (LinkGuard); a reply sets its own taint.
        androidx.compose.runtime.CompositionLocalProvider(LocalLinkContext provides state.links) {
        Box(modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = top + Spacing.sm,
                    // The dock's height already includes its fade (DockFade), which is the gap above it.
                    bottom = bottom,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(rows, key = { it.key }, contentType = { it.contentType }) { row ->
                    // Replies swap in for the streamed turn in the same frame: no fade, so nothing flashes.
                    val swap = row is TranscriptRow.Single && row.item is ChatItem.Assistant
                    val wash by androidx.compose.animation.animateColorAsState(
                        if (row.key == flashKey) Tone.Brand.container() else androidx.compose.ui.graphics.Color.Transparent,
                        label = "search result",
                    )
                    Box(
                        (if (swap) Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null) else Modifier.animateItem())
                            .background(wash, MaterialTheme.shapes.large),
                    ) {
                        when (row) {
                            is TranscriptRow.Single -> when (val item = row.item) {
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
                                is ChatItem.Assistant -> if (item.text.isNotBlank()) androidx.compose.runtime.CompositionLocalProvider(
                                    LocalLinkContext provides state.links.copy(outsideContent = item.outsideContent),
                                ) {
                                    AssistantText(
                                        item.text, item.usage,
                                        receipt = item.receipt,
                                        receiptActions = receiptActions,
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
                                        // The task this reply belongs to: the first receipt after it (a finished task has one).
                                        onSaveWorkflow = workflows.saveFromTask?.let { save ->
                                            val task = remember(state.items, item.key) {
                                                state.items.dropWhile { it.key != item.key }.firstNotNullOfOrNull { (it as? ChatItem.Receipt)?.receipt?.taskId }
                                            }
                                            task?.let { id -> { save(id) } }
                                        },
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
                                is ChatItem.Verification -> StepsGroup(listOf(item), activeTool = activeTool)
                                is ChatItem.Receipt -> if (!item.attached) ReceiptLine(item, receiptActions)
                            }
                            is TranscriptRow.Steps -> StepsGroup(row.steps, activeTool = activeTool)
                        }
                    }
                }
                if (showLive) item(key = "live", contentType = "live") {
                    Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { LiveTurn(state, streamed = { shownStream.value }, reasoning = reasoningText) }
                }
                // Sent while the agent works: they join the chat at its next step (or start the next turn).
                items(state.run.queued, key = { "queued:${it.id}" }, contentType = { "queued" }) { queued ->
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
        }
        }
    }

    // The chat hosts its own messages, so they show above the composer rather than over it.
    ProvideLocusFeedback(feedbackState) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { LocusFeedbackHost(feedbackState) },
        topBar = {
            // The model is shown (and changed) in the composer's bar, so the header has just the title,
            // and under it the chat's project (and where its commands run).
            // The task's spend toward its budget, while one is set and the task runs. Tap for its use
            // and limits (changes there apply to the next task). Then the context in use.
            val budgetMeter = state.run.budget?.takeIf { state.run.running }
            val meters: (@Composable () -> Unit)? = if (budgetMeter != null || state.tokens.context > 0) {
                {
                    budgetMeter?.let { BudgetMeterChip(it, paused = state.run.budgetPause != null, onClick = { sheet = ControlSheet.Budget }) }
                    if (state.tokens.context > 0) ContextMeter(state.tokens, onClick = { showTokens = true })
                }
            } else {
                null
            }
            // At a large font the meters move under the title, so the title keeps the width.
            val metersBelow = meters != null && largeFont()
            // A temporary chat says so under its title, where a project chip would be (it's never in one).
            val titleHeader: (@Composable () -> Unit)? = if (state.controls.temporary) ({ TemporaryMarker(onClick = { temporarySheet = true }) }) else projectHeader
            LocusTopBar(
                state.title,
                onBack = onBack,
                status = if (metersBelow) {
                    {
                        Column {
                            titleHeader?.invoke()
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) { meters?.invoke() }
                        }
                    }
                } else {
                    titleHeader
                },
                actions = {
                    if (!metersBelow) meters?.invoke()
                    // Nothing to share until the chat has a message.
                    if (state.items.isNotEmpty()) ShareChatButton(state.title, state.createdAt, state.items, onSaveExport)
                },
            )
        },
        bottomBar = {
            // One solid band under the composer and what's docked to it, with a short fade above, so the
            // transcript never shows through the gaps between cards. The transcript gets the band's height
            // as bottom padding, so its last item ends above it.
            FloatingLayer { BottomDock { Column(Modifier.navigationBarsPadding().imePadding()) {
                // Jump to latest has its own spot at the band's top edge, at the end, so it never covers a card.
                AnimatedVisibility(
                    visible = follower.hasUnseen && !follower.following,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.End) {
                        JumpToLatest(onClick = { scope.launch { follower.resume() } })
                    }
                }
                val bannerModifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                // The cards above the composer scroll within at most ~45% of what the keyboard leaves, so
                // on a small phone they can't push the text box off screen.
                val imeHeight = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
                // While an approval or a question waits, the transcript matters less than answering it: the cards
                // get more of the height, and the card scrolls its own buttons into view (a large font).
                val waiting = state.pendingApproval != null || state.pendingQuestion != null
                val cardsMax = (LocalConfiguration.current.screenHeightDp.dp - imeHeight) * if (waiting) 0.6f else 0.45f
                Column(Modifier.heightIn(max = cardsMax).verticalScroll(rememberScrollState())) {
                    // After an error or an interrupted run, the transcript is intact: run the model again from where it ends.
                    val canResume = !state.run.running && (state.run.interrupted || state.run.error != null)
                    val failureActions = FailureActions(
                        onDetails = { showFailure = true },
                        onCompact = onCompact.takeIf { !state.run.running && state.items.size >= 4 },
                        onSwitchModel = { sheet = ControlSheet.Model },
                    )
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
                            SettingsActionFor(state.run.error)
                            if (state.run.failure != null && state.run.error != null) TextButton(onClick = failureActions.onDetails) { Text("Details") }
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
                            failure = state.run.failure,
                            failureActions = failureActions,
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
                    state.pendingApproval?.let { ApprovalCard(it, onAnswer, onRemember, bannerModifier) }
                    state.pendingQuestion?.let { QuestionCard(it, onAnswerQuestion, bannerModifier) }
                    aside?.takeIf { it.open }?.let { thread ->
                        SideQuestionCard(thread, running = state.run.running, onDismiss = asideActions.dismiss, onClear = asideActions.clear, modifier = bannerModifier)
                    }
                    if (heatServices.isNotEmpty()) {
                        // The body says the phone is hot; the title says what to do about it. The actions wrap at a large font.
                        Banner(dev.locus.core.data.device.LinuxHeatAdvice.message(heatServices), Tone.Caution, bannerModifier, title = "Let the phone cool down", actionsBelow = true) {
                            androidx.compose.foundation.layout.FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = heatActions.dismissServices) { Text("Not now") }
                                TextButton(onClick = heatActions.stopServices) { Text("Stop services") }
                            }
                        }
                    }
                    // Side jobs held for heat or battery saver: say so, and let the user run them now.
                    state.run.paused?.takeIf { !state.run.running }?.let { PausedChip(it, heatActions.runDeferred) }
                    // Follow-ups after a reply. They go as soon as the user types, sends or the agent runs, and
                    // slide in and out rather than jumping the dock.
                    FollowUps(
                        state.run.suggestions,
                        visible = !state.run.running && state.pendingApproval == null && state.pendingQuestion == null && text.isBlank(),
                        onPick = { onSend(it) },
                    )
                }
                LocusComposer(
                    text = text,
                    onTextChange = { text = it },
                    settings = ComposerSettings(
                        state.provider, state.providers, state.controls, state.connectors, state.picker,
                        taskMeter = state.run.budget?.takeIf { state.run.running },
                    ),
                    actions = remember(controls, onAttach, onRemoveAttachment, onCompact, workflows, onVoiceMode) {
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
                            onWorkflows = workflows.pick,
                            onVoiceMode = onVoiceMode,
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
                    // The current turn's checklist, while there's something left to do or it's being worked
                    // on: one slim row in the composer's top edge rather than a card floating above it.
                    dock = state.todos?.takeIf { todos -> state.run.running || todos.any { it.status != dev.locus.core.model.TodoStatus.COMPLETED } }?.let { todos ->
                        { ChecklistStrip(todos, running = state.run.running) }
                    },
                )
            } } }
        },
    ) { padding ->
        if (!state.loading && state.items.isEmpty() && !state.run.running && panel == null) {
            Suggestions(Modifier.padding(padding), onPick = { text = it }, temporary = state.controls.temporary)
            return@Scaffold
        }
        // The transcript, optionally beside a live view: above it on phones, beside it on wide screens.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= ChatSizes.wideLayout
            val panelHeight = maxHeight * 0.42f
            val openPanel = panel
            when {
                openPanel == null -> transcript(Modifier.fillMaxSize(), padding.calculateTopPadding(), padding.calculateBottomPadding())
                wide -> Row(Modifier.fillMaxSize()) {
                    transcript(Modifier.weight(1f).fillMaxHeight(), padding.calculateTopPadding(), padding.calculateBottomPadding())
                    LivePanel(
                        openPanel, state, onClose = { panel = null }, onOpenBrowser = onOpenBrowser, onOpenTerminal = onOpenTerminal, browserView = browserView,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(), end = Spacing.lg),
                    )
                }
                else -> Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                    LivePanel(
                        openPanel, state, onClose = { panel = null }, onOpenBrowser = onOpenBrowser, onOpenTerminal = onOpenTerminal, browserView = browserView,
                        modifier = Modifier.fillMaxWidth().height(panelHeight).padding(horizontal = Spacing.lg),
                    )
                    transcript(Modifier.weight(1f), 0.dp, padding.calculateBottomPadding())
                }
            }
        }
    }
    } // ProvideLocusFeedback
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

/**
 * A turn's work in one card: tool calls in order, with the model's narration between them as quiet
 * notes. Longer runs collapse to a summary (one tile per kind of tool, and the latest note). Build and
 * test runs sit at the bottom, one row per kind of check however often it ran ([CheckGroupRow]), and
 * stay in view when the steps are folded; memory the turn read is one row among the steps.
 */
@Composable
private fun StepsGroup(steps: List<ChatItem>, activeTool: String?) {
    fun running(call: ChatItem.ToolCall) = activeTool == call.name && call.result == null
    // Every call, a verification's included: for the count, the tiles and memory reads.
    val allCalls = remember(steps) { steps.mapNotNull { (it as? ChatItem.ToolCall) ?: (it as? ChatItem.Verification)?.call } }
    val groups = remember(steps) { checkGroups(steps) }
    // What the step list shows: check runs are in their group's row instead.
    val shown = remember(steps) { steps.filter { checkRunOf(it) == null } }
    val calls = shown.filterIsInstance<ChatItem.ToolCall>()
    // Which memory topics these steps read, worked out from the calls as they are (docs/design/memory-search.md).
    val reads = remember(allCalls) { memoryReads(allCalls) }

    @Composable
    fun StepList(items: List<ChatItem>) {
        items.forEach { step ->
            when (step) {
                // A helper (sub-agent) is a nested row with its own live state and transcript.
                is ChatItem.ToolCall -> if (step.name == dev.locus.core.model.SubagentTools.SPAWN || step.name == dev.locus.core.model.SubagentTools.SEND) SubagentRow(step) else ToolCallRow(step, running = running(step))
                is ChatItem.Note -> StepNote(step.text)
                is ChatItem.Thought -> ThoughtRow(step, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
                else -> Unit
            }
        }
        if (reads.isNotEmpty()) MemoryReadRow(reads)
    }

    // A step that only thought (its tool calls were the checklist) needs no card around it.
    if (groups.isEmpty() && calls.isEmpty() && reads.isEmpty() && shown.all { it is ChatItem.Thought }) {
        Column { shown.forEach { ThoughtRow(it as ChatItem.Thought) } }
        return
    }
    // Short runs are shown in full; a summary would hide as much as it saves.
    val collapsible = calls.size > 2
    var expanded by rememberSaveable(steps.first().key) { mutableStateOf(false) }
    val failed = calls.count { it.result?.isError == true && it.result.decision != ApprovalDecision.USER_DENIED }
    val anyRunning = calls.any(::running)
    val latestNote = shown.lastOrNull { it is ChatItem.Note } as ChatItem.Note?
    LocusCard(contentPadding = PaddingValues(0.dp)) {
        if (collapsible) {
            val label = "${allCalls.size} steps"
            Row(
                Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (expanded) "Hide steps" else "Show steps") { expanded = !expanded }
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                    .semantics {
                        contentDescription = label + (if (failed > 0) ", $failed failed" else "") + (if (anyRunning) ", working" else "")
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // One tile per kind of tool used, so the summary shows what sort of work it was.
                AccentTileStack(allCalls.map { accentFor(it.name) }.distinctBy { it.first }.take(4))
                Text(label, modifier = Modifier.padding(start = Spacing.sm).weight(1f), style = LocusType.rowSupporting)
                if (anyRunning && !expanded) StatusDot(Tone.Live, Modifier.padding(end = Spacing.xs))
                if (failed > 0) Text("$failed failed", Modifier.padding(end = Spacing.sm), style = LocusType.chip, color = Locus.colors.critical)
                Icon(
                    LocusIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(IconSize.small).rotate(if (expanded) 270f else 90f),
                )
            }
            if (!expanded && latestNote != null) {
                SupportingText(latestNote.text.asNote(), Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm), maxLines = 2)
            }
            AnimatedVisibility(expanded) { Column { StepList(shown) } }
        } else {
            StepList(shown)
        }
        if (groups.isNotEmpty()) {
            if (collapsible || shown.isNotEmpty() || reads.isNotEmpty()) GroupDivider(inset = Spacing.md)
            groups.forEach { CheckGroupRow(it, running = ::running) }
        }
    }
}

/** The model's narration between steps: muted and compact, so it reads as a step, not a reply. */
@Composable
private fun StepNote(text: String) {
    SupportingText(
        text.asNote(),
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        style = LocusType.rowSupporting,
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
private fun Suggestions(modifier: Modifier, onPick: (String) -> Unit, temporary: Boolean = false) {
    Column(
        modifier.fillMaxSize().padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        Text(if (temporary) "A temporary chat" else "What should we work on?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.entrance(0))
        SupportingText(
            if (temporary) {
                "No memory, and it's deleted when you leave. Files it writes stay in the workspace; approvals work as usual."
            } else {
                "Locus can use the web, files, a Linux shell and your phone's apps. It asks before anything risky."
            },
            Modifier.padding(bottom = Spacing.md).entrance(1),
            style = LocusType.rowSupporting,
        )
        SUGGESTIONS.forEachIndexed { i, suggestion ->
            LocusCard(Modifier.entrance(i + 2), onClick = { onPick(suggestion.text) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (accent, icon) = accentFor(suggestion.tool)
                    AccentTile(accent, icon, size = IconSize.tileSmall)
                    Text(suggestion.text, Modifier.padding(start = Spacing.md), style = LocusType.rowSupporting)
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
        SentAttachments(attachments)
        if (planMode) Text("Plan mode", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (midTask) Text(MID_TASK_LABEL, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (text.isNotBlank()) {
            Surface(
                color = if (midTask) MaterialTheme.colorScheme.primaryContainer.copy(alpha = Emphasis.muted) else MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = UserBubbleShape,
                modifier = Modifier.widthIn(max = Sizes.readable),
            ) {
                // Long-press for actions; history is append-only, so "edit" adds a branch beside this message.
                Box {
                    Text(
                        text,
                        Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = "Message actions")
                            .padding(horizontal = Spacing.lg, vertical = if (midTask) Spacing.sm else Spacing.md),
                        style = if (midTask) LocusType.rowSupporting else LocusType.rowTitle,
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
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        SentAttachments(queued.attachments)
        if (queued.text.isNotBlank()) {
            Surface(
                // Not in the chat yet: dimmer than a sent message.
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = Emphasis.disabled),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = UserBubbleShape,
                border = androidx.compose.foundation.BorderStroke(
                    Stroke.regular,
                    MaterialTheme.colorScheme.primary.copy(alpha = if (editing) 0.8f else 0.25f),
                ),
                modifier = Modifier.widthIn(max = Sizes.readable),
            ) {
                Text(queued.text, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), style = LocusType.rowTitle)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The label wraps; the buttons keep their natural width.
            SupportingText(
                queuedLabel(running),
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelSmall,
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
        CompactIconButton(LocusIcons.ChevronLeft, "Previous version", onClick = { branch.previous?.let(onSwitch) }, enabled = enabled && branch.previous != null, tint = tint)
        Text(
            "${branch.index + 1}/${branch.count}",
            style = LocusType.mono,
            color = tint,
            modifier = Modifier.semantics { contentDescription = "Version ${branch.index + 1} of ${branch.count}" },
        )
        CompactIconButton(LocusIcons.ChevronRight, "Next version", onClick = { branch.next?.let(onSwitch) }, enabled = enabled && branch.next != null, tint = tint)
    }
}

/** A turn that ended without reply text: its branch controls on their own. */
@Composable
private fun TurnEndRow(turn: TurnBranch, onRegenerate: (String) -> Unit, onSwitch: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Labelled: a lone arrow under nothing doesn't say what it does. Pulled back so its icon starts on the text's edge.
        TextButton(onClick = { onRegenerate(turn.from) }, modifier = Modifier.offset(x = -Spacing.md)) {
            Icon(LocusIcons.Regenerate, contentDescription = null, modifier = Modifier.size(IconSize.small))
            Text("Regenerate", Modifier.padding(start = Spacing.xs))
        }
        turn.branch?.let { BranchSwitcher(it, enabled = true, onSwitch = onSwitch) }
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
    /** The task this reply finished: its time and cost join the meta line, which opens it. */
    receipt: dev.locus.core.model.TaskReceipt? = null,
    receiptActions: ReceiptActions = ReceiptActions(),
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
    /** Save the task this reply ended as a workflow; null when that isn't offered. */
    onSaveWorkflow: (() -> Unit)? = null,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val copied = rememberCopyFeedback()
    var menu by remember { mutableStateOf(false) }
    var showReceipt by rememberSaveable { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    // Compact buttons, pulled left so the first icon lines up with the reply's text.
    val action = Modifier.offset(x = -Spacing.lg)
    // One meta line: the finished task's time and cost, then what this turn cost (the last request's
    // prompt, everything the turn wrote, the share from cache). On-device replies show the backend and
    // speed instead (or as well). With a receipt it opens the run's details.
    val meta = replyMeta(usage, speed, receipt)
    val metaBelow = largeFont()
    val metaText: @Composable (String, Modifier) -> Unit = { line, modifier ->
        Text(
            line,
            // Wraps to a second line on narrow phones rather than cutting "% cached" off.
            modifier.then(
                if (receipt == null) Modifier
                else Modifier.clip(MaterialTheme.shapes.small)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Show run details") { showReceipt = true }
                    .heightIn(min = Sizes.touchTarget)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = Spacing.xs),
            ),
            style = LocusType.mono,
            maxLines = if (metaBelow) Int.MAX_VALUE else 3,
        )
    }
    Column {
        AssistantMarkdown(text)
        fallbackNote?.let {
            SupportingText(it, Modifier.padding(top = Spacing.xs), style = LocusType.mono, maxLines = 2)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactIconButton(
                if (copied.shown) LocusIcons.Check else LocusIcons.Copy,
                if (copied.shown) "Copied" else "Copy reply",
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Locus", text)))
                        copied.copied()
                    }
                },
                modifier = action,
                tint = tint,
            )
            if (turn != null && branchEnabled) {
                CompactIconButton(LocusIcons.Regenerate, "Regenerate", onClick = { onRegenerate(turn.from) }, modifier = action, tint = tint)
            }
            Box {
                CompactIconButton(LocusIcons.More, "More reply actions", onClick = { menu = true }, modifier = action, tint = tint)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (reading) "Stop reading" else "Read aloud") },
                        leadingIcon = { Icon(if (reading) LocusIcons.Stop else LocusIcons.Speaker, null) },
                        onClick = { menu = false; onReadAloud() },
                    )
                    DropdownMenuItem(text = { Text("Fork into new chat") }, leadingIcon = { Icon(LocusIcons.Branch, null) }, onClick = { menu = false; onFork() })
                    onSaveWorkflow?.let { save ->
                        DropdownMenuItem(text = { Text("Save as workflow") }, leadingIcon = { Icon(LocusIcons.Play, null) }, onClick = { menu = false; save() })
                    }
                    if (receipt != null) {
                        DropdownMenuItem(text = { Text("Run details") }, leadingIcon = { Icon(LocusIcons.Gauge, null) }, onClick = { menu = false; showReceipt = true })
                    }
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
            if (!metaBelow) meta?.let { metaText(it, Modifier.weight(1f, fill = false)) }
        }
        // At a large font the meta takes its own line rather than wrapping beside the buttons.
        if (metaBelow) meta?.let { metaText(it, Modifier) }
        // On its own line, so the usage line beside the actions keeps its room.
        if (reading) ReadingChip(onStop = onReadAloud)
    }
    if (showReceipt && receipt != null) ReceiptSheet(receipt, receiptActions, onDismiss = { showReceipt = false })
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
            AccentTile(accent, accentIcon, size = IconSize.large)
            Text(
                toolLabel(item.name, item.argumentsJson, done = done),
                modifier = Modifier.padding(start = Spacing.sm).weight(1f),
                style = LocusType.rowSupporting,
                color = if (refused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                running -> StatusDot(Tone.Live)
                done -> Icon(LocusIcons.Check, contentDescription = "Done", modifier = Modifier.size(IconSize.small), tint = Locus.colors.live)
                else -> Text(
                    when {
                        item.result == null -> "Not run"
                        item.name == dev.locus.core.model.AgentControlTools.ASK_USER -> "No answer"
                        decision == ApprovalDecision.USER_DENIED -> "You declined"
                        decision == ApprovalDecision.POLICY_DENIED -> "Blocked"
                        decision == ApprovalDecision.REVIEWER_DENIED -> "Reviewer blocked"
                        else -> "Failed"
                    },
                    style = LocusType.chip,
                    color = if (failed) Locus.colors.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (item.name == dev.locus.core.model.AgentControlTools.ASK_USER && item.result != null) {
            val answer = if (item.result.isError) null else dev.locus.core.agent.tools.AskUser.shownAnswer(item.result.content)
            SupportingText(
                answer?.let { "Answered: $it" } ?: "Not answered",
                Modifier.padding(start = IconSize.large + Spacing.sm, top = Spacing.xxs),
                maxLines = if (expanded) Int.MAX_VALUE else 2,
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                CodeBlock("Input", prettyJson(item.argumentsJson))
                // The safety envelope around outside text is for the model; the user sees the text itself.
                item.result?.let { CodeBlock("Result", dev.locus.core.model.Envelopes.strip(it.content).take(4000)) }
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, code: String) {
    SupportingText(label, style = LocusType.chip)
    CodeSurface(Modifier.fillMaxWidth()) {
        SelectionContainer { Text(code, Modifier.padding(Spacing.sm), style = LocusType.code) }
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
    // The budget card above the composer says it, with Stop and Continue.
    state.run.budgetPause != null -> null
    // The approval card above the composer says it, with the buttons.
    state.pendingApproval != null -> null
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


/**
 * What the error banner offers for a model failure ([dev.locus.core.agent.RunFailure]): its Details sheet,
 * and the ways out a repeat suggests. A null action isn't offered (Summarise needs a chat long enough).
 */
internal class FailureActions(
    val onDetails: () -> Unit = {},
    val onCompact: (() -> Unit)? = null,
    val onSwitchModel: (() -> Unit)? = null,
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MessagesBanner(
    error: String?,
    notice: String?,
    onDismiss: () -> Unit,
    modifier: Modifier,
    onContinue: (() -> Unit)? = null,
    continueLabel: String = "Continue",
    failure: dev.locus.core.agent.RunFailure? = null,
    failureActions: FailureActions = FailureActions(),
) {
    val shown = error ?: notice ?: return
    val model = failure?.takeIf { error != null }
    // The advice (a repeat, a usage limit) goes under the error itself.
    // The request id is in Details, not the sentence; a dropped connection is said in words.
    val text = listOfNotNull(if (error != null) failureMessageInWords(shown) else shown, model?.advice).joinToString("\n\n")
    Banner(text, if (error != null) Tone.Critical else Tone.Neutral, modifier, actionsBelow = model != null || hasSettingsAction(error), onDismiss = onDismiss) {
        if (model == null) {
            SettingsActionFor(error) // "… Add it in Settings → Models." gets "Open Models"
            onContinue?.let { TextButton(onClick = it) { Text(continueLabel) } }
        } else {
            // Up to five actions: they wrap rather than run off a narrow phone.
            androidx.compose.foundation.layout.FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsActionFor(error)
                TextButton(onClick = failureActions.onDetails) { Text("Details") }
                if (model.suggestCompact) failureActions.onCompact?.let { TextButton(onClick = it) { Text("Summarise chat") } }
                if (model.suggestSwitchModel) failureActions.onSwitchModel?.let { TextButton(onClick = it) { Text("Switch model") } }
                onContinue?.let { TextButton(onClick = it) { Text(continueLabel) } }
            }
        }
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
    LocusChip(
        text,
        tone = if (paused || near) Tone.Caution else Tone.Neutral,
        onClick = onClick,
        contentDescription = "Task budget: $spoken",
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
        Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Context and tokens", onClick = onClick).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextRing(tokens.fraction, size = IconSize.small)
        Text(
            tokens.fraction?.let { "${(it * 100).toInt()}%" } ?: compactCount(tokens.context.toLong()),
            Modifier.padding(start = Spacing.xs),
            style = LocusType.mono,
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
        subtitle = provider?.let { "${it.name} · ${dev.locus.core.model.ModelNames.short(it.model)}" },
        onDismiss = onDismiss,
        action = "Summarise now".takeIf { canCompact },
        onAction = onCompact,
    ) {
        val fraction = tokens.fraction
        SupportingText("In context now", style = LocusType.label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(compactCount(tokens.context.toLong()), style = MaterialTheme.typography.headlineMedium)
            SupportingText(
                tokens.window?.let { " of ${compactCount(it.toLong())} tokens" } ?: " tokens",
                Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
                style = LocusType.rowSupporting,
            )
        }
        if (fraction != null) {
            Meter(fraction, size = MeterSize.Medium, contentDescription = "Context used")
            SupportingText(
                when {
                    fraction >= 0.8f -> "The context is nearly full. Locus summarises older messages automatically before it runs out."
                    else -> "${(fraction * 100).toInt()}% used. The whole chat is sent with every request; cached parts are cheaper and faster."
                },
            )
        } else {
            SettingsLink(
                SettingsDestination.Models,
                hint = "This model's context window isn't known. To see how full it is, pick the model from the list or enter its window in",
                beforeOpen = onDismiss,
            )
        }
        GroupDivider(inset = 0.dp)
        SupportingText("This chat", style = LocusType.label)
        TokenStat("Requests", tokens.requests.toLong())
        TokenStat("Tokens in", tokens.totalInput, tokens.totalCached.takeIf { it > 0 && tokens.totalInput > 0 }?.let { "${it * 100 / tokens.totalInput}% from cache" })
        TokenStat("Tokens out", tokens.totalOutput)
        if (tokens.totalCacheWritten > 0) TokenStat("Written to cache", tokens.totalCacheWritten)
        // Per request: a warm cache reads most of the prompt and writes only the newest messages.
        if (tokens.recentSteps.any { it.read > 0 || it.written > 0 }) {
            SupportingText("Cache, latest requests", style = LocusType.label)
            tokens.recentSteps.forEach { s ->
                SupportingText(
                    "#${s.number}  ${compactCount(s.input.toLong())} in · ${compactCount(s.read.toLong())} read · ${compactCount(s.written.toLong())} written" +
                        if (s.written1h > 0) " (${compactCount(s.written1h.toLong())} 1 h)" else "",
                    style = LocusType.mono,
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
                trailing = { RowChevron() },
            )
        }
    }
}

@Composable
private fun TokenStat(label: String, value: Long, note: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = LocusType.rowTitle)
        if (note != null) SupportingText(note, Modifier.padding(end = Spacing.sm))
        // One format for every count on the sheet: exact, with separators.
        Text("%,d".format(value), style = LocusType.rowTitle.copy(fontFamily = LocusType.mono.fontFamily))
    }
}

/** Tappable follow-ups after a reply; tapping one sends it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FollowUps(ideas: List<String>, visible: Boolean, onPick: (String) -> Unit) {
    // The last ones shown stay while they slide out, so the row doesn't empty before it's gone.
    val last = remember { arrayOf(ideas) }
    if (ideas.isNotEmpty()) last[0] = ideas
    AnimatedVisibility(
        visible = visible && ideas.isNotEmpty(),
        enter = fadeIn(Motion.standard()) + expandVertically(Motion.standard()),
        exit = fadeOut(Motion.quick()) + shrinkVertically(Motion.quick()),
    ) {
        SuggestionRow(last[0], onPick = onPick)
    }
}

/** A compaction checkpoint: older messages above stay readable, but the model now works from this summary. */
@Composable
private fun CheckpointCard(item: ChatItem.Checkpoint) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    LocusCard(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LocusIcons.Note, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.medium))
            Text(
                "Summarised ${item.replaced} earlier messages",
                Modifier.weight(1f).padding(start = Spacing.sm),
                style = LocusType.cardTitle,
            )
            Text(if (open) "Hide" else "Show", style = LocusType.chip, color = MaterialTheme.colorScheme.primary)
        }
        SupportingText("Locus works from this summary now; the messages above are kept for you.")
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
    // The send arrow, pointing down.
    FloatingIconButton(LocusIcons.Send, "Jump to latest", onClick = onClick, rotation = 180f)
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
    onOpenTerminal: () -> Unit,
    browserView: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LocusCard(modifier, contentPadding = PaddingValues(0.dp)) {
        // The edge toggles pick the view, so the header just names it: "Terminal", or the page and its address.
        Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (kind == LivePanelKind.Browser) LocusIcons.Globe else LocusIcons.Terminal,
                contentDescription = null,
                modifier = Modifier.size(IconSize.medium),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(horizontal = Spacing.sm)) {
                val url = state.browserUrl.substringAfter("://").removeSuffix("/")
                val title = when {
                    kind == LivePanelKind.Terminal -> "Terminal"
                    !state.browserHasPage -> "Browser"
                    else -> state.browserTitle.ifBlank { url.substringBefore('/').ifBlank { "Browser" } }
                }
                Text(title, style = LocusType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (kind == LivePanelKind.Browser && state.browserHasPage && url.isNotEmpty()) {
                    SupportingText(url, style = LocusType.mono, maxLines = 1)
                }
            }
            if (kind == LivePanelKind.Browser) {
                TextButton(onClick = onOpenBrowser) { Text("Full screen") }
            } else {
                // The panel is a log of the agent's commands; this opens a shell for the user.
                TextButton(onClick = onOpenTerminal) { Text("Open terminal") }
            }
            IconButton(onClick = onClose) { Icon(LocusIcons.Close, contentDescription = "Close panel", modifier = Modifier.size(IconSize.medium)) }
        }
        GroupDivider(inset = 0.dp)
        when (kind) {
            LivePanelKind.Terminal -> TerminalLog(state, Modifier.fillMaxSize())
            LivePanelKind.Browser -> if (browserView != null && state.browserHasPage) {
                Box(Modifier.fillMaxSize().clipToBounds()) { browserView(Modifier.fillMaxSize()) }
            } else {
                SupportingText("The browser opens here when Locus visits a page.", Modifier.padding(Spacing.lg), style = LocusType.rowSupporting)
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
        SupportingText(
            "Commands Locus runs in its Linux environment or on your SSH machines show up here, with their output.",
            modifier.padding(Spacing.lg),
            style = LocusType.rowSupporting,
        )
        return
    }
    val terminalBg = if (Locus.isDark) TerminalColors.backgroundDark else TerminalColors.background
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
                    result == null && state.run.activeTool == run.name -> Text("…running", style = LocusType.code, color = output.copy(alpha = Emphasis.muted))
                    result == null -> Text("(not run)", style = LocusType.code, color = output.copy(alpha = Emphasis.muted))
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
