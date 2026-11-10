package dev.locus.feature.settings.ondevice

import android.content.res.Resources
import androidx.annotation.StringRes
import dev.locus.core.designsystem.R as DesignR
import dev.locus.core.ondevice.Accelerator
import dev.locus.core.ondevice.BenchmarkResult
import dev.locus.core.ondevice.EngineStatus
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.OnDeviceFormat
import dev.locus.core.ondevice.TurnSpeed
import dev.locus.core.ondevice.gguf.HfHub
import dev.locus.feature.settings.R

/*
 * The engine's state and speeds as the on-device model's page shows them, in the app's language.
 * [OnDeviceFormat] (core/ondevice) is the English these follow word for word; OnDeviceTextTest checks it.
 */

/** "Popular", "Trending": the search's sort choices. */
@get:StringRes
internal val HfHub.Sort.labelRes: Int
    get() = when (this) {
        HfHub.Sort.Downloads -> R.string.settings_ondevice_sort_popular
        HfHub.Sort.Trending -> R.string.settings_ondevice_sort_trending
    }

/** "18 tok/s", "≈18 tok/s", "0.8 tok/s"; whole numbers from 10 up. */
internal fun rateText(resources: Resources, perSecond: Double, estimate: Boolean = false): String {
    val n = if (perSecond >= 10) Math.round(perSecond).toString() else "%.1f".format(perSecond)
    return resources.getString(if (estimate) R.string.settings_ondevice_rate_estimate else R.string.settings_ondevice_rate, n)
}

/** "4.2 s", "0.35 s", "12 s". */
internal fun secondsText(resources: Resources, value: Double): String = resources.getString(
    R.string.settings_ondevice_seconds,
    when {
        value >= 10 -> Math.round(value).toString()
        value >= 1 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    },
)

/** "Running on GPU · 18 tok/s", "Running on CPU (GPU failed: …) · ≈6 tok/s", "Loading…", "Not loaded". */
internal fun backendText(resources: Resources, status: EngineStatus, modelId: String): String {
    if (status.modelId != modelId) return resources.getString(R.string.settings_ondevice_engine_not_loaded)
    if (status.loading) return resources.getString(DesignR.string.common_loading)
    val backend = status.backend
        ?: return status.lastError?.let { resources.getString(R.string.settings_ondevice_engine_load_failed, it.take(80)) }
            ?: resources.getString(R.string.settings_ondevice_engine_not_loaded)
    val gpuFailure = status.gpuFailure.takeIf { backend == Accelerator.CPU }
    val running = if (gpuFailure != null) resources.getString(R.string.settings_ondevice_engine_running_gpu_failed, backend.name, gpuFailure)
    else resources.getString(R.string.settings_ondevice_engine_running, backend.name)
    val rate = status.lastTurn?.let { turn -> turn.decodePerSecond?.let { rateText(resources, it, estimate = !turn.measured) } }
    return listOfNotNull(running, rate).joinToString(" · ")
}

/** "GPU · ≈18 tok/s · prefill ≈240 tok/s". Null when nothing is known. */
internal fun turnText(resources: Resources, speed: TurnSpeed): String? {
    val decode = speed.decodePerSecond ?: return null
    val estimate = !speed.measured
    return listOfNotNull(
        speed.backend.name,
        rateText(resources, decode, estimate),
        speed.prefillPerSecond?.let { resources.getString(R.string.settings_ondevice_prefill, rateText(resources, it, estimate)) },
    ).joinToString(" · ")
}

/** "Loaded in 4.2 s · ≈1.3 GB of memory". */
internal fun loadedText(resources: Resources, status: EngineStatus): String? {
    if (!status.loaded) return null
    return listOfNotNull(
        status.loadMillis?.let { resources.getString(R.string.settings_ondevice_loaded_in, secondsText(resources, it / 1000.0)) },
        status.memoryBytes?.let { resources.getString(R.string.settings_ondevice_memory, gb(it / MemoryGate.GIB)) },
    ).joinToString(" · ").ifEmpty { null }
}

/** The benchmark result in three short lines. */
internal fun benchmarkText(resources: Resources, result: BenchmarkResult): List<String> = listOf(
    result.gpuFailure?.let { resources.getString(R.string.settings_ondevice_benchmark_ran_gpu_failed, result.backend.name, it) }
        ?: resources.getString(R.string.settings_ondevice_benchmark_ran, result.backend.name),
    resources.getString(
        R.string.settings_ondevice_benchmark_load,
        secondsText(resources, result.loadSeconds),
        rateText(resources, result.prefillPerSecond),
        resources.getQuantityString(R.plurals.settings_ondevice_benchmark_tokens, result.prefillTokens, result.prefillTokens),
    ),
    resources.getString(
        R.string.settings_ondevice_benchmark_decode,
        rateText(resources, result.decodePerSecond),
        resources.getQuantityString(R.plurals.settings_ondevice_benchmark_tokens, result.decodeTokens, result.decodeTokens),
    ),
)

/** [MemoryGate.gb] in the default locale: "7.5", "12". */
private fun gb(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else "%.1f".format(rounded)
}
