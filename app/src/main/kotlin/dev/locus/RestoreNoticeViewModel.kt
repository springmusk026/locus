package dev.locus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.backup.BackupManager
import dev.locus.core.designsystem.UiText
import dev.locus.core.model.SettingsDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tells the user, once, that a restore didn't finish at startup and was rolled back, or that the
 * restored scheduled tasks couldn't be set up again (docs/design/backup.md). Each record is kept
 * until they dismiss the message.
 */
@HiltViewModel
class RestoreNoticeViewModel @Inject constructor(private val backup: BackupManager) : ViewModel() {
    /** The paragraphs to show, or null when there's nothing to say. */
    private val _message = MutableStateFlow<List<UiText>?>(null)
    val message: StateFlow<List<UiText>?> = _message.asStateFlow()

    private var restoreMessage: UiText? = null
    private var tasksMessage: UiText? = null

    /** The page the restore message names (Backup & restore after a rollback), for its link. */
    var destination: SettingsDestination? = null
        private set

    /** Whether the message includes a restore that didn't finish (else it's only about scheduled tasks); for the title. */
    val restoreUnfinished: Boolean get() = restoreMessage != null

    init {
        viewModelScope.launch {
            val failure = runCatching { backup.restoreFailure() }.getOrNull() ?: return@launch
            // Rolled back is true for the vault too: it's swapped in with everything else, and rolled back with it.
            restoreMessage = UiText.Res(if (failure.rolledBack) R.string.app_restore_rolled_back else R.string.app_restore_retrying)
            destination = SettingsDestination.Backup.takeIf { failure.rolledBack }
            show()
        }
        viewModelScope.launch {
            runCatching {
                backup.rescheduleFailures().collect { reason ->
                    if (reason == null) return@collect
                    tasksMessage = UiText.Res(R.string.app_restore_tasks_failed, reason)
                    show()
                }
            }
        }
    }

    private fun show() {
        _message.update { listOfNotNull(restoreMessage, tasksMessage).ifEmpty { null } }
    }

    fun dismiss() {
        _message.value = null
        val hadRestore = restoreMessage != null
        val hadTasks = tasksMessage != null
        restoreMessage = null
        tasksMessage = null
        viewModelScope.launch {
            if (hadRestore) runCatching { backup.dismissRestoreFailure() }
            if (hadTasks) runCatching { backup.dismissRescheduleFailure() }
        }
    }
}
