package dev.locus.feature.settings

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
internal fun updateStatusLine(info: UpdateInfo?, state: UpdateState, now: Long, relative: (Long, Long) -> String): String {
    val checked = info?.checkedAt?.takeIf { it > 0 }?.let { "checked ${relative(it, now).replaceFirstChar(Char::lowercase)}" }
    return when (state) {
        UpdateState.Checking -> "Checking GitHub…"
        is UpdateState.UpToDate -> listOfNotNull(
            if (state.latest == null) "No release published yet" else "Up to date (${info?.installedVersion ?: state.latest})",
            checked,
        ).joinToString(" · ")
        is UpdateState.Available -> listOfNotNull(
            when {
                state.skipped -> "${state.release.version} skipped"
                state.release.prerelease -> "Beta ${state.release.version} available"
                else -> "${state.release.version} available"
            },
            checked,
        ).joinToString(" · ")
        is UpdateState.Downloading, is UpdateState.Verifying, is UpdateState.ReadyToInstall, is UpdateState.Installing ->
            "Updating to ${releaseOf(state)?.version}"
        is UpdateState.Failed -> checked?.replaceFirstChar(Char::uppercase) ?: "Not checked yet"
        UpdateState.Idle -> checked?.replaceFirstChar(Char::uppercase) ?: if (info?.enabled == true) "Waiting for Wi-Fi to check" else "Not checked yet"
    }
}

/** The one-line explanation for a build that can't update itself here; null when it can. */
internal fun availabilityNote(availability: UpdateAvailability): String? = when (availability) {
    UpdateAvailability.Available -> null
    UpdateAvailability.DisabledInBuild -> "This build doesn't check for updates. They come from where you installed Locus (F-Droid or IzzyOnDroid)."
    UpdateAvailability.DebugBuild -> "Debug build: updates come from your own builds. Check now still shows the latest release."
    is UpdateAvailability.FromStore -> "Installed from ${availability.store}, which keeps Locus up to date."
}

/** What went wrong, in words, for [problem]. */
internal fun problemMessage(problem: UpdateProblem, timeOf: (Long) -> String): String = when (problem) {
    UpdateProblem.NoNetwork -> "Couldn't reach GitHub. Check your connection and try again."
    is UpdateProblem.RateLimited -> "GitHub limits how often a network can ask without signing in, and this one has asked too often. Try again after ${timeOf(problem.until)}."
    is UpdateProblem.Server -> if (problem.code == 0) "GitHub's answer couldn't be read. Try again later." else "GitHub answered with an error (HTTP ${problem.code}). Try again later."
    is UpdateProblem.NoApkForDevice -> "This release has no APK for this phone (${problem.abis.joinToString().ifEmpty { "unknown ABI" }}). See the release page."
    UpdateProblem.NoChecksum -> "This release doesn't publish a SHA-256 checksum Locus can check the APK against, so Locus won't install it. You can download it yourself from the release page."
    UpdateProblem.ChecksumMismatch -> "The download didn't match the release's SHA-256 checksum, so Locus deleted it. Try again; if it happens again, report it on GitHub."
    UpdateProblem.SignatureMismatch -> "This APK is signed with a different key than the Locus on this phone, so Android won't install it as an update. " +
        "To switch, make a backup in Settings → Backup & restore, uninstall Locus, install the new APK, then restore the backup."
    UpdateProblem.BadApk -> "The download isn't a newer Locus, so Locus deleted it."
    UpdateProblem.InstallNotAllowed -> "Android needs your permission for Locus to install apps. Turn on \"Allow from this source\" for Locus, then come back here."
    UpdateProblem.InstallCancelled -> "The update was cancelled in Android's installer. The verified download is kept."
    UpdateProblem.Storage -> "There isn't enough free space for the update. Free some up and try again."
    is UpdateProblem.InstallFailed -> "Android couldn't install the update" + (problem.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
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

/** "12.3 of 17.1 MB", or "12.3 MB" when the size isn't known. */
internal fun downloadProgress(bytes: Long, total: Long): String {
    fun mb(b: Long) = "%.1f".format(java.util.Locale.ROOT, b / 1_000_000.0)
    return if (total > 0) "${mb(bytes)} of ${mb(total)} MB" else "${mb(bytes)} MB"
}

internal const val RELEASES_PAGE = ReleaseSource.RELEASES_URL
