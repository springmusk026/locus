package dev.locus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.backup.BackupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tells the user, once, that a restore didn't finish at startup and was rolled back
 * (docs/design/backup.md). The record is kept until they dismiss the message.
 */
@HiltViewModel
class RestoreNoticeViewModel @Inject constructor(private val backup: BackupManager) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            val failure = runCatching { backup.restoreFailure() }.getOrNull() ?: return@launch
            _message.value = if (failure.rolledBack) {
                "Restoring the backup didn't finish; your data is as it was before. Try again from Settings → Backup & restore."
            } else {
                "Restoring the backup didn't finish, and some of your data couldn't be put back yet. " +
                    "Locus tries again the next time it starts. Restart Locus before restoring again."
            }
        }
    }

    fun dismiss() {
        _message.value = null
        viewModelScope.launch { runCatching { backup.dismissRestoreFailure() } }
    }
}
