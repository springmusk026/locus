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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import dev.locus.core.linux.EngineText
import dev.locus.core.ui.asString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A row of the self-test: [result] is null until its check has run. */
internal data class SelfTestRow(val id: String, val title: EngineText, val result: SelfTestResult? = null)

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
            SelfTestResult(check.id, check.title, SelfTestLevel.Fail, EngineText.Res(R.string.settings_selftest_check_failed, e::class.java.simpleName), SelfTestFix.RunAgain)
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
    // Read here, since the fix callback runs outside composition.
    val settingsFailed = stringResource(R.string.settings_selftest_settings_failed)
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
                            feedback.show(settingsFailed, Tone.Caution)
                        }
                    }
                }
            }
        },
    )
    if (confirmReset) {
        ConfirmSheet(
            title = stringResource(R.string.settings_selftest_reset_title),
            message = stringResource(R.string.settings_selftest_reset_message),
            confirmLabel = stringResource(R.string.settings_selftest_reset_confirm),
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
        SettingsPageScaffold(stringResource(R.string.settings_selftest_title), onBack) {
            item { ErrorState(stringResource(R.string.settings_selftest_error_title), error, onRetry = onRun, modifier = Modifier.padding(top = Spacing.xxl)) }
        }
        return
    }
    SettingsPageScaffold(stringResource(R.string.settings_selftest_title), onBack) {
        item { Verdict(state, onRun) }
        item { SectionLabel(stringResource(R.string.settings_selftest_checks_section), SectionPadding) }
        item {
            CardGroup {
                if (state.rows.isEmpty()) {
                    ListRow(title = stringResource(R.string.settings_selftest_getting_ready), leading = { InlineSpinner(contentDescription = null) })
                }
                state.rows.forEachIndexed { i, row ->
                    if (i > 0) GroupDivider()
                    CheckRow(row, checking = state.checking == row.id, busy = state.running || state.resetting, onFix = { onFix(row, it) })
                }
            }
        }
        item { SectionLabel(stringResource(R.string.settings_selftest_providers_section), SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_selftest_providers_title),
                    checked = state.includeProviders,
                    onCheckedChange = onIncludeProviders,
                    enabled = !state.running && state.providerCount != 0,
                    subtitle = when (val count = state.providerCount) {
                        0 -> stringResource(R.string.settings_selftest_providers_none)
                        null -> stringResource(R.string.settings_selftest_providers_network)
                        else -> pluralStringResource(R.plurals.settings_selftest_providers_requests, count, count)
                    },
                    icon = LocusIcons.Globe,
                )
            }
        }
        item {
            SectionNote(
                stringResource(R.string.settings_selftest_providers_note),
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
    // No glow: the result chip carries the tone, and a glow behind it would lower its contrast.
    LocusCard {
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (finished) LocusChip(worst.label(), tone = worst.tone(), dot = true, contentDescription = stringResource(R.string.settings_selftest_result, worst.label()))
            Text(
                when {
                    !finished -> stringResource(R.string.settings_selftest_checking)
                    attention == 0 -> stringResource(R.string.settings_selftest_all_good)
                    else -> pluralStringResource(R.plurals.settings_selftest_attention, attention, attention)
                },
                style = LocusType.stateTitle,
                modifier = Modifier.semantics { heading() },
            )
            if (!finished) {
                val fraction = meterFraction(state.done.toLong(), state.rows.size.toLong())
                Meter(fraction, size = MeterSize.Medium, contentDescription = stringResource(R.string.settings_selftest_progress_description, meterPercent(fraction)))
                SupportingText(if (state.rows.isEmpty()) stringResource(R.string.settings_selftest_starting) else stringResource(R.string.settings_selftest_progress, state.done, state.rows.size))
            } else {
                SupportingText(
                    summaryJoin(
                        listOfNotNull(
                            stringResource(R.string.settings_selftest_passed, results.count { it.level == SelfTestLevel.Pass }),
                            results.count { it.level == SelfTestLevel.Skipped }.takeIf { it > 0 }?.let { stringResource(R.string.settings_selftest_skipped, it) },
                            state.finishedAt?.let { stringResource(R.string.settings_selftest_checked, relativeTime(it).lowercase()) },
                        ),
                    ),
                )
            }
        }
        // While it runs the title says "Checking…"; the button comes back when there's something to run again.
        if (!state.running) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {}
                Button(onClick = onRun, modifier = Modifier.heightIn(min = Sizes.touchTarget)) { Text(stringResource(R.string.settings_selftest_run_again)) }
            }
        }
    }
}

@Composable
private fun CheckRow(row: SelfTestRow, checking: Boolean, busy: Boolean, onFix: (SelfTestFix) -> Unit) {
    val result = row.result
    val large = LocalDensity.current.fontScale > LARGE_FONT
    val title = row.title.asString()
    ListRow(
        title = title,
        subtitle = result?.detail?.asString() ?: stringResource(if (checking) R.string.settings_selftest_checking else R.string.settings_selftest_waiting),
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
                checking -> InlineSpinner(contentDescription = stringResource(R.string.settings_selftest_checking_row, title))
                else -> Icon(LocusIcons.Clock, contentDescription = stringResource(R.string.settings_selftest_waiting), tint = Tone.Neutral.color(), modifier = Modifier.size(IconSize.medium))
            }
        },
        // At a large font the fix goes under the text, so the check's words keep the row's width.
        trailing = result?.fix?.takeIf { !large }?.let { fix -> { FixButton(fix, row, busy, onFix) } },
        meta = result?.fix?.takeIf { large }?.let { fix -> { FixButton(fix, row, busy, onFix) } },
    )
}

@Composable
private fun FixButton(fix: SelfTestFix, row: SelfTestRow, busy: Boolean, onFix: (SelfTestFix) -> Unit) {
    val label = stringResource(fix.labelRes)
    val description = stringResource(R.string.settings_selftest_fix_description, label, row.title.asString())
    TextButton(
        onClick = { onFix(fix) },
        enabled = !busy,
        modifier = Modifier.heightIn(min = Sizes.touchTarget).semantics { contentDescription = description },
    ) { Text(label) }
}

/** From this font scale a row's action moves under its text. */
private const val LARGE_FONT = 1.3f

private fun SelfTestLevel.tone() = when (this) {
    SelfTestLevel.Pass -> Tone.Live
    SelfTestLevel.Warn -> Tone.Caution
    SelfTestLevel.Fail -> Tone.Critical
    SelfTestLevel.Skipped -> Tone.Neutral
}

@Composable
private fun SelfTestLevel.label() = stringResource(
    when (this) {
        SelfTestLevel.Pass -> R.string.settings_selftest_level_pass
        SelfTestLevel.Warn -> R.string.settings_selftest_level_warn
        SelfTestLevel.Fail -> R.string.settings_selftest_level_fail
        SelfTestLevel.Skipped -> R.string.settings_selftest_level_skipped
    },
)

private fun SelfTestLevel.icon() = when (this) {
    SelfTestLevel.Pass -> LocusIcons.Check
    SelfTestLevel.Warn -> LocusIcons.Info
    SelfTestLevel.Fail -> LocusIcons.Alert
    SelfTestLevel.Skipped -> LocusIcons.Info
}
