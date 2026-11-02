package dev.locus.core.ondevice

/**
 * The phone's memory as Android reports it ([android.app.ActivityManager.MemoryInfo]).
 * [advertisedBytes] is the marketed size (API 34+), null before that.
 */
data class DeviceMemory(
    val totalBytes: Long,
    val advertisedBytes: Long?,
    val availableBytes: Long,
    val lowMemory: Boolean,
)

/** Whether a model fits this phone. A warning, never a block: the user may go ahead anyway. */
data class RamCheck(
    /** The phone has less memory than the model is meant for. */
    val belowMinimum: Boolean,
    /** Android says it is low on memory right now. */
    val lowNow: Boolean,
    /** The phone's memory in GB, as shown to the user. */
    val deviceGb: Double,
    val minimumGb: Int,
) {
    val warn: Boolean get() = belowMinimum || lowNow
}

/**
 * Minimum-RAM gating, by total memory only (never by device name).
 *
 * The minimums come from Google AI Edge Gallery's model allowlist, which gates the same LiteRT-LM files
 * by `minDeviceMemoryInGb` (Gemma 4 E2B: 8, E4B: 12). Gallery compares against `advertisedMem` on
 * API 34+ and `totalMem` before that. `totalMem` is what the kernel can use, which is less than the
 * marketed size (an "8 GB" phone reports about 7.2–7.6 GiB), so on older Android the comparison
 * allows [TOTAL_MEM_TOLERANCE] of slack; otherwise every 8 GB phone would be warned about an 8 GB model.
 */
object MemoryGate {

    const val GIB = 1024.0 * 1024 * 1024

    /** totalMem must reach this share of the minimum on phones without advertisedMem. */
    const val TOTAL_MEM_TOLERANCE = 0.85

    fun check(minimumGb: Int, memory: DeviceMemory): RamCheck {
        val advertised = memory.advertisedBytes?.takeIf { it > 0 }
        val below = if (advertised != null) {
            advertised / GIB < minimumGb
        } else {
            memory.totalBytes / GIB < minimumGb * TOTAL_MEM_TOLERANCE
        }
        val shown = (advertised ?: memory.totalBytes) / GIB
        return RamCheck(belowMinimum = memory.totalBytes > 0 && below, lowNow = memory.lowMemory, deviceGb = shown, minimumGb = minimumGb)
    }

    /** The sentence shown before download and load, or null when the model fits. */
    fun warning(modelName: String, check: RamCheck): String? = when {
        check.belowMinimum ->
            "This phone has ${gb(check.deviceGb)} GB of memory; $modelName is meant for ${check.minimumGb} GB or more. It may be slow or fail on this phone."
        check.lowNow ->
            "Android reports this phone is low on memory right now. Close other apps first, or $modelName may be slow or fail to load."
        else -> null
    }

    /** "7.5" or "12" — one decimal, dropped when it is zero. */
    fun gb(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, rounded)
    }
}
