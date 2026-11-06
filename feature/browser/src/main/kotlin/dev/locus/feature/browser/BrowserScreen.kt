package dev.locus.feature.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import dev.locus.core.designsystem.BrowserToolbar
import dev.locus.core.designsystem.SiteSecurity
import dev.locus.core.designsystem.Sizes
import kotlinx.coroutines.launch
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.browser.AgentBrowser
import dev.locus.core.browser.WebViewHost
import dev.locus.core.designsystem.AddressField
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.ErrorState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusFeedbackHost
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusScreen
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.ProvideLocusFeedback
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Stroke
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.rememberLocusFeedbackState
import dev.locus.core.model.SiteOrigin
import dev.locus.core.ui.SaveLoginSheet
import dev.locus.core.agent.ApprovalAnswer
import dev.locus.core.agent.tools.QuestionAnswer
import dev.locus.core.ui.ApprovalCard
import dev.locus.core.ui.ApprovalDetailsSheet
import dev.locus.core.ui.QuestionCard
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the browser screen can ask for; the route wires it to [BrowserViewModel]. */
internal class BrowserActions(
    val onBack: () -> Unit,
    val takeOver: () -> Unit = {},
    val handBack: (note: String) -> Unit = {},
    val go: (input: String, onError: (String) -> Unit) -> Unit = { _, _ -> },
    val navigate: (AgentBrowser.UserNavigation) -> Unit = {},
    val selectTab: (Int) -> Unit = {},
    val closeTab: (Int) -> Unit = {},
    val answerDialog: (accept: Boolean, text: String?) -> Unit = { _, _ -> },
    val clearData: (onDone: (Boolean) -> Unit) -> Unit = {},
    val saveLogin: (username: String) -> Unit = {},
    val neverSaveLogin: () -> Unit = {},
    val dismissLogin: () -> Unit = {},
    /** Settings → Saved logins; null where the app can't open it, and then the menu doesn't offer it. */
    val openSavedLogins: (() -> Unit)? = null,
    /** The approval card's Allow and Deny, and its "Don't ask again" and "Allow site" answers. */
    val answerApproval: (callId: String, approved: Boolean) -> Unit = { _, _ -> },
    val rememberApproval: (callId: String, answer: ApprovalAnswer) -> Unit = { _, _ -> },
    val answerQuestion: (callId: String, answer: QuestionAnswer) -> Unit = { _, _ -> },
    /** Scrolls the page by pixels while Locus waits for an answer (taps still need Take over). */
    val scrollPage: (dy: Int) -> Unit = {},
)

/**
 * The full-screen browser: the page of the chat on screen, or the user's own browsing when [standalone]
 * (opened from the Workspace). See docs/design/browser.md, "Take over and hand back".
 */
@Composable
fun BrowserRoute(
    onBack: () -> Unit,
    standalone: Boolean = false,
    onOpenSavedLogins: (() -> Unit)? = null,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    // Before the first frame, so a chat's page never flashes up in the user's own browsing.
    remember(standalone) { standalone.also { if (it) viewModel.showStandalone() } }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    // This screen hosts its own messages until the app root provides one host for every screen
    // (docs/design/design-system.md, migration step 1); then this host and the provider go.
    val feedbackState = rememberLocusFeedbackState()
    ProvideLocusFeedback(feedbackState) {
        BrowserScreen(
            ui,
            BrowserActions(
                onBack = onBack,
                takeOver = { viewModel.takeOver() },
                handBack = viewModel::handBack,
                go = { input, onError -> viewModel.go(input, onError) },
                navigate = { viewModel.navigate(it) },
                selectTab = { viewModel.selectTab(it) },
                closeTab = { viewModel.closeTab(it) },
                answerDialog = { accept, text -> viewModel.answerDialog(accept, text) },
                clearData = { done -> viewModel.clearData(done) },
                saveLogin = { viewModel.saveLogin(it) },
                neverSaveLogin = { viewModel.neverSaveLogin() },
                dismissLogin = viewModel::dismissLogin,
                openSavedLogins = onOpenSavedLogins,
                answerApproval = viewModel::answer,
                rememberApproval = viewModel::answerRemembering,
                answerQuestion = viewModel::answerQuestion,
                scrollPage = viewModel::scrollPage,
            ),
            snackbarHost = { LocusFeedbackHost(feedbackState) },
            page = {
                if (activity == null || LocalInspectionMode.current) {
                    PagePlaceholder()
                } else {
                    AndroidView(
                        factory = { WebViewHost(activity, viewModel.browser) },
                        modifier = Modifier.fillMaxSize(),
                        // Detach when leaving so the browser keeps working off screen at full size.
                        onRelease = { host -> host.release() },
                    )
                }
            },
        )
    }
}

/** No activity to host the page in, or a preview or screenshot: say where it goes rather than "nothing open". */
@Composable
internal fun PagePlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SupportingText("The page shows here", style = LocusType.rowSupporting)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserScreen(
    ui: BrowserUi,
    actions: BrowserActions,
    snackbarHost: @Composable () -> Unit = {},
    page: @Composable () -> Unit = { PagePlaceholder() },
    // For screenshots of states a person reaches by tapping.
    startEditing: String? = null,
    startHandBack: String? = null,
    startDetails: String? = null,
) {
    val state = ui.page
    val feedback = Locus.feedback
    // The approval whose Details sheet is open, by call id: it closes by itself once the request is answered anywhere.
    var detailsFor by rememberSaveable { mutableStateOf(startDetails) }

    // "Save this login?" after the user signed in themselves (docs/design/browser.md, "Saved logins").
    ui.loginOffer?.let { offer ->
        SaveLoginSheet(
            host = SiteOrigin.host(offer.origin),
            username = offer.username,
            isUpdate = offer.isUpdate,
            onSave = { user -> actions.saveLogin(user); feedback.show(if (offer.isUpdate) "Login updated" else "Login saved", Tone.Live) },
            onNever = actions.neverSaveLogin,
            onDismiss = actions.dismissLogin,
        )
    }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearError by remember { mutableStateOf<String?>(null) }
    var showTabs by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(startEditing != null) }
    var query by rememberSaveable { mutableStateOf(startEditing.orEmpty()) }
    var handingBack by rememberSaveable { mutableStateOf(startHandBack != null) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val canDrive = ui.userDriving && state.hasPage
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // System back: out of the address field, then back through the page's history, then out of the
    // browser, as in a mobile browser. While Locus drives, the page's history is its own, so back leaves.
    BackHandler(enabled = browserBack(ui, editing) != BrowserBack.Leave) {
        when (browserBack(ui, editing)) {
            BrowserBack.EndEditing -> editing = false
            BrowserBack.PageBack -> actions.navigate(AgentBrowser.UserNavigation.BACK)
            BrowserBack.Leave -> actions.onBack()
        }
    }

    LocusScreen(
        if (ui.standalone) "Your browsing" else "Browser",
        snackbarHost = snackbarHost,
        topBar = {
            BrowserToolbar(
                onClose = actions.onBack,
                closeLabel = if (ui.standalone) "Close browser" else "Back to chat",
                editing = editing,
                // A thin progress line while loading, in place of the bar's hairline edge.
                progress = if (state.loading && !editing) state.progress / 100f else null,
                actions = {
                    // Only once a page opened a second tab (a sign-in pop-up, a link to a new tab).
                    if (state.tabs.size > 1) TabCount(state.tabs.size) { showTabs = true }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(LocusIcons.More, contentDescription = "More options") }
                        BrowserMenu(
                            expanded = menu,
                            onDismiss = { menu = false },
                            ui = ui,
                            onNavigate = { menu = false; actions.navigate(it) },
                            onCopy = {
                                menu = false
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Link", state.url))) }
                                feedback.show("Link copied", Tone.Live)
                            },
                            onOpenElsewhere = {
                                menu = false
                                openElsewhere(context, state.url)?.let { feedback.show(it, Tone.Critical) }
                            },
                            onClear = { menu = false; clearError = null; confirmClear = true },
                            onSavedLogins = actions.openSavedLogins?.let { open -> { menu = false; open() } },
                        )
                    }
                },
            ) {
                AddressField(
                    address = displayHost(state.url),
                    query = query,
                    onQueryChange = { query = it },
                    editing = editing,
                    onEditingChange = { editing = it; if (it) query = "" },
                    onGo = {
                        val typed = query
                        editing = false
                        if (typed.isNotBlank()) actions.go(typed) { message -> feedback.show(message, Tone.Critical) }
                    },
                    enabled = ui.userDriving,
                    security = siteSecurityOf(state.url),
                    // Reload (Stop while loading) inside the pill, where it acts on the address beside it.
                    trailing = if (state.hasPage) {
                        {
                            if (state.loading) {
                                IconButton(onClick = { actions.navigate(AgentBrowser.UserNavigation.STOP) }, enabled = canDrive) {
                                    Icon(LocusIcons.Close, contentDescription = "Stop loading")
                                }
                            } else {
                                IconButton(onClick = { actions.navigate(AgentBrowser.UserNavigation.RELOAD) }, enabled = canDrive) {
                                    Icon(LocusIcons.Regenerate, contentDescription = "Reload")
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                // What Locus waits on, over the bottom of the page: the chat's own approval and question cards.
                WaitingDock(ui, actions, onDetails = { detailsFor = it })
                ControlBanner(ui, onTakeOver = actions.takeOver, onHandBack = { handingBack = true })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    !state.hasPage -> EmptyState(
                        if (ui.standalone) "Browse on your own" else "Nothing open yet",
                        if (ui.standalone) {
                            "Type an address above. Sites you sign in to here stay signed in for Locus's browser in every chat, until you clear browsing data."
                        } else {
                            "Type an address above, or ask Locus to browse. Its page shows here live, and you can take over whenever you like."
                        },
                        icon = LocusIcons.Globe,
                    )
                    else -> {
                        page()
                        state.error?.let { error ->
                            // Over the WebView's own error page, which is the browser engine's, not ours.
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                ErrorState(
                                    "Couldn't open this page",
                                    error,
                                    onRetry = if (ui.userDriving) ({ actions.navigate(AgentBrowser.UserNavigation.RELOAD) }) else null,
                                )
                            }
                        }
                        // Watching only while Locus drives: a tap would race its clicks. Tapping says how to take over.
                        // While it waits for an answer the user may scroll to look, but a tap still needs Take over.
                        if (ui.agentDriving) {
                            val waiting = ui.waitingForAnswer
                            Box(
                                Modifier.fillMaxSize().pointerInput(waiting) {
                                    val slop = viewConfiguration.touchSlop
                                    awaitPointerEventScope {
                                        while (true) {
                                            val down = awaitPointerEvent(PointerEventPass.Initial)
                                            down.changes.forEach { it.consume() }
                                            if (down.type != PointerEventType.Press) continue
                                            val start = down.changes.first().position.y
                                            var last = start
                                            var dragging = false
                                            var carry = 0f
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                event.changes.forEach { it.consume() }
                                                val y = event.changes.first().position.y
                                                if (waiting && !dragging && abs(y - start) > slop) {
                                                    dragging = true
                                                    last = y
                                                }
                                                if (dragging) {
                                                    // Finger up, page down, as a scroll does; whole pixels, the remainder kept.
                                                    carry += last - y
                                                    last = y
                                                    val step = carry.roundToInt()
                                                    if (step != 0) {
                                                        carry -= step
                                                        actions.scrollPage(step)
                                                    }
                                                }
                                                if (event.changes.none { it.pressed }) {
                                                    if (!dragging) {
                                                        val message = if (waiting) "Locus is waiting for your answer below." else "Locus is using this page."
                                                        feedback.show(message, Tone.Live, action = "Take over", onAction = actions.takeOver)
                                                    }
                                                    break
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (handingBack) {
        var note by rememberSaveable { mutableStateOf(startHandBack.orEmpty()) }
        ConfirmSheet(
            title = "Hand back to Locus?",
            message = "Locus carries on from this page. What you typed into the page stays hidden from it.",
            confirmLabel = "Hand back",
            onConfirm = { actions.handBack(note); handingBack = false },
            onDismiss = { handingBack = false },
            content = {
                OutlinedTextField(
                    note, { note = it }, Modifier.fillMaxWidth(),
                    label = { Text("Note (optional)") },
                    placeholder = { Text("e.g. Signed in. Book the 9:40 train.") },
                    maxLines = 4,
                )
            },
        )
    }

    // The approval card's Details, over the page. Only while that request still waits: answered in the
    // chat or the notification, it goes from the run state and the sheet goes with it.
    detailsFor?.let { callId ->
        val request = ui.approvals.firstOrNull { it.callId == callId }
        if (request == null) {
            androidx.compose.runtime.LaunchedEffect(callId) { detailsFor = null }
        } else {
            ApprovalDetailsSheet(request, onDismiss = { detailsFor = null })
        }
    }

    if (showTabs) {
        LocusSheet(title = "Tabs", subtitle = "Pages Locus's browser has open. Locus works in the selected one.", onDismiss = { showTabs = false }) {
            CardGroup {
                state.tabs.forEachIndexed { i, tab ->
                    if (i > 0) GroupDivider()
                    ListRow(
                        tab.title.ifBlank { "Untitled" },
                        subtitle = displayAddress(tab.url),
                        onClick = { actions.selectTab(i); showTabs = false },
                        leading = if (i == state.activeTab) {
                            { Icon(LocusIcons.Check, contentDescription = "Current tab", tint = Locus.colors.live) }
                        } else {
                            null
                        },
                        trailing = { IconButton(onClick = { actions.closeTab(i) }) { Icon(LocusIcons.Close, contentDescription = "Close tab") } },
                    )
                }
            }
        }
    }

    // A page's confirm() or prompt(), which pauses it; the agent can answer it too. Swiping the sheet
    // away answers Cancel, as the page's own dialog would.
    state.dialog?.let { dialog ->
        var answer by remember(dialog) { mutableStateOf(dialog.defaultText) }
        ConfirmSheet(
            title = state.url.substringAfter("://").substringBefore('/').ifBlank { "This page" } + " says",
            message = dialog.message,
            confirmLabel = "OK",
            onConfirm = { actions.answerDialog(true, answer) },
            onDismiss = { actions.answerDialog(false, null) },
            content = if (dialog.type == "prompt") {
                { OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Answer") }) }
            } else {
                null
            },
        )
    }

    if (confirmClear) {
        ConfirmSheet(
            title = "Clear browsing data?",
            message = "Signs Locus's browser out of every site and deletes its cookies, storage and history. Your own browser is not affected.",
            confirmLabel = "Clear",
            tone = Tone.Critical,
            busy = clearing,
            error = clearError,
            onDismiss = { confirmClear = false },
            onConfirm = {
                clearing = true
                clearError = null
                actions.clearData { cleared ->
                    clearing = false
                    val failed = "Couldn't clear all browsing data. Try again."
                    when {
                        cleared -> {
                            confirmClear = false
                            feedback.show("Browsing data cleared", Tone.Live)
                        }
                        // Still open: say so where the user is looking, next to Try again.
                        confirmClear -> clearError = failed
                        else -> feedback.show(failed, Tone.Critical)
                    }
                }
            },
        )
    }
}

/**
 * Who drives the page, at the bottom: Locus asked for the user ("Your turn"), the user took over ("You
 * have control"), or Locus is browsing (Take over). Nothing when nobody is acting.
 */
@Composable
private fun ControlBanner(ui: BrowserUi, onTakeOver: () -> Unit, onHandBack: () -> Unit) {
    val state = ui.page
    val modifier = Modifier.padding(Spacing.lg)
    when {
        state.handoffReason != null -> Banner(state.handoffReason.orEmpty(), Tone.Caution, modifier, title = "Your turn") {
            // "Done", the word browser_ask_user's own description tells the model to use ("…, then tap Done"); it opens the same hand-back sheet.
            Button(onClick = onHandBack) { Text("Done") }
        }
        state.userControl -> Banner(
            if (state.agentWaiting) {
                "Locus is waiting for you. It sees nothing of this page until you hand it back, and never what you type."
            } else {
                "Locus is paused on this page. It sees nothing of it until you hand it back, and never what you type."
            },
            Tone.Caution,
            modifier,
            title = "You have control",
        ) { Button(onClick = onHandBack) { Text("Hand back") } }
        // While Locus waits for an answer the card above takes this place: nothing is browsing then.
        ui.agentDriving && state.hasPage && !ui.waitingForAnswer -> Banner(
            "Take over to scroll, type or sign in yourself. Locus waits until you hand back.",
            Tone.Live,
            modifier,
            title = "Locus is browsing",
        ) { Button(onClick = onTakeOver) { Text("Take over") } }
    }
}

/**
 * What the chat's agent waits on, at the bottom of the browser: its oldest approval request, else its
 * oldest question, as the chat's own cards (core/ui), with how many wait in all. The same requests as
 * the chat and the notification, so answering anywhere clears them everywhere. Nothing in the user's
 * own browsing, which has no chat. It scrolls within about half the screen (a large font), so the page
 * stays partly in view above it.
 */
@Composable
private fun WaitingDock(ui: BrowserUi, actions: BrowserActions, onDetails: (String) -> Unit) {
    val approval = ui.approvals.firstOrNull()
    val question = if (approval == null) ui.questions.firstOrNull() else null
    val total = ui.approvals.size + ui.questions.size
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * DOCK_MAX_SHARE
    // Always composed, so TalkBack hears a new request in it politely, without moving focus off the page.
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        if (approval != null || question != null) {
            val spoken = waitingAnnouncement(approval?.let { it.origin?.let { o -> "${o.label} wants to: ${it.summary}" } ?: it.summary }, question?.question, total)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.md)
                    .semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SupportingText(
                    if (approval != null) "Locus is waiting for your approval" else "Locus is waiting for your answer",
                    Modifier.weight(1f).semantics { heading() },
                    style = LocusType.chip,
                )
                if (total > 1) SupportingText(queueText(total), style = LocusType.chip)
            }
        }
    }
    if (approval == null && question == null) return
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
        val cardModifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        if (approval != null) {
            ApprovalCard(approval, actions.answerApproval, actions.rememberApproval, cardModifier, onDetails = { onDetails(approval.callId) })
        } else if (question != null) {
            QuestionCard(question, actions.answerQuestion, cardModifier, composerBelow = false)
        }
    }
}

/** At most this share of the screen's height for the waiting card; past it the card scrolls and the page stays in view. */
private const val DOCK_MAX_SHARE = 0.55f

/** "1 of 3": the request on show is the oldest; the rest follow as each is answered. */
internal fun queueText(total: Int): String = "1 of $total"

/** What TalkBack says when something new waits: what it is, and how many wait in all. */
internal fun waitingAnnouncement(approvalSummary: String?, question: String?, total: Int): String {
    val what = when {
        approvalSummary != null -> "Locus needs your approval. $approvalSummary"
        question != null -> "Locus asks: $question"
        else -> return ""
    }
    if (total <= 1) return what
    val end = if (what.trimEnd().lastOrNull() in listOf('.', '?', '!')) "" else "."
    return "${what.trimEnd()}$end ${queueText(total)} waiting."
}

/**
 * The overflow menu: what the page is (its title and full address, which the bar no longer shows),
 * then back and forward, the address's own actions, the saved logins, and clearing the browser's data. Back and forward
 * wait while Locus drives, as the address bar does.
 */
@Composable
private fun BrowserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    ui: BrowserUi,
    onNavigate: (AgentBrowser.UserNavigation) -> Unit,
    onCopy: () -> Unit,
    onOpenElsewhere: () -> Unit,
    onClear: () -> Unit,
    onSavedLogins: (() -> Unit)?,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.widthIn(max = Sizes.readable)) {
        BrowserMenuItems(ui, onNavigate, onCopy, onOpenElsewhere, onClear, onSavedLogins)
    }
}

/** The overflow's content, apart from its popup so a screenshot can draw it (Robolectric hangs on an open popup menu). */
@Composable
internal fun ColumnScope.BrowserMenuItems(
    ui: BrowserUi,
    onNavigate: (AgentBrowser.UserNavigation) -> Unit = {},
    onCopy: () -> Unit = {},
    onOpenElsewhere: () -> Unit = {},
    onClear: () -> Unit = {},
    onSavedLogins: (() -> Unit)? = null,
) {
    val state = ui.page
    val canDrive = ui.userDriving && state.hasPage
    run {
        if (state.hasPage) {
            Column(
                Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(state.title.ifBlank { displayHost(state.url) }, style = LocusType.cardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                SupportingText(displayAddress(state.url), style = LocusType.mono, maxLines = 2)
            }
            HorizontalDivider(color = Locus.colors.hairline, thickness = Stroke.hairline)
        }
        DropdownMenuItem(
            text = { Text("Back") },
            leadingIcon = { Icon(LocusIcons.ChevronLeft, null) },
            enabled = canDrive && state.canGoBack,
            onClick = { onNavigate(AgentBrowser.UserNavigation.BACK) },
        )
        DropdownMenuItem(
            text = { Text("Forward") },
            leadingIcon = { Icon(LocusIcons.ChevronRight, null) },
            enabled = canDrive && state.canGoForward,
            onClick = { onNavigate(AgentBrowser.UserNavigation.FORWARD) },
        )
        DropdownMenuItem(text = { Text("Copy link") }, leadingIcon = { Icon(LocusIcons.Copy, null) }, enabled = state.hasPage && state.url.isNotBlank(), onClick = onCopy)
        DropdownMenuItem(
            text = { Text("Open in another app") },
            leadingIcon = { Icon(LocusIcons.OpenExternal, null) },
            enabled = state.hasPage && isWebAddress(state.url),
            onClick = onOpenElsewhere,
        )
        HorizontalDivider(color = Locus.colors.hairline, thickness = Stroke.hairline)
        // The browser's own data: the logins Locus can fill here, and clearing its cookies and history.
        if (onSavedLogins != null) {
            DropdownMenuItem(text = { Text("Saved logins") }, leadingIcon = { Icon(LocusIcons.Key, null) }, onClick = onSavedLogins)
        }
        DropdownMenuItem(text = { Text("Clear browsing data") }, leadingIcon = { Icon(LocusIcons.Trash, null) }, onClick = onClear)
    }
}

/** What system back does on the browser screen, most local first. */
internal enum class BrowserBack { EndEditing, PageBack, Leave }

internal fun browserBack(ui: BrowserUi, editing: Boolean): BrowserBack = when {
    editing -> BrowserBack.EndEditing
    ui.userDriving && ui.page.hasPage && ui.page.canGoBack -> BrowserBack.PageBack
    else -> BrowserBack.Leave
}

/** The host a person reads in the bar: "booking.com" for "https://www.booking.com/hotel/…"; the address as it is when it has none. */
internal fun displayHost(url: String): String {
    if (url.isBlank()) return ""
    val host = runCatching { java.net.URI(url).host }.getOrNull()
    return host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: displayAddress(url)
}

internal fun siteSecurityOf(url: String): SiteSecurity = when {
    url.startsWith("https://", ignoreCase = true) -> SiteSecurity.Secure
    url.startsWith("http://", ignoreCase = true) -> SiteSecurity.NotSecure
    else -> SiteSecurity.Unknown
}

private fun isWebAddress(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/** Hands the page's address to the person's own browser or app; a message when nothing can open it. */
private fun openElsewhere(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    null
} catch (_: ActivityNotFoundException) {
    "No app on this phone opens web links."
}

/** "booking.com/hotel/…" for "https://www.booking.com/hotel/…": what a person reads in an address bar. */
internal fun displayAddress(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")

/** A small boxed number like a mobile browser's tab switcher. */
@Composable
private fun TabCount(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        // Sized from the number, at least the icon size, so a large font grows the box instead of spilling out of it.
        Box(
            Modifier
                .sizeIn(minWidth = IconSize.medium, minHeight = IconSize.medium)
                // design-exception: the tab-switcher glyph is an icon drawn in code; its corner scales with the icon, not a surface radius
                .border(Stroke.bold, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(IconSize.medium / 4))
                .semantics { contentDescription = "$count tabs" },
            contentAlignment = Alignment.Center,
        ) {
            Text(count.toString(), Modifier.padding(horizontal = Spacing.xs), style = LocusType.chip, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
