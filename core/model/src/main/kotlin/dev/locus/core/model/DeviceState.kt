package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * Android's thermal status (`PowerManager.THERMAL_STATUS_*`, API 29), in the same order and with the
 * same numbers, so [fromAndroid] is an index. See docs/design/battery-thermal.md.
 */
enum class ThermalLevel(val label: String) {
    NONE("Normal temperature"),
    LIGHT("Slightly warm"),
    MODERATE("Warm"),
    SEVERE("Hot"),
    CRITICAL("Very hot"),
    EMERGENCY("Overheating"),
    SHUTDOWN("Overheating");

    companion object {
        /** From `PowerManager.getCurrentThermalStatus()`; an unknown higher value counts as the highest. */
        fun fromAndroid(status: Int): ThermalLevel = when {
            status < 0 -> NONE
            status >= entries.size -> SHUTDOWN
            else -> entries[status]
        }
    }
}

/** The network Android routes this app's traffic over by default. */
enum class NetworkKind { NONE, METERED, UNMETERED }

/**
 * What the phone is like right now, as `DeviceConditions` (core/llm) sources
 * report it: heat, battery, battery saver, network and memory pressure. Pure data, so every decision
 * made from it ([PowerPolicy]) is a plain function and unit-tested.
 */
data class DeviceState(
    val thermal: ThermalLevel = ThermalLevel.NONE,
    /** 0..100, or null when unknown. */
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** Battery saver (`PowerManager.isPowerSaveMode`). */
    val powerSave: Boolean = false,
    val network: NetworkKind = NetworkKind.UNMETERED,
    /** Android said memory is short (`onTrimMemory` / `onLowMemory`, or `ActivityManager.MemoryInfo.lowMemory`). */
    val lowMemory: Boolean = false,
) {
    /** Hot enough that Android is throttling (SEVERE and above). */
    val hot: Boolean get() = thermal >= ThermalLevel.SEVERE

    /** Hot enough to stop on-device generation (CRITICAL and above). */
    val critical: Boolean get() = thermal >= ThermalLevel.CRITICAL
}

/**
 * When a scheduled task may run, as WorkManager constraints (core/data `TaskConstraints`). A run that
 * finds them unmet waits; one already running when they stop holding is stopped and retried later.
 */
@Serializable
data class TaskConditions(
    val requiresCharging: Boolean = false,
    /** Wi-Fi or another unmetered network (`NetworkType.UNMETERED`). */
    val unmeteredOnly: Boolean = false,
    /** Not while Android says the battery is low (`setRequiresBatteryNotLow`). */
    val batteryNotLow: Boolean = true,
) {
    /** "Only when charging, on Wi-Fi", "Battery not low", or "Any time" when none is set. */
    val summary: String
        get() = listOfNotNull(
            "when charging".takeIf { requiresCharging },
            "on Wi-Fi".takeIf { unmeteredOnly },
            "battery not low".takeIf { batteryNotLow && !requiresCharging },
        ).let { parts -> if (parts.isEmpty()) "Any time" else "Only " + parts.joinToString(", ") }

    companion object {
        /** What tasks scheduled before these options existed have: just a network connection. */
        val LEGACY = TaskConditions(requiresCharging = false, unmeteredOnly = false, batteryNotLow = false)
    }
}

/** Settings → Battery & heat. */
@Serializable
data class PowerSettings(
    /** Hold non-urgent background work (titles, follow-ups, lessons, catalogue refresh) while hot or in battery saver. */
    val deferWhenHot: Boolean = true,
    /** Ask before an on-device reply while hot or in battery saver. */
    val warnOnDevice: Boolean = true,
    /** What a new scheduled task starts with. */
    val taskDefaults: TaskConditions = TaskConditions(),
    /** Download on-device models (gigabytes) only on Wi-Fi or another unmetered network. */
    val downloadsUnmeteredOnly: Boolean = true,
)

/** Why non-urgent work is on hold. */
enum class PauseReason(val chip: String, val why: String) {
    HOT("Paused: phone is hot", "The phone is hot"),
    POWER_SAVE("Paused: battery saver", "Battery saver is on"),
}

/** Before an on-device reply: why to hold off. [fewerThreads]: light mode would also run the CPU engine on fewer threads (not on GPU). */
data class LocalWarning(val reason: PauseReason, val thermal: ThermalLevel, val fewerThreads: Boolean)

/** Every battery and heat decision, from [DeviceState] and [PowerSettings]. Pure; see docs/design/battery-thermal.md. */
object PowerPolicy {
    /** An idle on-device model is unloaded after this long normally (it holds 2.6–3.7 GB). */
    const val IDLE_UNLOAD_MS = 10 * 60_000L

    /** ... in battery saver or while hot. */
    const val IDLE_UNLOAD_SAVING_MS = 2 * 60_000L

    /** ... when Android reports memory is short. Not zero: an agent between two steps would reload it at once. */
    const val IDLE_UNLOAD_LOW_MEMORY_MS = 20_000L

    /**
     * `getThermalHeadroom` at or above this means Android forecasts severe throttling; treated as hot
     * even before the status changes (ADPF: act on headroom before the status changes).
     */
    const val HEADROOM_SEVERE = 1.0f

    /** Whether to hold non-urgent background work, and why; null to run it now. Heat wins over battery saver. */
    fun deferReason(state: DeviceState, settings: PowerSettings): PauseReason? = when {
        !settings.deferWhenHot -> null
        state.hot -> PauseReason.HOT
        state.powerSave -> PauseReason.POWER_SAVE
        else -> null
    }

    /**
     * The warning before a long on-device reply: at SEVERE or above (or a headroom forecast of severe
     * throttling), or in battery saver. Null to go ahead.
     */
    fun localWarning(state: DeviceState, settings: PowerSettings, headroom: Float? = null, onGpu: Boolean? = null): LocalWarning? {
        if (!settings.warnOnDevice) return null
        val forecastHot = headroom != null && !headroom.isNaN() && headroom >= HEADROOM_SEVERE
        val thermal = if (forecastHot && state.thermal < ThermalLevel.SEVERE) ThermalLevel.SEVERE else state.thermal
        val reason = when {
            thermal >= ThermalLevel.SEVERE -> PauseReason.HOT
            state.powerSave -> PauseReason.POWER_SAVE
            else -> return null
        }
        // Light mode caps the reply's length on any backend; fewer CPU threads only matter on a CPU engine.
        return LocalWarning(reason, thermal, fewerThreads = onGpu == false)
    }

    /** Stop an on-device reply now (keeping what it wrote): CRITICAL and above. */
    fun stopLocalGeneration(state: DeviceState): Boolean = state.critical

    /** How long an idle on-device model stays loaded. */
    fun idleUnloadMs(state: DeviceState): Long = when {
        state.lowMemory -> IDLE_UNLOAD_LOW_MEMORY_MS
        state.powerSave || state.hot -> IDLE_UNLOAD_SAVING_MS
        else -> IDLE_UNLOAD_MS
    }

    /** Suggest stopping Linux services (never stop them automatically): CRITICAL and above, with some running. */
    fun suggestStoppingServices(state: DeviceState, running: Int): Boolean = state.critical && running > 0

    /** "Hot · 41% · battery saver · mobile data": what Settings shows. */
    fun describe(state: DeviceState): String = listOfNotNull(
        state.thermal.label,
        state.batteryPercent?.let { "$it%" + if (state.charging) ", charging" else "" },
        "battery saver".takeIf { state.powerSave },
        when (state.network) {
            NetworkKind.NONE -> "offline"
            NetworkKind.METERED -> "metered network"
            NetworkKind.UNMETERED -> "Wi-Fi"
        },
        "memory low".takeIf { state.lowMemory },
    ).joinToString(" · ")
}
