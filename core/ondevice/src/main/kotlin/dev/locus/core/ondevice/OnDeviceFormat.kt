package dev.locus.core.ondevice

import java.util.Locale

/** User-facing text for the engine's state and speeds. Pure, so the wording is unit-tested. */
object OnDeviceFormat {

    /** "18 tok/s", "≈18 tok/s", "0.8 tok/s"; whole numbers from 10 up. */
    fun rate(perSecond: Double, estimate: Boolean = false): String {
        val n = if (perSecond >= 10) perSecond.roundToLong().toString() else String.format(Locale.ROOT, "%.1f", perSecond)
        return (if (estimate) "≈" else "") + n + " tok/s"
    }

    /**
     * The Settings row: "Running on GPU · 18 tok/s", "Running on CPU (GPU failed: …) · ≈6 tok/s",
     * "Loading…", "Not loaded". GPU is named only when the engine was created on GPU.
     */
    fun backendLine(status: EngineStatus, modelId: String): String {
        if (status.modelId != modelId) return "Not loaded"
        if (status.loading) return "Loading…"
        val backend = status.backend ?: return status.lastError?.let { "Couldn't load: ${it.take(80)}" } ?: "Not loaded"
        return buildString {
            append("Running on ").append(backend.name)
            if (backend == Accelerator.CPU && status.gpuFailure != null) append(" (GPU failed: ").append(status.gpuFailure).append(')')
            status.lastTurn?.let { turn -> turn.decodePerSecond?.let { append(" · ").append(rate(it, estimate = !turn.measured)) } }
        }
    }

    /** Under an on-device reply: "GPU · ≈18 tok/s · prefill ≈240 tok/s". Null when nothing is known. */
    fun turnLine(speed: TurnSpeed): String? {
        val decode = speed.decodePerSecond ?: return null
        val estimate = !speed.measured
        return buildString {
            append(speed.backend.name).append(" · ").append(rate(decode, estimate))
            speed.prefillPerSecond?.let { append(" · prefill ").append(rate(it, estimate)) }
        }
    }

    /** "Loaded in 4.2 s · ≈1.3 GB". */
    fun loadedLine(status: EngineStatus): String? {
        if (!status.loaded) return null
        val parts = listOfNotNull(
            status.loadMillis?.let { "Loaded in ${seconds(it / 1000.0)}" },
            status.memoryBytes?.let { "≈${MemoryGate.gb(it / MemoryGate.GIB)} GB of memory" },
        )
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /** The benchmark result in three short lines. */
    fun benchmark(result: BenchmarkResult): List<String> = listOfNotNull(
        "Ran on ${result.backend.name}" + (result.gpuFailure?.let { " (GPU failed: $it)" } ?: ""),
        "Load ${seconds(result.loadSeconds)} · prefill ${rate(result.prefillPerSecond)} (${result.prefillTokens} tokens)",
        "Decode ${rate(result.decodePerSecond)} (${result.decodeTokens} tokens)",
    )

    /** "4.2 s", "0.35 s", "12 s". */
    fun seconds(value: Double): String = when {
        value >= 10 -> "${value.roundToLong()} s"
        value >= 1 -> String.format(Locale.ROOT, "%.1f s", value)
        else -> String.format(Locale.ROOT, "%.2f s", value)
    }

    private fun Double.roundToLong(): Long = Math.round(this)
}
