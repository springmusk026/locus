package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Counts switches of the phone's default network (Wi-Fi to mobile data and back), fed from
 * `ConnectivityManager.registerDefaultNetworkCallback` (core/data `DefaultNetworkWatcher`). After a
 * handover the old socket often dies without a reset, so a stream on it would wait for its idle
 * timeout; [InterruptOnNetworkSwitch] ends it at once instead, and the run's retry goes out on the new
 * network. See docs/design/stream-timeouts.md.
 *
 * Only a different default network counts. Losing the network with nothing to replace it doesn't: a
 * retry then would fail at once and spend the retries, while the stream may still recover if the same
 * network comes back. The idle timeout covers that case.
 */
class NetworkSwitches {
    private val _generation = MutableStateFlow(0L)

    /** Goes up by one on every switch. */
    val generation: StateFlow<Long> = _generation.asStateFlow()

    private var current: Any? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * [network] is now the default network (the callback's `onAvailable`). The first call, which the
     * platform makes at once on registering with the network in use, only records it.
     */
    fun available(network: Any) {
        val switched = synchronized(this) {
            val before = current
            current = network
            before != null && before != network
        }
        if (switched) switched()
    }

    /** Runs [listener] after every switch, e.g. to drop pooled connections that belonged to the old network. */
    fun onSwitch(listener: () -> Unit) {
        listeners += listener
    }

    private fun switched() {
        _generation.value += 1
        listeners.forEach { runCatching { it() } }
    }
}

/** The stream was ended because the phone switched networks; an [java.io.IOException], so the run retries it. */
class NetworkSwitchedException : java.io.IOException("the phone switched networks")

/**
 * [inner], with each stream ended by a [NetworkSwitchedException] as soon as the default network
 * changes after it started ([NetworkSwitches]). Cancelling the collection cancels the HTTP call.
 */
class InterruptOnNetworkSwitch(internal val inner: ChatModel, private val switches: NetworkSwitches) : ChatModel {
    override val format: ApiFormat get() = inner.format

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val started = switches.generation.value
        coroutineScope {
            val watcher = launch {
                switches.generation.first { it != started }
                throw NetworkSwitchedException()
            }
            try {
                inner.stream(request).collect { emit(it) }
            } finally {
                watcher.cancel()
            }
        }
    }
}
