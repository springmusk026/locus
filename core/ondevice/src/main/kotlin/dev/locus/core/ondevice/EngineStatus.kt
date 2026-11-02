package dev.locus.core.ondevice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The result of the optional local benchmark. Kept in memory on the phone; never uploaded. */
data class BenchmarkResult(
    val modelId: String,
    val backend: Accelerator,
    val gpuFailure: String?,
    val loadSeconds: Double,
    val prefillTokens: Int,
    val prefillPerSecond: Double,
    val decodeTokens: Int,
    val decodePerSecond: Double,
)

/** What the on-device engine is doing, as Settings and the chat show it. */
data class EngineStatus(
    /** The model whose engine is in memory, or being loaded. */
    val modelId: String? = null,
    val loading: Boolean = false,
    /** Set only once an engine was really created; null while unloaded or loading. */
    val backend: Accelerator? = null,
    /** Why GPU didn't work, when the engine fell back to CPU. */
    val gpuFailure: String? = null,
    val loadMillis: Long? = null,
    /** Process memory added by loading, approximate; null when it couldn't be measured. */
    val memoryBytes: Long? = null,
    /** A reply is being generated right now. */
    val busy: Boolean = false,
    val lastTurn: TurnSpeed? = null,
    val lastError: String? = null,
    val benchmarking: Boolean = false,
    val benchmark: BenchmarkResult? = null,
    val benchmarkError: String? = null,
) {
    val loaded: Boolean get() = backend != null && modelId != null && !loading
}

/**
 * Keeps [EngineStatus] honest: the backend is only ever set from a [LoadedBackend], i.e. from the
 * backend the engine was actually created with. Pure state, so it is unit-tested without LiteRT-LM.
 */
class EngineStatusTracker {
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    /** Recent replies' speeds by their trimmed text, so the chat can show them under the reply. */
    private val _recent = MutableStateFlow<Map<String, TurnSpeed>>(emptyMap())
    val recentTurns: StateFlow<Map<String, TurnSpeed>> = _recent.asStateFlow()

    fun loading(modelId: String) = _status.update {
        it.copy(modelId = modelId, loading = true, backend = null, gpuFailure = null, loadMillis = null, memoryBytes = null, lastTurn = null, lastError = null)
    }

    fun loaded(modelId: String, result: LoadedBackend<*>, loadMillis: Long, memoryBytes: Long?) = _status.update {
        it.copy(
            modelId = modelId, loading = false, backend = result.backend, gpuFailure = result.gpuFailure,
            loadMillis = loadMillis, memoryBytes = memoryBytes?.takeIf { b -> b > 0 }, lastError = null,
        )
    }

    fun loadFailed(modelId: String, message: String) = _status.update {
        it.copy(modelId = modelId, loading = false, backend = null, gpuFailure = null, loadMillis = null, memoryBytes = null, lastError = message)
    }

    fun unloaded() = _status.update {
        it.copy(modelId = null, loading = false, backend = null, gpuFailure = null, loadMillis = null, memoryBytes = null, busy = false, lastTurn = null)
    }

    fun busy(value: Boolean) = _status.update { it.copy(busy = value) }

    fun turn(reply: String, speed: TurnSpeed) {
        _status.update { it.copy(lastTurn = speed) }
        val key = reply.trim()
        if (key.isEmpty()) return
        _recent.update { old -> (old - key + (key to speed)).entries.toList().takeLast(MAX_RECENT).associate { e -> e.key to e.value } }
    }

    fun benchmarkStarted() = _status.update { it.copy(benchmarking = true, benchmarkError = null) }
    fun benchmarkDone(result: BenchmarkResult) = _status.update { it.copy(benchmarking = false, benchmark = result, benchmarkError = null) }
    fun benchmarkFailed(message: String) = _status.update { it.copy(benchmarking = false, benchmarkError = message) }

    companion object {
        const val MAX_RECENT = 32
    }
}
