package dev.locus.feature.chat

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.lock.AppLock
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.ui.rememberUnlock
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Locking and unlocking chats, for home's menu and the chat's top bar (docs/design/app-lock.md). */
@HiltViewModel
class ChatLockViewModel @Inject constructor(val appLock: AppLock) : ViewModel() {
    val status = appLock.status

    fun setLocked(conversationId: String, locked: Boolean) {
        viewModelScope.launch { appLock.setChatLocked(conversationId, locked) }
    }
}

/** What a screen does to lock or unlock a chat; [toggle] asks for authentication first when the chat is still hidden. */
@Stable
internal class ChatLocker(val toggle: (conversationId: String, locked: Boolean, redacted: Boolean) -> Unit)

/**
 * Locks a chat at once (it asks the next time it's opened); unlocking a chat whose name is still
 * hidden asks for authentication first, which also opens this session. Each change is said in a
 * short message.
 */
@Composable
internal fun rememberChatLocker(viewModel: ChatLockViewModel): ChatLocker {
    val feedback = Locus.feedback
    val lockedMessage = stringResource(R.string.chat_lock_locked)
    val unlockedMessage = stringResource(R.string.chat_lock_unlocked)
    var pending by remember { mutableStateOf<String?>(null) }
    val unlock = rememberUnlock(viewModel.appLock, stringResource(R.string.chat_lock_prompt_remove)) {
        pending?.let { id ->
            viewModel.setLocked(id, false)
            feedback.show(unlockedMessage)
        }
        pending = null
    }
    return remember(viewModel, unlock) {
        ChatLocker { id, locked, redacted ->
            when {
                locked -> {
                    viewModel.setLocked(id, true)
                    feedback.show(lockedMessage)
                }
                redacted -> {
                    pending = id
                    unlock.launcher.launch()
                }
                else -> {
                    viewModel.setLocked(id, false)
                    feedback.show(unlockedMessage)
                }
            }
        }
    }
}

/** The chat's top-bar button: a closed padlock, in the accent while the chat is locked. */
@Composable
internal fun ChatLockButton(locked: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            LocusIcons.Lock,
            contentDescription = stringResource(if (locked) R.string.chat_lock_remove else R.string.chat_lock_add),
            tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
