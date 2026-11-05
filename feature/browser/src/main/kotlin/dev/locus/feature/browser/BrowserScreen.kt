package dev.locus.feature.browser

import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.foundation.layout.sizeIn
import dev.locus.core.designsystem.LocusScreen
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.browser.WebViewHost
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusFeedbackHost
import dev.locus.core.designsystem.LocusIcons
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserRoute(onBack: () -> Unit, viewModel: BrowserViewModel = hiltViewModel()) {
    // This screen hosts its own messages until the app root provides one host for every screen
    // (docs/design/design-system.md, migration step 1); then this host and the provider go.
    val feedbackState = rememberLocusFeedbackState()
    ProvideLocusFeedback(feedbackState) {
        BrowserScreen(onBack, viewModel, snackbarHost = { LocusFeedbackHost(feedbackState) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(onBack: () -> Unit, viewModel: BrowserViewModel, snackbarHost: @Composable () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val feedback = Locus.feedback
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearError by remember { mutableStateOf<String?>(null) }
    var showTabs by remember { mutableStateOf(false) }

    LocusScreen(
        state.title.ifBlank { "Browser" },
        onBack = onBack,
        status = {
            if (state.url.isNotBlank()) {
                SupportingText(state.url.removePrefix("https://").removePrefix("www."), style = LocusType.mono, maxLines = 1)
            }
        },
        actions = {
            // Only once a page opened a second tab (a sign-in pop-up, a link to a new tab).
            if (state.tabs.size > 1) TabCount(state.tabs.size) { showTabs = true }
            IconButton(onClick = { clearError = null; confirmClear = true }) { Icon(LocusIcons.Trash, contentDescription = "Clear browsing data") }
        },
        snackbarHost = snackbarHost,
        bottomBar = {
            state.handoffReason?.let { reason ->
                Banner(
                    reason,
                    Tone.Caution,
                    Modifier.navigationBarsPadding().padding(Spacing.lg),
                    title = "Your turn",
                ) { Button(onClick = viewModel::done) { Text("Done") } }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // A thin progress line while loading; nothing at all when idle.
            if (state.loading) {
                Meter(state.progress / 100f, tone = Tone.Live, size = MeterSize.Hairline, contentDescription = "Page loading")
            }
            when {
                !state.hasPage -> EmptyState(
                    "Nothing open yet",
                    "When Locus browses the web for you, the page appears here live. You can scroll, tap and log in yourself at any time.",
                    icon = LocusIcons.Globe,
                )
                // No activity to host the page in, or a preview or screenshot: say where it goes rather than "nothing open".
                activity == null || LocalInspectionMode.current -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SupportingText("The page shows here", style = LocusType.rowSupporting)
                }
                else -> AndroidView(
                    factory = { WebViewHost(activity, viewModel.browser) },
                    modifier = Modifier.fillMaxSize(),
                    // Detach when leaving so the browser keeps working off screen at full size.
                    onRelease = { host -> host.release() },
                )
            }
        }
    }

    if (showTabs) {
        LocusSheet(title = "Tabs", subtitle = "Pages Locus's browser has open. Locus works in the selected one.", onDismiss = { showTabs = false }) {
            CardGroup {
                state.tabs.forEachIndexed { i, tab ->
                    if (i > 0) GroupDivider()
                    ListRow(
                        tab.title.ifBlank { "Untitled" },
                        subtitle = tab.url.removePrefix("https://").removePrefix("www."),
                        onClick = { viewModel.selectTab(i); showTabs = false },
                        leading = if (i == state.activeTab) {
                            { Icon(LocusIcons.Check, contentDescription = "Current tab", tint = Locus.colors.live) }
                        } else {
                            null
                        },
                        trailing = { IconButton(onClick = { viewModel.closeTab(i) }) { Icon(LocusIcons.Close, contentDescription = "Close tab") } },
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
            onConfirm = { viewModel.answerDialog(true, answer) },
            onDismiss = { viewModel.answerDialog(false, null) },
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
                viewModel.clearData { cleared ->
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
