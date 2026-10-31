package dev.locus.feature.browser

import dev.locus.core.designsystem.LocusSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import dev.locus.core.browser.WebViewHost
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Tone
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.ListRow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserRoute(onBack: () -> Unit, viewModel: BrowserViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    var confirmClear by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                LocusTopBar(
                    state.title.ifBlank { "Browser" },
                    onBack = onBack,
                    status = {
                        if (state.url.isNotBlank()) {
                            Text(
                                state.url.removePrefix("https://").removePrefix("www."),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MonoCaption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        // Only once a page opened a second tab (a sign-in pop-up, a link to a new tab).
                        if (state.tabs.size > 1) TabCount(state.tabs.size) { showTabs = true }
                        IconButton(onClick = { confirmClear = true }) { Icon(LocusIcons.Trash, contentDescription = "Clear browsing data") }
                    },
                )
                // A thin progress line while loading; nothing at all when idle.
                if (state.loading) {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Locus.colors.live,
                        trackColor = Locus.colors.track,
                        drawStopIndicator = {},
                    )
                }
            }
        },
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
        if (!state.hasPage || activity == null) {
            EmptyState(
                "Nothing open yet",
                "When Locus browses the web for you, the page appears here live. You can scroll, tap and log in yourself at any time.",
                Modifier.padding(padding),
                icon = LocusIcons.Globe,
            )
        } else {
            AndroidView(
                factory = { WebViewHost(activity, viewModel.browser) },
                modifier = Modifier.fillMaxSize().padding(padding),
                // Detach when leaving so the browser keeps working off screen at full size.
                onRelease = { host -> host.release() },
            )
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

    // A page's confirm() or prompt(), which pauses it; the agent can answer it too.
    state.dialog?.let { dialog ->
        var answer by remember(dialog) { mutableStateOf(dialog.defaultText) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(state.url.substringAfter("://").substringBefore('/').ifBlank { "This page" } + " says") },
            text = {
                Column {
                    Text(dialog.message)
                    if (dialog.type == "prompt") {
                        OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth().padding(top = Spacing.md), singleLine = true)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.answerDialog(true, answer) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { viewModel.answerDialog(false, null) }) { Text("Cancel") } },
        )
    }

    if (confirmClear) {
        LocusSheet(
            title = "Clear browsing data?",
            subtitle = "Signs Locus's browser out of every site and deletes its cookies, storage and history. Your own browser is not affected.",
            onDismiss = { confirmClear = false },
            action = "Clear",
            actionTone = Tone.Critical,
            onAction = { viewModel.clearData(); confirmClear = false },
        ) {}
    }
}

/** A small boxed number like a mobile browser's tab switcher. */
@Composable
private fun TabCount(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier
                .size(22.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp))
                .semantics { contentDescription = "$count tabs" },
            contentAlignment = Alignment.Center,
        ) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
