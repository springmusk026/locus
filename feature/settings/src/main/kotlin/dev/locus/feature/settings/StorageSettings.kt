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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.meterFraction
import dev.locus.core.designsystem.meterPercent
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.ui.asString
import dev.locus.core.ui.toUiText
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
    /** The system's own message, shown inside our words ([storageErrorMessage]). */
    val error: UiText? = null,
    val busy: StorageAction? = null,
    val message: StorageMessage? = null,
)

internal data class StorageMessage(val text: UiText, val problem: Boolean = false)

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
                _state.update { it.copy(measuring = false, error = UiText.Plain(e.message ?: e::class.java.simpleName)) }
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
                        storage.clearCaches().let {
                            if (it > 0) UiText.Res(R.string.settings_storage_cleared, LinuxHealth.bytesText(it).toUiText()) else UiText.Res(R.string.settings_storage_nothing_to_clear)
                        },
                    )
                    StorageAction.EmptyTrash -> StorageMessage(
                        UiText.Plural(R.plurals.settings_storage_trash_emptied, storage.emptyTrash()),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StorageMessage(UiText.Res(R.string.settings_storage_failed, e.message ?: e::class.java.simpleName), problem = true)
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
    val resources = LocalResources.current
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        feedback.show(message.text.asString(resources), if (message.problem) Tone.Critical else Tone.Live)
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
            title = stringResource(R.string.settings_storage_clear_title),
            message = stringResource(R.string.settings_storage_clear_message),
            confirmLabel = stringResource(R.string.settings_storage_clear),
            onConfirm = { viewModel.run(action); confirm = null },
            onDismiss = { confirm = null },
        )
        StorageAction.EmptyTrash -> ConfirmSheet(
            title = stringResource(R.string.settings_storage_trash_title),
            message = stringResource(R.string.settings_storage_trash_message),
            confirmLabel = stringResource(R.string.settings_storage_trash_confirm),
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
        report == null && error != null -> SettingsPageScaffold(stringResource(R.string.settings_storage_title), onBack) {
            item { ErrorState(stringResource(R.string.settings_storage_error_title), storageErrorMessage(error), onRetry = onRefresh, modifier = Modifier.padding(top = Spacing.xxl)) }
        }
        report == null -> SettingsPageScaffold(stringResource(R.string.settings_storage_title), onBack) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    InlineSpinner()
                    SupportingText(stringResource(R.string.settings_storage_measuring), textAlign = TextAlign.Center)
                }
            }
        }
        else -> SettingsPageScaffold(stringResource(R.string.settings_storage_title), onBack) {
            item { Summary(report, state.measuring, onRefresh) }
            item { SectionLabel(stringResource(R.string.settings_storage_uses_section), SectionPadding) }
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
                    stringResource(R.string.settings_storage_note),
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
            Text(stringResource(R.string.settings_storage_locus_uses, LinuxHealth.bytesText(report.locusBytes).asString()), style = LocusType.stateTitle)
            if (report.totalBytes > 0) {
                // The bar is the phone's storage, not Locus's: it sits under the line that says so.
                val used = (report.totalBytes - report.freeBytes).coerceAtLeast(0)
                val fraction = meterFraction(used, report.totalBytes)
                SupportingText(stringResource(R.string.settings_storage_phone_full, (fraction * 100).roundToInt(), LinuxHealth.bytesText(report.freeBytes).asString(), LinuxHealth.bytesText(report.totalBytes).asString()))
                Meter(fraction, size = MeterSize.Medium, contentDescription = stringResource(R.string.settings_storage_phone_description, meterPercent(fraction)))
            }
            SupportingText(if (measuring) stringResource(R.string.settings_storage_measuring_again) else stringResource(R.string.settings_storage_measured, relativeTime(report.takenAt).lowercase()))
        }
        androidx.compose.foundation.layout.Row(Modifier.padding(top = Spacing.xs)) {
            Column(Modifier.weight(1f)) {}
            if (measuring) {
                InlineSpinner(contentDescription = stringResource(R.string.settings_storage_measuring_description))
            } else {
                TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text(stringResource(R.string.settings_storage_measure_again)) }
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
        null -> stringResource(R.string.settings_storage_unmeasured)
        0L -> stringResource(R.string.settings_storage_empty)
        else -> LinuxHealth.bytesText(bytes).asString()
    }
    // Each area's one action: a confirmed clean-up, or the screen that manages it.
    val action: Pair<String, () -> Unit>? = when (item.area) {
        StorageArea.Caches -> (stringResource(R.string.settings_storage_action_clear) to { onAction(StorageAction.ClearCaches) }).takeIf { !empty && bytes != null }
        StorageArea.Trash -> (stringResource(R.string.settings_storage_action_empty) to { onAction(StorageAction.EmptyTrash) }).takeIf { !empty && bytes != null }
        StorageArea.Linux -> (stringResource(R.string.settings_storage_action_manage) to onOpenLinux).takeIf { !empty }
        StorageArea.Models -> stringResource(R.string.settings_storage_action_manage) to onOpenModels
        else -> null
    }
    val running = (item.area == StorageArea.Caches && busy == StorageAction.ClearCaches) ||
        (item.area == StorageArea.Trash && busy == StorageAction.EmptyTrash)
    val actionButton: (@Composable () -> Unit)? = when {
        running -> ({ InlineSpinner(contentDescription = stringResource(R.string.settings_storage_working, stringResource(item.area.titleRes))) })
        action != null -> ({
            val description = stringResource(R.string.settings_storage_action_description, action.first, stringResource(item.area.titleRes))
            TextButton(
                onClick = action.second,
                enabled = enabled,
                modifier = Modifier.heightIn(min = Sizes.touchTarget).semantics { contentDescription = description },
            ) { Text(action.first) }
        })
        else -> null
    }
    // At a large font the action goes under the size, so the area's words keep the row's width.
    val large = LocalDensity.current.fontScale > LARGE_FONT
    ListRow(
        title = stringResource(item.area.titleRes),
        subtitle = stringResource(item.area.noteRes),
        titleMaxLines = 2,
        subtitleMaxLines = if (large) Int.MAX_VALUE else 3,
        leading = { IconTile(item.area.icon()) },
        meta = {
            if (bytes == null) LocusChip(stringResource(R.string.settings_storage_unmeasured), tone = Tone.Caution) else SupportingText(size, style = LocusType.mono)
            if (large) actionButton?.invoke()
        },
        trailing = actionButton?.takeIf { !large }?.let { button -> { button() } },
    )
}

/** From this font scale a row's action moves under its text. */
private const val LARGE_FONT = 1.3f

/** What couldn't be measured, in words, with the system's own message kept as the details line. */
@Composable
internal fun storageErrorMessage(error: UiText): String = stringResource(R.string.settings_storage_error_message, error.asString())

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
