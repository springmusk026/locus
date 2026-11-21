package dev.locus.core.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.data.lock.AppLock
import dev.locus.core.model.DeviceAuthOutcome
import kotlinx.coroutines.flow.first

/** An unlock in progress: the prompt's launcher and how the last attempt ended. */
@Stable
class UnlockState internal constructor(val launcher: DeviceAuthLauncher, private val last: MutableState<DeviceAuthOutcome?>) {
    val outcome: DeviceAuthOutcome? get() = last.value
}

/**
 * Asks for authentication on behalf of [appLock]: a success unlocks the session (Locus and every
 * locked chat) and then runs [onSucceeded]. The trip to Android's prompt doesn't count as leaving Locus.
 */
@Composable
fun rememberUnlock(appLock: AppLock, prompt: String, onSucceeded: () -> Unit = {}): UnlockState {
    val last = remember { mutableStateOf<DeviceAuthOutcome?>(null) }
    val launcher = rememberDeviceAuth(prompt, beforeLaunch = appLock::expectReturn) { result ->
        last.value = result
        when (result) {
            DeviceAuthOutcome.SUCCEEDED -> {
                appLock.unlocked()
                onSucceeded()
            }
            // The screen lock was removed meanwhile: the lock is suspended, and Settings says so.
            DeviceAuthOutcome.NO_CREDENTIAL -> {
                appLock.returned()
                appLock.refreshDeviceSecure()
            }
            else -> appLock.returned()
        }
    }
    return remember(launcher) { UnlockState(launcher, last) }
}

/**
 * Locus's content, or its lock screen instead (docs/design/app-lock.md). While locked, [content] isn't
 * composed at all; its saved state (the back stack, scroll positions, drafts) is kept by a
 * [rememberSaveableStateHolder] and comes back on unlock. Until the lock's settings are read it shows
 * only the background, so nothing flashes before the lock screen. [onBack] adds a back button to the
 * lock screen (the automation request's sheet declines with it).
 */
@Composable
fun AppLockGate(appLock: AppLock, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val status = appLock.status.collectAsStateWithLifecycle().value
    val holder = rememberSaveableStateHolder()
    when {
        status == null -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        status.appLocked -> {
            val unlock = rememberUnlock(appLock, stringResource(R.string.ui_lock_prompt_app))
            AutoPrompt(status.sessionKey, unlock)
            LockScreen(LockScreenKind.App, onUnlock = unlock.launcher::launch, busy = unlock.launcher.busy, outcome = unlock.outcome, onBack = onBack)
        }
        else -> holder.SaveableStateProvider("locus") { content() }
    }
}

/**
 * A locked chat's screen, or the lock screen in front of it while this session hasn't been unlocked.
 * A chat that was open when the user locked it stays open until the session locks: the screen
 * remembers the session it was let into ([dev.locus.core.model.AppLockStatus.sessionKey]).
 */
@Composable
fun ChatLockGate(appLock: AppLock, conversationId: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val status = appLock.status.collectAsStateWithLifecycle().value ?: return
    var letIn by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    val open = !status.chatNeedsAuth(conversationId) || letIn == status.sessionKey
    if (open) {
        SideEffect { letIn = status.sessionKey }
        content()
    } else {
        val unlock = rememberUnlock(appLock, stringResource(R.string.ui_lock_prompt_chat))
        AutoPrompt(status.sessionKey + ":" + conversationId, unlock)
        LockScreen(LockScreenKind.Chat, onUnlock = unlock.launcher::launch, busy = unlock.launcher.busy, outcome = unlock.outcome, onBack = onBack)
    }
}

/**
 * Opens the prompt by itself once per [key] (a session, or a chat in a session), when the screen is
 * resumed: not again after a cancel, a rotation or a trip away, where the Unlock button is the way.
 */
@Composable
private fun AutoPrompt(key: String, unlock: UnlockState) {
    var prompted by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key) {
        if (prompted == key) return@LaunchedEffect
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        prompted = key
        unlock.launcher.launch()
    }
}
