package dev.locus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.backup.BackupManager
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
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var restoreMessage: String? = null
    private var tasksMessage: String? = null

    /** Whether the message includes a restore that didn't finish (else it's only about scheduled tasks); for the title. */
    val restoreUnfinished: Boolean get() = restoreMessage != null

    init {
        viewModelScope.launch {
            val failure = runCatching { backup.restoreFailure() }.getOrNull() ?: return@launch
            restoreMessage = if (failure.rolledBack) {
                // True for the vault too: it's swapped in with everything else, and rolled back with it.
                "Restoring the backup didn't finish; your data and saved keys are as they were before. " +
                    "Try again from Settings → Backup & restore."
            } else {
                "Restoring the backup didn't finish, and some of your data couldn't be put back yet. " +
                    "Locus tries again the next time it starts. Restart Locus before restoring again."
            }
            show()
        }
        viewModelScope.launch {
            runCatching {
                backup.rescheduleFailures().collect { reason ->
                    if (reason == null) return@collect
                    tasksMessage = "Your scheduled tasks couldn't be set up again after the restore ($reason). " +
                        "They won't run until they are; Locus tries again the next time it starts."
                    show()
                }
            }
        }
    }

    private fun show() {
        _message.update { listOfNotNull(restoreMessage, tasksMessage).joinToString("\n\n").ifEmpty { null } }
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
