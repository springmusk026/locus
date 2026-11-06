package dev.locus.core.data.updates

import android.content.pm.PackageInstaller
import dev.locus.core.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** Whether this install can update itself from GitHub. */
sealed interface UpdateAvailability {
    /** A GitHub release build, sideloaded: the check can be turned on. */
    data object Available : UpdateAvailability

    /** Built with `locus.updateCheck=false` (F-Droid, IzzyOnDroid builds). */
    data object DisabledInBuild : UpdateAvailability

    /** The debug app (`dev.locus.debug`): it can look, but releases install as a different app. */
    data object DebugBuild : UpdateAvailability

    /** Installed by a store that updates Locus itself. */
    data class FromStore(val store: String) : UpdateAvailability

    /** Whether the daily check and the toggle exist at all. */
    val canSchedule: Boolean get() = this == Available

    /** Whether "Check now" is offered. */
    val canCheck: Boolean get() = this == Available || this == DebugBuild
}

/** Why an update step failed; each has its own message and next action in the UI. */
sealed interface UpdateProblem {
    data object NoNetwork : UpdateProblem
    data class RateLimited(val until: Long) : UpdateProblem
    data class Server(val code: Int) : UpdateProblem

    /** The release has no APK for this phone's ABIs. */
    data class NoApkForDevice(val abis: List<String>) : UpdateProblem

    /** The release lists no SHA-256 for the APK, or two that disagree. */
    data object NoChecksum : UpdateProblem

    /** The download's SHA-256 isn't the published one; the file was deleted. */
    data object ChecksumMismatch : UpdateProblem

    /** Signed with another key than the installed app: Android would refuse it. */
    data object SignatureMismatch : UpdateProblem

    /** The APK isn't Locus, isn't newer, or can't be read; the file was deleted. */
    data object BadApk : UpdateProblem

    /** "Install unknown apps" is off for Locus. */
    data object InstallNotAllowed : UpdateProblem

    /** The user cancelled the system installer. */
    data object InstallCancelled : UpdateProblem

    /** Not enough space to download or install. */
    data object Storage : UpdateProblem

    data class InstallFailed(val message: String?) : UpdateProblem
}

/** Where the update stands, for Settings → About & privacy. */
sealed interface UpdateState {
    /** Nothing checked yet (since this build was installed). */
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** [latest] is the newest release (null when there is none yet), and it isn't newer. */
    data class UpToDate(val latest: String?) : UpdateState

    data class Available(val release: Release, val skipped: Boolean) : UpdateState
    data class Downloading(val release: Release, val bytes: Long, val total: Long) : UpdateState {
        val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }
    data class Verifying(val release: Release) : UpdateState
    data class ReadyToInstall(val release: Release) : UpdateState

    /** Handed to Android's installer, waiting for the user. */
    data class Installing(val release: Release) : UpdateState

    /** [release] is what was being updated to, when the failure came after a check. */
    data class Failed(val problem: UpdateProblem, val release: Release?) : UpdateState
}

/** What About shows besides [UpdateState]: the settings and when GitHub last answered. */
data class UpdateInfo(
    val availability: UpdateAvailability,
    val enabled: Boolean,
    val checkedAt: Long,
    val installedVersion: String,
    /** Beta versions (GitHub pre-releases) are offered too ([dev.locus.core.model.AppSettings.updateBeta]). */
    val beta: Boolean = false,
)

/**
 * The opt-in update check (docs/design/updates.md): asks GitHub for the latest release, compares it with
 * this build, and on the user's say-so downloads the APK for this phone, checks its SHA-256 and signer,
 * and hands it to Android's installer. Nothing here runs unless the user turned the check on or tapped
 * Check now.
 */
class Updates(
    private val settings: SettingsRepository,
    private val source: ReleaseSource,
    private val cache: UpdateCacheStore,
    private val work: UpdateWork,
    private val platform: UpdatePlatform,
    private val build: UpdateBuild,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val packageName: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val availability: UpdateAvailability by lazy {
        when {
            !build.enabled -> UpdateAvailability.DisabledInBuild
            build.debug -> UpdateAvailability.DebugBuild
            else -> platform.storeInstaller()?.let(UpdateAvailability::FromStore) ?: UpdateAvailability.Available
        }
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val info: Flow<UpdateInfo> = combine(settings.settings, cache.data) { s, c ->
        UpdateInfo(availability, s.updateCheck && availability.canSchedule, c.checkedAt, build.versionName, beta = s.updateBeta)
    }

    /** Whether the setting is on, for the onboarding toggle. */
    val enabled: Flow<Boolean> = settings.settings.map { it.updateCheck && availability.canSchedule }

    private val lock = Mutex()
    private var download: Job? = null

    /** At start: the schedule follows the setting, and the last answer is shown again without asking GitHub. */
    fun start() {
        scope.launch(io) {
            runCatching { syncSchedule() }
            runCatching { restore() }
        }
    }

    internal suspend fun syncSchedule() {
        if (availability.canSchedule && settings.current().updateCheck) work.schedule() else work.cancel()
    }

    internal suspend fun restore() {
        val c = cache.current()
        val release = c.release
        if (_state.value != UpdateState.Idle) return
        _state.value = when {
            release == null && c.checkedAt == 0L -> UpdateState.Idle
            release != null && isUpdate(release, settings.current().updateBeta) -> UpdateState.Available(release, skipped = c.skippedVersion == release.version)
            else -> UpdateState.UpToDate(release?.version)
        }
        // An APK left from an update that's now installed, or abandoned, isn't kept.
        if (_state.value !is UpdateState.Available) clearDownloads()
    }

    /** Turns the daily check on or off (onboarding, Settings). */
    suspend fun setEnabled(on: Boolean) {
        if (on && !availability.canSchedule) return
        settings.setUpdateCheck(on)
        if (on) work.schedule() else work.cancel()
    }

    /**
     * Turns beta versions on or off. The release in hand from the other channel is dropped (unless it's
     * downloading or installing), so the next check asks again; nothing is fetched here. Turning beta off
     * never offers a downgrade: the stable release is offered only once it's newer than what's installed.
     */
    suspend fun setBeta(on: Boolean) = lock.withLock {
        if (settings.current().updateBeta == on) return@withLock
        settings.setUpdateBeta(on)
        cache.update { it.copy(etag = null, release = null, beta = on) }
        val s = _state.value
        if (s is UpdateState.Available || s is UpdateState.UpToDate || (s is UpdateState.Failed && s.release != null && !hasDownload(s.release))) {
            platform.clearNotification()
            _state.value = UpdateState.Idle
        }
    }

    /**
     * Asks GitHub now. [manual] is the user's Check now, which works with the setting off; the daily
     * worker passes false. Returns the new state.
     */
    suspend fun check(manual: Boolean): UpdateState = lock.withLock {
        if (!availability.canCheck) return _state.value
        if (!manual && !(availability.canSchedule && settings.current().updateCheck)) return _state.value
        // Busy with a download or install: the release in hand stays.
        if (_state.value.let { it is UpdateState.Downloading || it is UpdateState.Verifying || it is UpdateState.Installing }) return _state.value
        val before = cache.current()
        if (before.rateLimitedUntil > now()) return set(UpdateState.Failed(UpdateProblem.RateLimited(before.rateLimitedUntil), null))
        set(UpdateState.Checking)
        val beta = settings.current().updateBeta
        // The ETag is for the address it came from: the stable channel's "latest", or the beta channel's list.
        val outcome = source.latest(before.etag.takeIf { before.release != null && before.beta == beta }, beta)
        val release: Release? = when (outcome) {
            is CheckOutcome.Fresh -> {
                cache.update { it.copy(etag = outcome.etag, release = outcome.release, checkedAt = now(), rateLimitedUntil = 0, beta = beta) }
                outcome.release
            }
            CheckOutcome.NotModified -> {
                cache.update { it.copy(checkedAt = now(), rateLimitedUntil = 0) }
                before.release
            }
            CheckOutcome.NoRelease -> {
                cache.update { it.copy(etag = null, release = null, checkedAt = now(), rateLimitedUntil = 0, beta = beta) }
                return set(UpdateState.UpToDate(null))
            }
            is CheckOutcome.RateLimited -> {
                cache.update { it.copy(rateLimitedUntil = outcome.resetAt) }
                return set(UpdateState.Failed(UpdateProblem.RateLimited(outcome.resetAt), null))
            }
            CheckOutcome.Offline -> return set(UpdateState.Failed(UpdateProblem.NoNetwork, null))
            is CheckOutcome.Failed -> return set(UpdateState.Failed(UpdateProblem.Server(outcome.code), null))
        }
        if (release == null || !isUpdate(release, beta)) {
            platform.clearNotification()
            return set(UpdateState.UpToDate(release?.version))
        }
        val skipped = cache.current().skippedVersion == release.version
        set(UpdateState.Available(release, skipped))
    }

    /**
     * The daily worker: checks, and posts one notification per new version, never for a skipped one.
     */
    suspend fun checkInBackground() {
        val result = check(manual = false)
        if (result is UpdateState.Available && !result.skipped) {
            val version = result.release.version
            var post = false
            cache.update { c -> if (c.notifiedVersion == version) c else c.copy(notifiedVersion = version).also { post = true } }
            if (post) platform.notifyAvailable(version)
        }
    }

    /** "Skip this version": no card and no notification for it. A newer release shows again. */
    suspend fun skip(version: String) {
        cache.update { it.copy(skippedVersion = version) }
        platform.clearNotification()
        (_state.value as? UpdateState.Available)?.takeIf { it.release.version == version }?.let { _state.value = it.copy(skipped = true) }
        (_state.value as? UpdateState.Failed)?.release?.takeIf { it.version == version }?.let { _state.value = UpdateState.Available(it, skipped = true) }
    }

    /** Takes back a skip, so the card offers the update again. */
    suspend fun unskip() {
        cache.update { it.copy(skippedVersion = null) }
        (_state.value as? UpdateState.Available)?.let { _state.value = it.copy(skipped = false) }
    }

    /** Downloads the APK for this phone, then checks its SHA-256 and signer. */
    fun download() {
        val release = releaseInHand() ?: return
        if (availability != UpdateAvailability.Available) return
        if (download?.isActive == true) return
        download = scope.launch(io) { runDownload(release) }
    }

    /** Stops a download and deletes what came so far. */
    fun cancelDownload() {
        val release = releaseInHand()
        download?.cancel()
        download = null
        clearDownloads()
        if (release != null) _state.value = UpdateState.Available(release, skipped = false)
    }

    internal suspend fun runDownload(release: Release) {
        val asset = ReleaseFiles.apkFor(release, platform.supportedAbis)
            ?: return fail(UpdateProblem.NoApkForDevice(platform.supportedAbis), release)
        _state.value = UpdateState.Downloading(release, 0, asset.size)
        val sums = release.assets.firstOrNull { it.name == ReleaseFiles.CHECKSUMS }?.let { sumsAsset ->
            source.text(sumsAsset.url)?.let(ReleaseFiles::parseChecksums)
                // The release says it has checksums but they can't be had: don't fall back to less.
                ?: return fail(UpdateProblem.NoNetwork, release)
        }
        val expected = when (val e = ReleaseFiles.expected(asset, sums)) {
            is ReleaseFiles.Expected.Sha256 -> e.hex
            else -> return fail(UpdateProblem.NoChecksum, release)
        }
        val dir = platform.downloadDir.apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "${asset.name}.part")
        val actual = try {
            source.download(asset.url, part) { bytes, total -> _state.value = UpdateState.Downloading(release, bytes, if (total > 0) total else asset.size) }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: DownloadException) {
            part.delete()
            return fail(UpdateProblem.Server(e.code), release)
        } catch (e: IOException) {
            part.delete()
            return fail(if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true) UpdateProblem.Storage else UpdateProblem.NoNetwork, release)
        } catch (e: Exception) {
            part.delete()
            return fail(if (e.isNetwork()) UpdateProblem.NoNetwork else UpdateProblem.Server(0), release)
        }
        _state.value = UpdateState.Verifying(release)
        if (!ReleaseFiles.matches(actual, expected)) {
            part.delete()
            return fail(UpdateProblem.ChecksumMismatch, release)
        }
        val apk = File(dir, asset.name)
        if (!part.renameTo(apk)) {
            part.delete()
            return fail(UpdateProblem.Storage, release)
        }
        when (ApkCheck.verdict(platform.installed(), platform.archive(apk))) {
            ApkCheck.Verdict.OK -> _state.value = UpdateState.ReadyToInstall(release)
            ApkCheck.Verdict.DIFFERENT_KEY -> {
                apk.delete()
                fail(UpdateProblem.SignatureMismatch, release)
            }
            else -> {
                apk.delete()
                fail(UpdateProblem.BadApk, release)
            }
        }
    }

    /** Hands the verified APK to Android's installer; asks for "Install unknown apps" first if it's off. */
    fun install() {
        val release = releaseInHand() ?: return
        val apk = downloadedApk(release) ?: return run { _state.value = UpdateState.Available(release, skipped = false) }
        if (!platform.canInstall()) return fail(UpdateProblem.InstallNotAllowed, release)
        _state.value = UpdateState.Installing(release)
        scope.launch(io) {
            try {
                platform.install(apk, packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                fail(UpdateProblem.InstallNotAllowed, release)
            } catch (e: IOException) {
                fail(UpdateProblem.Storage, release)
            } catch (e: Exception) {
                fail(UpdateProblem.InstallFailed(e.message), release)
            }
        }
    }

    /** Back from Android's "Install unknown apps" page: ready again if it's now allowed. */
    fun onReturnFromSettings() {
        val s = _state.value as? UpdateState.Failed ?: return
        if (s.problem != UpdateProblem.InstallNotAllowed || s.release == null) return
        if (platform.canInstall() && downloadedApk(s.release) != null) _state.value = UpdateState.ReadyToInstall(s.release)
    }

    /** Android's installer answered ([PackageInstaller.EXTRA_STATUS]). PENDING_USER_ACTION is handled by the receiver. */
    fun onInstallResult(status: Int, message: String?) {
        val release = releaseInHand() ?: return
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                clearDownloads()
                _state.value = UpdateState.UpToDate(release.version)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> fail(UpdateProblem.InstallCancelled, release)
            // "conflicts with another package already installed": in practice a different signing key.
            PackageInstaller.STATUS_FAILURE_CONFLICT -> fail(UpdateProblem.SignatureMismatch, release)
            PackageInstaller.STATUS_FAILURE_STORAGE -> fail(UpdateProblem.Storage, release)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> fail(UpdateProblem.InstallNotAllowed, release)
            else -> fail(UpdateProblem.InstallFailed(message), release)
        }
    }

    /** Back to the release's card after a failure (Try again, Dismiss). */
    fun dismissProblem() {
        val s = _state.value as? UpdateState.Failed ?: return
        _state.value = s.release?.let { r ->
            when {
                downloadedApk(r) != null -> UpdateState.ReadyToInstall(r)
                // It was offered on the channel it came from (a pre-release only with beta on).
                isUpdate(r, beta = true) -> UpdateState.Available(r, skipped = false)
                else -> UpdateState.UpToDate(r.version)
            }
        } ?: UpdateState.Idle
        if (_state.value == UpdateState.Idle) scope.launch(io) { restore() }
    }

    /**
     * Try again after a failure: the install when a verified APK is waiting, the download when a
     * release is in hand, else the check.
     */
    suspend fun retry() {
        val s = _state.value as? UpdateState.Failed ?: return
        val release = s.release
        when {
            release == null -> check(manual = true)
            downloadedApk(release) != null -> install()
            else -> download()
        }
    }

    /** Whether there's a verified APK for [release] waiting. */
    fun hasDownload(release: Release): Boolean = downloadedApk(release) != null

    private fun downloadedApk(release: Release): File? {
        val asset = ReleaseFiles.apkFor(release, platform.supportedAbis) ?: return null
        return File(platform.downloadDir, asset.name).takeIf { it.isFile }
    }

    private fun releaseInHand(): Release? = when (val s = _state.value) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Verifying -> s.release
        is UpdateState.ReadyToInstall -> s.release
        is UpdateState.Installing -> s.release
        is UpdateState.Failed -> s.release
        else -> null
    }

    private fun isUpdate(release: Release, beta: Boolean): Boolean = ReleaseChannel.offers(release, build.versionName, beta)

    private fun clearDownloads() {
        platform.downloadDir.listFiles()?.forEach { it.delete() }
    }

    private fun set(state: UpdateState): UpdateState {
        _state.value = state
        return state
    }

    private fun fail(problem: UpdateProblem, release: Release?) {
        _state.value = UpdateState.Failed(problem, release)
    }
}
