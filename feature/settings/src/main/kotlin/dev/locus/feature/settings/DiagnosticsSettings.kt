package dev.locus.feature.settings

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.diagnostics.DiagnosticEntry
import dev.locus.core.data.diagnostics.DiagnosticKind
import dev.locus.core.data.diagnostics.DiagnosticsRepository
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.relativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class DiagnosticsViewModel @Inject constructor(private val diagnostics: DiagnosticsRepository) : ViewModel() {
    val entries = diagnostics.entries

    init {
        viewModelScope.launch(Dispatchers.IO) { diagnostics.refresh() }
    }

    /** The scrubbed report, handed to [onReady] for the share sheet. Only on the user's tap. */
    fun share(onReady: (String) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val text = diagnostics.shareText()
        launch(Dispatchers.Main) { onReady(text) }
    }

    fun clear() = viewModelScope.launch(Dispatchers.IO) { diagnostics.clear() }
}

/** Recent crashes, exits Android recorded, and storage problems, kept on the phone; Share and Clear. */
@Composable
internal fun DiagnosticsRoute(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DiagnosticsSettings(
        entries = entries,
        onBack = onBack,
        onShare = {
            viewModel.share { text ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Locus diagnostics").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share diagnostics"))
            }
        },
        onClear = viewModel::clear,
    )
}

@Composable
internal fun DiagnosticsSettings(entries: List<DiagnosticEntry>, onBack: () -> Unit, onShare: () -> Unit, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    SettingsPageScaffold("Diagnostics", onBack) {
        item {
            SectionNote(
                "Crashes, times Android closed Locus, and storage problems, kept on this phone only. Nothing is sent unless you share it. " +
                    "Keys and passwords are removed before anything is shown or shared.",
                NotePadding,
            )
        }
        if (entries.isEmpty()) {
            item { SectionNote("Nothing recorded.", NotePadding.padding(top = Spacing.md)) }
        } else {
            item {
                CardGroup(Modifier.padding(top = Spacing.md)) {
                    entries.forEachIndexed { i, entry ->
                        if (i > 0) GroupDivider(inset = Insets.iconRow)
                        DiagnosticRow(entry)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { confirmClear = true }) { Text("Clear", color = Locus.colors.critical) }
                    TextButton(onClick = onShare) { Text("Share") }
                }
            }
        }
    }
    if (confirmClear) {
        LocusSheet(
            title = "Clear diagnostics?",
            subtitle = "Deletes every entry on this page.",
            onDismiss = { confirmClear = false },
            action = "Clear",
            actionTone = Tone.Critical,
            onAction = { onClear(); confirmClear = false },
        ) {}
    }
}

@Composable
private fun DiagnosticRow(entry: DiagnosticEntry) {
    val icon = when (entry.kind) {
        DiagnosticKind.CRASH, DiagnosticKind.ERROR -> LocusIcons.Code
        DiagnosticKind.EXIT -> LocusIcons.Stop
        DiagnosticKind.STORAGE -> LocusIcons.Key
    }
    val firstLine = entry.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("Locus ") && !it.startsWith("Thread:") }.orEmpty()
    ExpandableRow(title = entry.title, subtitle = firstLine.ifEmpty { " " }, icon = icon, meta = relativeTime(entry.at), expandKey = entry.id) {
        Text(
            entry.text.trimEnd().take(6_000),
            style = MonoCaption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}
