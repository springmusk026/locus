package dev.locus.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.backup.BackupError
import dev.locus.core.data.backup.BackupEstimate
import dev.locus.core.data.backup.BackupException
import dev.locus.core.data.backup.BackupManager
import dev.locus.core.data.backup.BackupManifest
import dev.locus.core.data.backup.BackupOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import android.content.res.Resources
import dev.locus.core.designsystem.UiText
import dev.locus.core.model.SettingsDestination
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Long work in progress: what it is, and bytes done of the total (total 0 while unknown). */
data class BackupProgress(val label: UiText, val done: Long = 0, val total: Long = 0)

/** A backup's outcome: what to say, whether it failed, and the Settings page that helps, if one does. */
data class BackupResult(val text: UiText, val failed: Boolean, val link: SettingsDestination? = null)

/** Where a restore is. Each step after [Idle] shows in one sheet. */
sealed interface RestoreStep {
    data object Idle : RestoreStep
    data class Password(
        val uri: Uri,
        val error: UiText? = null,
        /** The file can't be a backup at all; there's no point asking for a password. */
        val unreadable: Boolean = false,
        /** The phone hasn't room to unpack it: how much it needs. The error then says it in words. */
        val noSpace: SpaceNeeded? = null,
    ) : RestoreStep
    data class Verifying(val progress: BackupProgress) : RestoreStep
    data class Preview(
        val manifest: BackupManifest,
        val error: UiText? = null,
        /** Not enough room to restore: Restore stays off until there is. */
        val noSpace: SpaceNeeded? = null,
    ) : RestoreStep
    data object Restoring : RestoreStep
}

/** A restore that doesn't fit: the room it needs and what's free, in bytes. */
data class SpaceNeeded(val neededBytes: Long, val freeBytes: Long) {
    /** How much to free before it fits. */
    val shortBytes: Long get() = (neededBytes - freeBytes).coerceAtLeast(0)
}

/** What the restore sheet says about [need]: how much it needs, what's free, and how much to free. */
internal fun noSpaceText(res: Resources, need: SpaceNeeded): String {
    val locale = res.configuration.locales[0]
    return res.getString(
        R.string.settings_backup_no_space_text,
        formatBytes(need.neededBytes, locale),
        formatBytes(need.freeBytes, locale),
        formatBytes(need.shortBytes, locale),
    )
}

private fun Exception.spaceNeeded(): SpaceNeeded? {
    val e = this as? BackupException ?: return null
    if (e.error != BackupError.NoSpace) return null
    return SpaceNeeded(e.neededBytes ?: return null, e.freeBytes ?: 0)
}

data class BackupUiState(
    val estimate: BackupEstimate? = null,
    /** The sizes are still being worked out (they can take a few seconds for a large workspace). */
    val estimating: Boolean = true,
    val includeWorkspace: Boolean = false,
    /** Off by default: keys and tokens only go into a backup when the user turns this on. */
    val includeVault: Boolean = false,
    val backupProgress: BackupProgress? = null,
    /** The last backup's outcome: a message and whether it failed. */
    val result: BackupResult? = null,
    val restore: RestoreStep = RestoreStep.Idle,
    val runActive: Boolean = false,
)

@HiltViewModel
class BackupViewModel @Inject constructor(private val manager: BackupManager) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** Held between the password sheet and the system's save dialog, then wiped. */
    private var pendingPassword: CharArray? = null
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val estimate = runCatching { manager.estimate() }.getOrNull()
            _state.update { it.copy(estimate = estimate, estimating = false) }
        }
        viewModelScope.launch { manager.runActiveFlow.collect { active -> _state.update { it.copy(runActive = active) } } }
    }

    fun setIncludeWorkspace(on: Boolean) = _state.update { it.copy(includeWorkspace = on) }
    fun setIncludeVault(on: Boolean) = _state.update { it.copy(includeVault = on) }
    fun dismissResult() = _state.update { it.copy(result = null) }

    /** The password is set; the screen opens the save dialog next. */
    fun setBackupPassword(password: String) {
        pendingPassword = password.toCharArray()
    }

    fun backup(target: Uri?) {
        val password = pendingPassword ?: return
        pendingPassword = null
        if (target == null) return password.fill('\u0000')
        val options = BackupOptions(includeWorkspace = state.value.includeWorkspace, includeVault = state.value.includeVault)
        job = viewModelScope.launch {
            _state.update { it.copy(backupProgress = BackupProgress(UiText.Res(R.string.settings_backup_progress_backing_up)), result = null) }
            val outcome = try {
                val manifest = manager.backup(target, password, options) { done, total ->
                    _state.update { it.copy(backupProgress = BackupProgress(UiText.Res(R.string.settings_backup_progress_backing_up), done, total)) }
                }
                BackupResult(
                    UiText.Res(
                        if (options.includeVault) R.string.settings_backup_saved_with_keys else R.string.settings_backup_saved,
                        formatBytes(manifest.sections.sumOf { it.bytes }),
                    ),
                    failed = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backupFailure(e)
            } finally {
                password.fill('\u0000')
            }
            _state.update { it.copy(backupProgress = null, result = outcome) }
        }
    }

    fun cancelBackup() {
        job?.cancel()
        _state.update { it.copy(backupProgress = null, result = BackupResult(UiText.Res(R.string.settings_backup_cancelled), failed = true)) }
    }

    /** The user picked a file to restore. */
    fun pickedRestoreFile(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val error = try {
                manager.inspect(uri)
                null
            } catch (e: Exception) {
                message(e)
            }
            _state.update { it.copy(restore = RestoreStep.Password(uri, error, unreadable = error != null)) }
        }
    }

    fun unlock(password: String) {
        val step = state.value.restore as? RestoreStep.Password ?: return
        val chars = password.toCharArray()
        job = viewModelScope.launch {
            _state.update { it.copy(restore = RestoreStep.Verifying(BackupProgress(UiText.Res(R.string.settings_backup_progress_checking)))) }
            val next = try {
                RestoreStep.Preview(
                    manager.verify(step.uri, chars) { done, total ->
                        _state.update { s -> if (s.restore is RestoreStep.Verifying) s.copy(restore = RestoreStep.Verifying(BackupProgress(UiText.Res(R.string.settings_backup_progress_checking), done, total))) else s }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                step.copy(error = message(e), noSpace = e.spaceNeeded())
            } finally {
                chars.fill('\u0000')
            }
            _state.update { it.copy(restore = next) }
        }
    }

    fun confirmRestore() {
        val step = state.value.restore as? RestoreStep.Preview ?: return
        viewModelScope.launch {
            _state.update { it.copy(restore = RestoreStep.Restoring) }
            try {
                manager.restoreAndRestart() // Ends the process on success.
            } catch (e: Exception) {
                _state.update { it.copy(restore = step.copy(error = message(e), noSpace = e.spaceNeeded())) }
            }
        }
    }

    fun closeRestore() {
        job?.cancel()
        manager.discardVerified()
        _state.update { it.copy(restore = RestoreStep.Idle) }
    }

    override fun onCleared() {
        pendingPassword?.fill('\u0000')
        if (state.value.restore !is RestoreStep.Restoring) manager.discardVerified()
    }

    private fun message(e: Exception): UiText = when ((e as? BackupException)?.error) {
        BackupError.NotABackup -> UiText.Res(R.string.settings_backup_error_not_a_backup)
        BackupError.WrongPassword -> UiText.Res(R.string.settings_backup_error_wrong_password)
        BackupError.Damaged -> UiText.Res(R.string.settings_backup_error_damaged)
        BackupError.DatabaseDamaged -> UiText.Res(R.string.settings_backup_error_database_damaged)
        BackupError.NewerFormat -> UiText.Res(R.string.settings_backup_error_newer_format)
        BackupError.NewerSchema -> UiText.Res(R.string.settings_backup_error_newer_schema)
        BackupError.RunActive -> UiText.Res(R.string.settings_backup_error_run_active)
        BackupError.NoSpace -> UiText.Res(R.string.settings_backup_error_no_space)
        // The system's own reason, as it gave it.
        null -> if (e is IOException) {
            UiText.Res(R.string.settings_backup_error_read, e.message?.let(UiText::Plain) ?: UiText.Res(R.string.settings_backup_error_unknown))
        } else {
            UiText.Plain(e.message ?: e.javaClass.simpleName)
        }
    }
}

/**
 * Why a backup failed, in words, with what to do: free space (and where to see what Locus uses), or
 * try again. The file it was writing is incomplete either way.
 */
internal fun backupFailure(e: Throwable): BackupResult {
    // The system's messages are English whatever the language: "No space left on device" (ENOSPC).
    val text = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
    val noSpace = "No space left" in text || "ENOSPC" in text
    return if (noSpace) {
        // "… Settings → About & privacy → Diagnostics → Storage shows what Locus uses": a button there.
        BackupResult(UiText.Res(R.string.settings_backup_failed_no_space), failed = true, link = SettingsDestination.Storage)
    } else {
        BackupResult(UiText.Res(R.string.settings_backup_failed_write), failed = true)
    }
}

/** 1536 → "1.5 KB", 734003200 → "700 MB", with [locale]'s decimal separator. */
internal fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    if (unit == 0 || value >= 100) return "${value.toLong()} ${units[unit]}"
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
        roundingMode = RoundingMode.HALF_UP
        isGroupingUsed = false
    }
    return "${number.format(value)} ${units[unit]}"
}
