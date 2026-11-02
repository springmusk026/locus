package dev.locus.core.linux

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Why a Linux process died when Android killed it. Android 12+ caps the child processes of all apps
 * ("phantom processes") at 32 and kills the children of background apps that use a lot of CPU; both
 * kill with signal 9. A process killed by a signal exits with 128 + the signal number on Android's
 * `java.lang.Process` (as in shells), so 137. A command can also exit 137 on its own, or the kernel's
 * out-of-memory killer can send the same signal; only a kill while Locus is in the background is put
 * down to Android here. Sources and the fix: docs/design/stream-timeouts.md#linux-kill-reason.
 */
object AndroidKill {
    /** 128 + SIGKILL (9). */
    const val SIGKILL_EXIT = 137

    /** agnostic-apollo's Android-Docs page on phantom processes, with the fix for each Android version. */
    const val HELP_URL = "https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md"

    /** What the agent and the user are told: the reason, and the fix to pass on. */
    const val MESSAGE = "Killed by Android (phantom process limit): Android stops the Linux processes of apps in the background " +
        "when there are too many or they use a lot of CPU. To stop that, turn on Settings → System → Developer options → " +
        "\"Disable child process restrictions\" (Android 14 and later), or on Android 12L and 13 run " +
        "`adb shell settings put global settings_enable_monitor_phantom_procs false` from a computer. Details: $HELP_URL"

    /** True when a process that exited with [exitCode] was most likely killed by Android. */
    fun applies(exitCode: Int?, inBackground: Boolean): Boolean = exitCode == SIGKILL_EXIT && inBackground

    /** Whether Locus has no visible activity now; false where that can't be read (unit tests). */
    fun appInBackground(): Boolean = runCatching {
        !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(false)
}
