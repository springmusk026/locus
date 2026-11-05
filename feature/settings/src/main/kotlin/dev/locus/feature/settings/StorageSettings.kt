package dev.locus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.diagnostics.StorageArea
import dev.locus.core.data.diagnostics.StorageItem
import dev.locus.core.data.diagnostics.StorageReport
import dev.locus.core.data.diagnostics.StorageUsage
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.ErrorState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.MeterSize
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.meterFraction
import dev.locus.core.designsystem.meterPercent
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.linux.LinuxHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** A clean-up on the Storage page. Both are confirmed in a sheet first. */
internal enum class StorageAction { ClearCaches, EmptyTrash }

/**
 * The Storage page: [report] is null until the first measure ends ([measuring] meanwhile), [error]
 * when it failed. [busy] is the clean-up running now; [message] is its outcome, shown once.
 */
internal data class StorageUiState(
    val report: StorageReport? = null,
    val measuring: Boolean = true,
    val error: String? = null,
    val busy: StorageAction? = null,
    val message: StorageMessage? = null,
)

internal data class StorageMessage(val text: String, val problem: Boolean = false)

@HiltViewModel
internal class StorageViewModel @Inject constructor(private val storage: StorageUsage) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()
    private var measuring: Job? = null

    init {
        refresh()
    }

    /** Measures every area again, off the main thread (StorageUsage walks on the IO dispatcher). */
    fun refresh() {
        measuring?.cancel()
        measuring = viewModelScope.launch {
            _state.update { it.copy(measuring = true, error = null) }
            try {
                val report = storage.measure()
                _state.update { it.copy(report = report, measuring = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(measuring = false, error = e.message ?: e::class.java.simpleName) }
            }
        }
    }

    /** Runs the clean-up the user confirmed, then measures again. */
    fun run(action: StorageAction) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = action) }
            val message = try {
                when (action) {
                    StorageAction.ClearCaches -> StorageMessage(
                        storage.clearCaches().let { if (it > 0) "Cleared ${LinuxHealth.bytes(it)} of caches." else "No caches to clear (files in use were kept)." },
                    )
                    StorageAction.EmptyTrash -> StorageMessage(
                        storage.emptyTrash().let { if (it == 1) "Emptied the trash: 1 item deleted." else "Emptied the trash: $it items deleted." },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StorageMessage("Couldn't finish: ${e.message ?: e::class.java.simpleName}", problem = true)
            }
            _state.update { it.copy(busy = null, message = message) }
            refresh()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

/** Settings → About & privacy → Diagnostics → Storage. */
@Composable
internal fun StorageRoute(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenLinux: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedback = Locus.feedback
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        feedback.show(message.text, if (message.problem) Tone.Critical else Tone.Live)
        viewModel.messageShown()
    }
    var confirm by remember { mutableStateOf<StorageAction?>(null) }
    StorageSettings(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onAction = { confirm = it },
        onOpenModels = onOpenModels,
        onOpenLinux = onOpenLinux,
    )
    when (val action = confirm) {
        StorageAction.ClearCaches -> ConfirmSheet(
            title = "Clear caches?",
            message = "Deletes temporary files: exported chats waiting to be shared, downloaded model details and the like. Locus makes " +
                "them again when needed. Files touched in the last 10 minutes are kept, in case they're in use. Your chats, files, " +
                "memory and keys aren't touched.",
            confirmLabel = "Clear",
            onConfirm = { viewModel.run(action); confirm = null },
            onDismiss = { confirm = null },
        )
        StorageAction.EmptyTrash -> ConfirmSheet(
            title = "Empty the trash?",
            message = "Deletes every file in the workspace trash for good. They can't be restored afterwards, and Undo stops working " +
                "for earlier file changes whose old version was kept there.",
            confirmLabel = "Empty trash",
            tone = Tone.Critical,
            onConfirm = { viewModel.run(action); confirm = null },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

@Composable
internal fun StorageSettings(
    state: StorageUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (StorageAction) -> Unit,
    onOpenModels: () -> Unit,
    onOpenLinux: () -> Unit,
) {
    val report = state.report
    val error = state.error
    when {
        report == null && error != null -> SettingsPageScaffold("Storage", onBack) {
            item { ErrorState("Couldn't measure storage", storageErrorMessage(error), onRetry = onRefresh, modifier = Modifier.padding(top = Spacing.xxl)) }
        }
        report == null -> SettingsPageScaffold("Storage", onBack) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    InlineSpinner()
                    SupportingText("Measuring each part of Locus…", textAlign = TextAlign.Center)
                }
            }
        }
        else -> SettingsPageScaffold("Storage", onBack) {
            item { Summary(report, state.measuring, onRefresh) }
            item { SectionLabel("What uses space", SectionPadding) }
            item {
                CardGroup {
                    report.items.forEachIndexed { i, item ->
                        if (i > 0) GroupDivider(inset = Insets.iconRow)
                        AreaRow(item, state.busy, enabled = state.busy == null, onAction = onAction, onOpenModels = onOpenModels, onOpenLinux = onOpenLinux)
                    }
                }
            }
            item {
                SectionNote(
                    "Sizes are measured on this phone without following links, so a link inside the workspace or Linux never counts " +
                        "(or reaches) anything outside it. Logs are cleared on the Diagnostics page.",
                    NotePadding,
                )
            }
        }
    }
}

@Composable
private fun Summary(report: StorageReport, measuring: Boolean, onRefresh: () -> Unit) {
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Locus uses ${LinuxHealth.bytes(report.locusBytes)}", style = LocusType.stateTitle)
            if (report.totalBytes > 0) {
                // The bar is the phone's storage, not Locus's: it sits under the line that says so.
                val used = (report.totalBytes - report.freeBytes).coerceAtLeast(0)
                val fraction = meterFraction(used, report.totalBytes)
                SupportingText("Phone ${(fraction * 100).roundToInt()}% full: ${LinuxHealth.bytes(report.freeBytes)} free of ${LinuxHealth.bytes(report.totalBytes)}")
                Meter(fraction, size = MeterSize.Medium, contentDescription = "Phone storage, ${meterPercent(fraction)} used")
            }
            SupportingText(if (measuring) "Measuring again…" else "Measured ${relativeTime(report.takenAt).lowercase()}")
        }
        androidx.compose.foundation.layout.Row(Modifier.padding(top = Spacing.xs)) {
            Column(Modifier.weight(1f)) {}
            if (measuring) {
                InlineSpinner(contentDescription = "Measuring storage")
            } else {
                TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text("Measure again") }
            }
        }
    }
}

@Composable
private fun AreaRow(
    item: StorageItem,
    busy: StorageAction?,
    enabled: Boolean,
    onAction: (StorageAction) -> Unit,
    onOpenModels: () -> Unit,
    onOpenLinux: () -> Unit,
) {
    val bytes = item.bytes
    val empty = bytes == 0L
    val size = when (bytes) {
        null -> "Couldn't measure"
        0L -> "Empty"
        else -> LinuxHealth.bytes(bytes)
    }
    // Each area's one action: a confirmed clean-up, or the screen that manages it.
    val action: Pair<String, () -> Unit>? = when (item.area) {
        StorageArea.Caches -> ("Clear" to { onAction(StorageAction.ClearCaches) }).takeIf { !empty && bytes != null }
        StorageArea.Trash -> ("Empty" to { onAction(StorageAction.EmptyTrash) }).takeIf { !empty && bytes != null }
        StorageArea.Linux -> ("Manage" to onOpenLinux).takeIf { !empty }
        StorageArea.Models -> "Manage" to onOpenModels
        else -> null
    }
    val running = (item.area == StorageArea.Caches && busy == StorageAction.ClearCaches) ||
        (item.area == StorageArea.Trash && busy == StorageAction.EmptyTrash)
    val actionButton: (@Composable () -> Unit)? = when {
        running -> ({ InlineSpinner(contentDescription = "${item.area.title}: working") })
        action != null -> ({
            TextButton(
                onClick = action.second,
                enabled = enabled,
                modifier = Modifier.heightIn(min = Sizes.touchTarget).semantics { contentDescription = "${action.first}: ${item.area.title}" },
            ) { Text(action.first) }
        })
        else -> null
    }
    // At a large font the action goes under the size, so the area's words keep the row's width.
    val large = LocalDensity.current.fontScale > LARGE_FONT
    ListRow(
        title = item.area.title,
        subtitle = item.area.note,
        titleMaxLines = 2,
        subtitleMaxLines = if (large) Int.MAX_VALUE else 3,
        leading = { IconTile(item.area.icon()) },
        meta = {
            if (bytes == null) LocusChip("Couldn't measure", tone = Tone.Caution) else SupportingText(size, style = LocusType.mono)
            if (large) actionButton?.invoke()
        },
        trailing = actionButton?.takeIf { !large }?.let { button -> { button() } },
    )
}

/** From this font scale a row's action moves under its text. */
private const val LARGE_FONT = 1.3f

/** What couldn't be measured, in words, with the system's own message kept as the details line. */
internal fun storageErrorMessage(error: String): String =
    "Locus couldn't read the size of one of its folders. Try again; if it keeps failing, restart Locus.\n\nDetails: $error"

private fun StorageArea.icon() = when (this) {
    StorageArea.Database -> LocusIcons.Chats
    StorageArea.Workspace -> LocusIcons.Folder
    StorageArea.Trash -> LocusIcons.Trash
    StorageArea.Linux -> LocusIcons.Terminal
    StorageArea.Models -> LocusIcons.Phone
    StorageArea.Browser -> LocusIcons.Globe
    StorageArea.Caches -> LocusIcons.Regenerate
    StorageArea.Logs -> LocusIcons.Note
    StorageArea.AgentHome -> LocusIcons.Library
}
