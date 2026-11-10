package dev.locus.feature.settings

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.updates.UpdateAvailability
import dev.locus.core.data.updates.UpdateInfo
import dev.locus.core.data.updates.UpdateProblem
import dev.locus.core.data.updates.UpdateState
import dev.locus.core.data.updates.Updates
import dev.locus.core.data.updates.ReleaseSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.RoundingMode
import java.text.NumberFormat
import javax.inject.Inject

/** Settings → About & privacy → Updates: the setting, Check now, and the release in hand. */
data class UpdatesUiState(
    val info: UpdateInfo? = null,
    val state: UpdateState = UpdateState.Idle,
)

@HiltViewModel
class UpdatesViewModel @Inject constructor(private val updates: Updates) : ViewModel() {
    val uiState: StateFlow<UpdatesUiState> = combine(updates.info, updates.state, ::UpdatesUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdatesUiState(state = updates.state.value))

    fun setEnabled(on: Boolean) = viewModelScope.launch { updates.setEnabled(on) }
    fun setBeta(on: Boolean) = viewModelScope.launch { updates.setBeta(on) }
    fun checkNow() = viewModelScope.launch { updates.check(manual = true) }
    fun download() = updates.download()
    fun cancelDownload() = updates.cancelDownload()
    fun install() = updates.install()
    fun skip(version: String) = viewModelScope.launch { updates.skip(version) }
    fun unskip() = viewModelScope.launch { updates.unskip() }
    fun dismissProblem() = updates.dismissProblem()
    fun retry() = viewModelScope.launch { updates.retry() }

    /** Back in front, maybe from "Install unknown apps". */
    fun onResume() = updates.onReturnFromSettings()
}

// ---- Words (plain functions so they are easy to test) ----

/** What About says under "Check now". */
internal fun updateStatusLine(res: Resources, info: UpdateInfo?, state: UpdateState, now: Long, relative: (Long, Long) -> String): String {
    val ago = info?.checkedAt?.takeIf { it > 0 }?.let { relative(it, now).replaceFirstChar(Char::lowercase) }
    val checked = ago?.let { res.getString(R.string.settings_updates_status_checked, it) }
    return when (state) {
        UpdateState.Checking -> res.getString(R.string.settings_updates_status_checking)
        is UpdateState.UpToDate -> summaryJoin(
            res,
            listOfNotNull(
                if (state.latest == null) {
                    res.getString(R.string.settings_updates_status_no_release)
                } else {
                    res.getString(R.string.settings_updates_status_up_to_date, info?.installedVersion ?: state.latest)
                },
                checked,
            ),
        )
        is UpdateState.Available -> summaryJoin(
            res,
            listOfNotNull(
                when {
                    state.skipped -> res.getString(R.string.settings_updates_status_skipped, state.release.version)
                    state.release.prerelease -> res.getString(R.string.settings_updates_status_beta_available, state.release.version)
                    else -> res.getString(R.string.settings_updates_status_available, state.release.version)
                },
                checked,
            ),
        )
        is UpdateState.Downloading, is UpdateState.Verifying, is UpdateState.ReadyToInstall, is UpdateState.Installing ->
            res.getString(R.string.settings_updates_status_updating, releaseOf(state)?.version.toString())
        is UpdateState.Failed -> ago?.let { res.getString(R.string.settings_updates_status_checked_start, it) } ?: res.getString(R.string.settings_updates_status_not_checked)
        UpdateState.Idle -> ago?.let { res.getString(R.string.settings_updates_status_checked_start, it) }
            ?: res.getString(if (info?.enabled == true) R.string.settings_updates_status_waiting_wifi else R.string.settings_updates_status_not_checked)
    }
}

/** The one-line explanation for a build that can't update itself here; null when it can. */
internal fun availabilityNote(res: Resources, availability: UpdateAvailability): String? = when (availability) {
    UpdateAvailability.Available -> null
    UpdateAvailability.DisabledInBuild -> res.getString(R.string.settings_updates_note_disabled)
    UpdateAvailability.DebugBuild -> res.getString(R.string.settings_updates_note_debug)
    is UpdateAvailability.FromStore -> res.getString(R.string.settings_updates_note_store, availability.store)
}

/** What went wrong, in words, for [problem]. */
internal fun problemMessage(res: Resources, problem: UpdateProblem, timeOf: (Long) -> String): String = when (problem) {
    UpdateProblem.NoNetwork -> res.getString(R.string.settings_updates_problem_no_network)
    is UpdateProblem.RateLimited -> res.getString(R.string.settings_updates_problem_rate_limited, timeOf(problem.until))
    is UpdateProblem.Server ->
        if (problem.code == 0) res.getString(R.string.settings_updates_problem_unreadable) else res.getString(R.string.settings_updates_problem_server, problem.code)
    is UpdateProblem.NoApkForDevice ->
        res.getString(R.string.settings_updates_problem_no_apk, problem.abis.joinToString().ifEmpty { res.getString(R.string.settings_updates_problem_unknown_abi) })
    UpdateProblem.NoChecksum -> res.getString(R.string.settings_updates_problem_no_checksum)
    UpdateProblem.ChecksumMismatch -> res.getString(R.string.settings_updates_problem_checksum_mismatch)
    UpdateProblem.SignatureMismatch -> res.getString(R.string.settings_updates_problem_signature_mismatch)
    UpdateProblem.BadApk -> res.getString(R.string.settings_updates_problem_bad_apk)
    UpdateProblem.InstallNotAllowed -> res.getString(R.string.settings_updates_problem_install_not_allowed)
    UpdateProblem.InstallCancelled -> res.getString(R.string.settings_updates_problem_install_cancelled)
    UpdateProblem.Storage -> res.getString(R.string.settings_updates_problem_storage)
    // Android's own reason, as it gave it.
    is UpdateProblem.InstallFailed -> problem.message?.takeIf { it.isNotBlank() }
        ?.let { res.getString(R.string.settings_updates_problem_install_failed_reason, it) }
        ?: res.getString(R.string.settings_updates_problem_install_failed)
}

internal fun releaseOf(state: UpdateState) = when (state) {
    is UpdateState.Available -> state.release
    is UpdateState.Downloading -> state.release
    is UpdateState.Verifying -> state.release
    is UpdateState.ReadyToInstall -> state.release
    is UpdateState.Installing -> state.release
    is UpdateState.Failed -> state.release
    else -> null
}

/** "12.3 of 17.1 MB", or "12.3 MB" when the size isn't known; the digits in the language shown. */
internal fun downloadProgress(res: Resources, bytes: Long, total: Long): String {
    val number = NumberFormat.getNumberInstance(res.configuration.locales[0]).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        roundingMode = RoundingMode.HALF_UP
        isGroupingUsed = false
    }
    fun mb(b: Long) = number.format(b / 1_000_000.0)
    return if (total > 0) res.getString(R.string.settings_updates_progress_of, mb(bytes), mb(total)) else res.getString(R.string.settings_updates_progress, mb(bytes))
}

internal const val RELEASES_PAGE = ReleaseSource.RELEASES_URL
