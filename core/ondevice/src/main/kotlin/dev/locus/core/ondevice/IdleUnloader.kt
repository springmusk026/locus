package dev.locus.core.ondevice

import dev.locus.core.model.DeviceState
import dev.locus.core.model.PowerPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Unloads an on-device model that has sat idle for [PowerPolicy.idleUnloadMs]: 10 minutes normally,
 * 2 in battery saver or while hot, 20 seconds once Android says memory is short. The idle time counts
 * from when the model last finished work, so a change of conditions shortens the wait without
 * restarting it. Before this, a 2.6–3.7 GB model stayed loaded until the process died.
 *
 * [now] is a monotonic clock in milliseconds (virtual time in tests); [unload] may refuse (returns
 * false) when a reply started meanwhile, and the next idle period starts the wait again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleUnloader(
    scope: CoroutineScope,
    idle: Flow<Boolean>,
    conditions: Flow<DeviceState>,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    unload: suspend () -> Boolean,
) {
    init {
        scope.launch {
            val idleSince = idle.distinctUntilChanged().map { if (it) now() else null }
            val after = conditions.map(PowerPolicy::idleUnloadMs).distinctUntilChanged()
            combine(idleSince, after) { since, wait -> since?.let { it + wait } }
                .distinctUntilChanged()
                .collectLatest { deadline ->
                    if (deadline == null) return@collectLatest
                    delay((deadline - now()).coerceAtLeast(0))
                    unload()
                }
        }
    }
}

/** What an on-device reply stopped for critical heat ends with, so the user (and the model next turn) see why. */
object HeatStop {
    fun note(droppedCalls: Int = 0): String = buildString {
        append("\n\n[Stopped early: the phone reached a critical temperature, so Locus stopped the on-device model to let it cool down. ")
        append("What it wrote so far is kept.")
        if (droppedCalls > 0) append(if (droppedCalls == 1) " A tool call it had started was not run." else " $droppedCalls tool calls it had started were not run.")
        append(" Send a message to continue once the phone has cooled.]")
    }
}
