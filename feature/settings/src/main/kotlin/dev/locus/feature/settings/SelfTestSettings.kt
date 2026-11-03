package dev.locus.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.diagnostics.DiagnosticsRepository
import dev.locus.core.data.diagnostics.SelfTest
import dev.locus.core.data.diagnostics.SelfTestCheck
import dev.locus.core.data.diagnostics.SelfTestFix
import dev.locus.core.data.diagnostics.SelfTestLevel
import dev.locus.core.data.diagnostics.SelfTestResult
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.ErrorState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.ListRow
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
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.meterFraction
import dev.locus.core.designsystem.meterPercent
import dev.locus.core.designsystem.relativeTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A row of the self-test: [result] is null until its check has run. */
internal data class SelfTestRow(val id: String, val title: String, val result: SelfTestResult? = null)

/**
 * The self-test page. [rows] is empty before the first run starts; [checking] is the id of the check
 * running now. [error] is set when the run couldn't start at all.
 */
internal data class SelfTestUiState(
    val rows: List<SelfTestRow> = emptyList(),
    val checking: String? = null,
    val running: Boolean = false,
    val includeProviders: Boolean = false,
    val providerCount: Int? = null,
    val finishedAt: Long? = null,
    val resetting: Boolean = false,
    val error: String? = null,
) {
    val done: Int get() = rows.count { it.result != null }
}

@HiltViewModel
internal class SelfTestViewModel @Inject constructor(
    private val selfTest: SelfTest,
    private val diagnostics: DiagnosticsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SelfTestUiState())
    val state: StateFlow<SelfTestUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var checks: Map<String, SelfTestCheck> = emptyMap()

    init {
        // The local checks are quick and stay on the phone, so they run as the page opens.
        run()
        viewModelScope.launch { _state.update { it.copy(providerCount = runCatching { selfTest.cloudProviders().size }.getOrNull()) } }
    }

    fun setIncludeProviders(include: Boolean) = _state.update { it.copy(includeProviders = include) }

    /** Runs every check in order; the provider checks only when the user turned them on. */
    fun run() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, error = null) }
            val list = try {
                selfTest.checks(providers = _state.value.includeProviders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e.message ?: e::class.java.simpleName) }
                return@launch
            }
            checks = list.associateBy { it.id }
            _state.update { s -> s.copy(rows = list.map { SelfTestRow(it.id, it.title) }, finishedAt = null) }
            list.forEach { check -> runOne(check) }
            _state.update { it.copy(running = false, checking = null, finishedAt = System.currentTimeMillis()) }
        }
    }

    /** Runs one check again (its Try again). */
    fun retry(id: String) {
        val check = checks[id] ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { s -> s.copy(running = true, rows = s.rows.map { if (it.id == id) it.copy(result = null) else it }) }
            runOne(check)
            _state.update { it.copy(running = false, checking = null, finishedAt = System.currentTimeMillis()) }
        }
    }

    /** The confirmed reset of saved keys that can't be opened (a copy is kept), then the vault check again. */
    fun resetSavedKeys() {
        if (_state.value.resetting) return
        viewModelScope.launch {
            _state.update { it.copy(resetting = true) }
            runCatching { diagnostics.resetSavedKeys() }
            _state.update { it.copy(resetting = false) }
            retry(SelfTest.VAULT)
        }
    }

    private suspend fun runOne(check: SelfTestCheck) {
        _state.update { it.copy(checking = check.id) }
        val result = try {
            check.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SelfTestResult(check.id, check.title, SelfTestLevel.Fail, "The check itself failed (${e::class.java.simpleName}).", SelfTestFix.RunAgain)
        }
        _state.update { s -> s.copy(rows = s.rows.map { if (it.id == check.id) it.copy(result = result) else it }) }
    }
}

/** Settings → About & privacy → Diagnostics → Self-test. */
@Composable
internal fun SelfTestRoute(
    onBack: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenLinux: () -> Unit,
    viewModel: SelfTestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val feedback = Locus.feedback
    var confirmReset by remember { mutableStateOf(false) }
    SelfTestSettings(
        state = state,
        onBack = onBack,
        onRun = viewModel::run,
        onIncludeProviders = viewModel::setIncludeProviders,
        onFix = { row, fix ->
            when (fix) {
                SelfTestFix.RunAgain -> viewModel.retry(row.id)
                SelfTestFix.ResetSavedKeys -> confirmReset = true
                SelfTestFix.OpenStorage -> onOpenStorage()
                SelfTestFix.OpenLinux -> onOpenLinux()
                SelfTestFix.OpenModels -> onOpenModels()
                SelfTestFix.NotificationSettings -> {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        try {
                            context.startActivity(fallback)
                        } catch (_: ActivityNotFoundException) {
                            feedback.show("Couldn't open Android's settings. Turn notifications on in Settings → Apps → Locus.", Tone.Caution)
                        }
                    }
                }
            }
        },
    )
    if (confirmReset) {
        ConfirmSheet(
            title = "Reset saved keys?",
            message = "Locus makes new keys and starts with an empty vault, so you'll enter your API keys and passwords again. " +
                "A copy of the old keys is kept, and restored on its own if they become readable again.",
            confirmLabel = "Reset",
            tone = Tone.Critical,
            busy = state.resetting,
            onConfirm = { viewModel.resetSavedKeys(); confirmReset = false },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
internal fun SelfTestSettings(
    state: SelfTestUiState,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onIncludeProviders: (Boolean) -> Unit,
    onFix: (SelfTestRow, SelfTestFix) -> Unit,
) {
    val error = state.error
    if (error != null && state.rows.isEmpty()) {
        SettingsPageScaffold("Self-test", onBack) {
            item { ErrorState("Couldn't start the self-test", error, onRetry = onRun, modifier = Modifier.padding(top = Spacing.xxl)) }
        }
        return
    }
    SettingsPageScaffold("Self-test", onBack) {
        item { Verdict(state, onRun) }
        item { SectionLabel("Checks", SectionPadding) }
        item {
            CardGroup {
                if (state.rows.isEmpty()) {
                    ListRow(title = "Getting ready…", leading = { InlineSpinner(contentDescription = null) })
                }
                state.rows.forEachIndexed { i, row ->
                    if (i > 0) GroupDivider()
                    CheckRow(row, checking = state.checking == row.id, busy = state.running || state.resetting, onFix = { onFix(row, it) })
                }
            }
        }
        item { SectionLabel("Providers", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = "Also check providers",
                    checked = state.includeProviders,
                    onCheckedChange = onIncludeProviders,
                    enabled = !state.running && state.providerCount != 0,
                    subtitle = when (state.providerCount) {
                        0 -> "No cloud providers are set up."
                        null -> "Uses the network."
                        1 -> "Uses the network: one request to your provider."
                        else -> "Uses the network: one request to each of your ${state.providerCount} providers."
                    },
                    icon = LocusIcons.Globe,
                )
            }
        }
        item {
            SectionNote(
                "Off by default. When on, the next run asks each cloud provider for its model list at its own address, with its own key, " +
                    "to see that it answers and accepts the key. Nothing else is sent, and keys never go anywhere else. Every other check " +
                    "stays on this phone, and nothing here is saved or shared.",
                NotePadding,
            )
        }
    }
}

@Composable
private fun Verdict(state: SelfTestUiState, onRun: () -> Unit) {
    val results = state.rows.mapNotNull { it.result }
    val worst = results.map { it.level }.filter { it != SelfTestLevel.Skipped }.maxByOrNull { it.ordinal } ?: SelfTestLevel.Pass
    val attention = results.count { it.level == SelfTestLevel.Warn || it.level == SelfTestLevel.Fail }
    val finished = !state.running && state.rows.isNotEmpty() && state.done == state.rows.size
    LocusCard(glow = worst.tone().color().takeIf { finished && worst != SelfTestLevel.Pass }) {
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (finished) LocusChip(worst.label(), tone = worst.tone(), dot = true, contentDescription = "Result: ${worst.label()}")
            Text(
                when {
                    !finished -> "Checking…"
                    attention == 0 -> "Everything checked works"
                    attention == 1 -> "1 check needs attention"
                    else -> "$attention checks need attention"
                },
                style = LocusType.stateTitle,
                modifier = Modifier.semantics { heading() },
            )
            if (!finished) {
                val fraction = meterFraction(state.done.toLong(), state.rows.size.toLong())
                Meter(fraction, size = MeterSize.Medium, contentDescription = "Self-test, ${meterPercent(fraction)} done")
                SupportingText(if (state.rows.isEmpty()) "Starting" else "${state.done} of ${state.rows.size} done")
            } else {
                SupportingText(
                    buildString {
                        append("${results.count { it.level == SelfTestLevel.Pass }} passed")
                        results.count { it.level == SelfTestLevel.Skipped }.takeIf { it > 0 }?.let { append(" · $it skipped") }
                        state.finishedAt?.let { append(" · checked ${relativeTime(it).lowercase()}") }
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {}
            Button(onClick = onRun, enabled = !state.running, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                Text(if (state.running) "Checking…" else "Run again")
            }
        }
    }
}

@Composable
private fun CheckRow(row: SelfTestRow, checking: Boolean, busy: Boolean, onFix: (SelfTestFix) -> Unit) {
    val result = row.result
    ListRow(
        title = row.title,
        subtitle = result?.detail ?: if (checking) "Checking…" else "Waiting",
        titleMaxLines = 2,
        subtitleMaxLines = 6,
        leading = {
            when {
                result != null -> Icon(
                    result.level.icon(),
                    contentDescription = result.level.label(),
                    tint = result.level.tone().color(),
                    modifier = Modifier.size(IconSize.medium),
                )
                checking -> InlineSpinner(contentDescription = "Checking ${row.title}")
                else -> Icon(LocusIcons.Clock, contentDescription = "Waiting", tint = Tone.Neutral.color(), modifier = Modifier.size(IconSize.medium))
            }
        },
        trailing = result?.fix?.let { fix ->
            {
                TextButton(
                    onClick = { onFix(fix) },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = Sizes.touchTarget).semantics { contentDescription = "${fix.label}: ${row.title}" },
                ) { Text(fix.label) }
            }
        },
    )
}

private fun SelfTestLevel.tone() = when (this) {
    SelfTestLevel.Pass -> Tone.Live
    SelfTestLevel.Warn -> Tone.Caution
    SelfTestLevel.Fail -> Tone.Critical
    SelfTestLevel.Skipped -> Tone.Neutral
}

private fun SelfTestLevel.label() = when (this) {
    SelfTestLevel.Pass -> "Passed"
    SelfTestLevel.Warn -> "Warning"
    SelfTestLevel.Fail -> "Failed"
    SelfTestLevel.Skipped -> "Skipped"
}

private fun SelfTestLevel.icon() = when (this) {
    SelfTestLevel.Pass -> LocusIcons.Check
    SelfTestLevel.Warn -> LocusIcons.Info
    SelfTestLevel.Fail -> LocusIcons.Alert
    SelfTestLevel.Skipped -> LocusIcons.Info
}
