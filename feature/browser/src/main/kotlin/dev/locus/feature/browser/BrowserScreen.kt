package dev.locus.feature.browser

import dev.locus.core.designsystem.LocusSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserRoute(onBack: () -> Unit, viewModel: BrowserViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    var confirmClear by remember { mutableStateOf(false) }

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
                    actions = { IconButton(onClick = { confirmClear = true }) { Icon(LocusIcons.Trash, contentDescription = "Clear browsing data") } },
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
                factory = { viewModel.browser.viewFor(activity) },
                modifier = Modifier.fillMaxSize().padding(padding),
                // Detach when leaving so the browser keeps working off screen at full size.
                onRelease = { view -> (view.parent as? ViewGroup)?.removeView(view) },
            )
        }
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
