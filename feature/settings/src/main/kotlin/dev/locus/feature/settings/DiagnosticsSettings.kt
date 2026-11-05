package dev.locus.feature.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
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
internal fun DiagnosticsRoute(
    onBack: () -> Unit,
    onOpenSelfTest: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
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
        onOpenSelfTest = onOpenSelfTest,
        onOpenStorage = onOpenStorage,
    )
}

@Composable
internal fun DiagnosticsSettings(
    entries: List<DiagnosticEntry>,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onOpenSelfTest: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
) {
    var confirmClear by remember { mutableStateOf(false) }
    SettingsPageScaffold("Diagnostics", onBack) {
        item { SectionLabel("Tools", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Self-test",
                    subtitle = "Check keys, database, storage, notifications and Linux",
                    subtitleMaxLines = 2,
                    onClick = onOpenSelfTest,
                    leading = { IconTile(LocusIcons.Check) },
                    trailing = { RowChevron() },
                )
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = "Storage",
                    subtitle = "What each part of Locus uses, and clean-up",
                    subtitleMaxLines = 2,
                    onClick = onOpenStorage,
                    leading = { IconTile(LocusIcons.Folder) },
                    trailing = { RowChevron() },
                )
            }
        }
        item { SectionLabel("Recent problems", SectionPadding) }
        item {
            SectionNote(
                "Crashes, times Android closed Locus, and storage problems, kept on this phone only. Nothing is sent unless you share it. " +
                    "Keys and passwords are removed before anything is shown or shared.",
                NotePadding,
            )
        }
        if (entries.isEmpty()) {
            item { EmptyState("No problems recorded", "Crashes and times Android closed Locus show up here.", icon = LocusIcons.Check) }
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
                CardGroup(Modifier.padding(top = Spacing.md)) {
                    ListRow(
                        title = "Share report",
                        subtitle = "Opens the share sheet with these entries",
                        subtitleMaxLines = 2,
                        onClick = onShare,
                        leading = { IconTile(LocusIcons.Share) },
                    )
                    GroupDivider(inset = Insets.iconRow)
                    ListRow(
                        title = "Clear",
                        subtitle = "Deletes every entry on this page",
                        subtitleMaxLines = 2,
                        onClick = { confirmClear = true },
                        leading = { IconTile(LocusIcons.Trash) },
                    )
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmSheet(
            title = "Clear diagnostics?",
            message = "Deletes every entry on this page.",
            confirmLabel = "Clear",
            tone = Tone.Critical,
            onConfirm = { onClear(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun DiagnosticRow(entry: DiagnosticEntry) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    Column {
        ListRow(
            title = entry.title,
            subtitle = "${entry.kind.words()} · ${relativeTime(entry.at)}",
            // The title says what happened: it wraps rather than being cut, and has the row's whole width.
            titleMaxLines = 3,
            subtitleMaxLines = 2,
            onClick = { expanded = !expanded },
            leading = { IconTile(entry.kind.icon()) },
        )
        AnimatedVisibility(expanded) {
            // The full record, wrapped so nothing is hidden past the card's edge.
            SupportingText(
                entry.text.trimEnd().take(6_000),
                Modifier.fillMaxWidth().padding(start = Insets.iconRow, end = Spacing.lg, bottom = Spacing.md),
                style = LocusType.mono,
            )
        }
    }
}

/** What kind of problem an entry is, in words, under its title. */
private fun DiagnosticKind.words() = when (this) {
    DiagnosticKind.CRASH -> "Locus stopped with an error"
    DiagnosticKind.EXIT -> "Android closed Locus"
    DiagnosticKind.ERROR -> "A request or task failed"
    DiagnosticKind.STORAGE -> "Storage"
}

private fun DiagnosticKind.icon() = when (this) {
    DiagnosticKind.CRASH -> LocusIcons.Alert
    DiagnosticKind.EXIT -> LocusIcons.Close
    DiagnosticKind.ERROR -> LocusIcons.Info
    DiagnosticKind.STORAGE -> LocusIcons.Folder
}
