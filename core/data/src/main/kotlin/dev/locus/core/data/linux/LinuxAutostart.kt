package dev.locus.core.data.linux

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the services the user set to "Start when Locus opens" (docs/design/linux-health.md,
 * "Services"), once per app process: the first time Locus is on screen with Linux ready. Services
 * end with Locus's process, so after the app or the phone restarts this is what brings them back.
 *
 * It waits for Locus to be opened rather than starting them when the process starts for something in
 * the background (a scheduled task, the boot receiver): Android freezes or kills an app's extra
 * processes in the background, so a server started then wouldn't stay up.
 */
@Singleton
class LinuxAutostart internal constructor(
    private val linux: LinuxEnvironment,
    private val scope: CoroutineScope,
    /** True while Locus has a visible activity; replaced in tests. */
    private val visible: () -> Flow<Boolean>,
) {
    @Inject constructor(linux: LinuxEnvironment, @ApplicationScope scope: CoroutineScope) : this(
        linux,
        scope,
        { ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) } },
    )

    private val started = AtomicBoolean(false)

    /** Once per process, from the Application. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            visible().first { it }
            linux.status.first { it == LinuxStatus.Ready }
            val failed = linux.startAutostartServices()
            // The health page shows each one as set to start but not running; this is for the log.
            failed.forEach { (name, why) -> runCatching { Log.w("Locus", "Service $name didn't start automatically: $why") } }
        }
    }
}
