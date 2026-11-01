package dev.locus.core.data.device

import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.llm.DeviceConditions
import dev.locus.core.model.PowerPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * At critical heat, the Linux services the agent left running, to suggest stopping them. Nothing is
 * ever stopped here on its own: the user taps Stop services, or dismisses the suggestion until the
 * phone has left critical. See docs/design/battery-thermal.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LinuxHeatAdvice @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val linux: LinuxEnvironment,
    conditions: DeviceConditions,
) {
    private val _running = MutableStateFlow<List<String>>(emptyList())

    /** Running services to suggest stopping; empty when the phone isn't critical, none run, or the user said not now. */
    val suggestion: StateFlow<List<String>> = _running.asStateFlow()

    @Volatile private var dismissed = false

    init {
        scope.launch(Dispatchers.Default) {
            conditions.state.map { it.critical }.distinctUntilChanged().collectLatest { critical ->
                if (!critical) {
                    dismissed = false
                    _running.value = emptyList()
                    return@collectLatest
                }
                // Services start and stop without a signal; look again every few seconds while it's critical.
                while (true) {
                    val names = runCatching { linux.serviceStatuses().filter { it.running }.map { it.name } }.getOrDefault(emptyList())
                    _running.value = if (dismissed || !PowerPolicy.suggestStoppingServices(conditions.state.value, names.size)) emptyList() else names
                    delay(POLL_MS)
                }
            }
        }
    }

    /** The user's choice: stops the suggested services (the agent can start them again later). */
    fun stopAll() {
        val names = _running.value
        _running.value = emptyList()
        scope.launch(Dispatchers.Default) { names.forEach { runCatching { linux.stopService(it) } } }
    }

    /** Not now: hidden until the phone has left critical and reached it again. */
    fun dismiss() {
        dismissed = true
        _running.value = emptyList()
    }

    companion object {
        private const val POLL_MS = 10_000L

        /** The suggestion's text: which services, and that stopping them is the user's call and can be undone. */
        fun message(names: List<String>): String {
            val list = names.take(3).joinToString(", ") + if (names.size > 3) " and ${names.size - 3} more" else ""
            val what = if (names.size == 1) "a Linux service is running ($list)" else "${names.size} Linux services are running ($list)"
            return "The phone is very hot and $what. Stopping them would help it cool down; the agent can start them again later."
        }
    }
}
