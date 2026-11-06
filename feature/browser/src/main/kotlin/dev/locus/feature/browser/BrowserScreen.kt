package dev.locus.feature.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.MeterSize
import dev.locus.core.designsystem.ProvideLocusFeedback
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Stroke
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.rememberLocusFeedbackState

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
)

/**
 * The full-screen browser: the page of the chat on screen, or the user's own browsing when [standalone]
 * (opened from the Workspace). See docs/design/browser.md, "Take over and hand back".
 */
@Composable
fun BrowserRoute(onBack: () -> Unit, standalone: Boolean = false, viewModel: BrowserViewModel = hiltViewModel()) {
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
) {
    val state = ui.page
    val feedback = Locus.feedback
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearError by remember { mutableStateOf<String?>(null) }
    var showTabs by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(startEditing != null) }
    var query by rememberSaveable { mutableStateOf(startEditing.orEmpty()) }
    var handingBack by rememberSaveable { mutableStateOf(startHandBack != null) }
    val canDrive = ui.userDriving && state.hasPage

    LocusScreen(
        state.title.ifBlank { if (ui.standalone) "Your browsing" else "Browser" },
        onBack = actions.onBack,
        actions = {
            // Only once a page opened a second tab (a sign-in pop-up, a link to a new tab).
            if (state.tabs.size > 1) TabCount(state.tabs.size) { showTabs = true }
            IconButton(onClick = { clearError = null; confirmClear = true }) { Icon(LocusIcons.Trash, contentDescription = "Clear browsing data") }
        },
        snackbarHost = snackbarHost,
        bottomBar = {
            ControlBanner(ui, onTakeOver = actions.takeOver, onHandBack = { handingBack = true })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // While typing, the field takes the whole row (as in mobile browsers), so a long address
                // and a large font still fit; back, forward and reload return when editing ends.
                if (!editing) {
                    IconButton(onClick = { actions.navigate(AgentBrowser.UserNavigation.BACK) }, enabled = canDrive && state.canGoBack) {
                        Icon(LocusIcons.ChevronLeft, contentDescription = "Back")
                    }
                    IconButton(onClick = { actions.navigate(AgentBrowser.UserNavigation.FORWARD) }, enabled = canDrive && state.canGoForward) {
                        Icon(LocusIcons.ChevronRight, contentDescription = "Forward")
                    }
                }
                AddressField(
                    address = displayAddress(state.url),
                    query = query,
                    onQueryChange = { query = it },
                    editing = editing,
                    onEditingChange = { editing = it; if (it) query = "" },
                    onGo = {
                        val typed = query
                        editing = false
                        if (typed.isNotBlank()) actions.go(typed) { message -> feedback.show(message, Tone.Critical) }
                    },
                    modifier = Modifier.weight(1f).then(if (editing) Modifier.padding(horizontal = Spacing.sm) else Modifier),
                    enabled = ui.userDriving,
                )
                when {
                    editing -> Unit
                    state.loading -> IconButton(onClick = { actions.navigate(AgentBrowser.UserNavigation.STOP) }, enabled = canDrive) {
                        Icon(LocusIcons.Close, contentDescription = "Stop loading")
                    }
                    else -> IconButton(onClick = { actions.navigate(AgentBrowser.UserNavigation.RELOAD) }, enabled = canDrive) {
                        Icon(LocusIcons.Regenerate, contentDescription = "Reload")
                    }
                }
            }
            // A thin progress line while loading; nothing at all when idle.
            if (state.loading) {
                Meter(state.progress / 100f, tone = Tone.Live, size = MeterSize.Hairline, contentDescription = "Page loading")
            }
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
                        if (ui.agentDriving) {
                            Box(
                                Modifier.fillMaxSize().pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            event.changes.forEach { it.consume() }
                                            if (event.type == PointerEventType.Release) {
                                                feedback.show("Locus is using this page.", Tone.Live, action = "Take over", onAction = actions.takeOver)
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
    val modifier = Modifier.navigationBarsPadding().padding(Spacing.lg)
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
        ui.agentDriving && state.hasPage -> Banner(
            "Take over to scroll, type or sign in yourself. Locus waits until you hand back.",
            Tone.Live,
            modifier,
            title = "Locus is browsing",
        ) { Button(onClick = onTakeOver) { Text("Take over") } }
    }
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
