package dev.locus.core.llm

import dev.locus.core.model.DeviceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's heat, battery, battery saver, network and memory pressure, as a flow (core/data
 * `AndroidDeviceConditions` on the phone). Decisions made from it are in [dev.locus.core.model.PowerPolicy].
 * See docs/design/battery-thermal.md.
 */
interface DeviceConditions {
    val state: StateFlow<DeviceState>

    /**
     * Android's forecast thermal headroom (`PowerManager.getThermalHeadroom`, API 30): 1.0 means severe
     * throttling within [forecastSeconds]. Null where unsupported or not known yet. Rate-limited by
     * the implementation (at most one platform call every 10 seconds, as the ADPF guide asks).
     */
    fun headroom(forecastSeconds: Int = 10): Float? = null
}

/** Conditions set by hand: tests, and anywhere there is no phone to read. */
class FixedDeviceConditions(initial: DeviceState = DeviceState(), var forecast: Float? = null) : DeviceConditions {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<DeviceState> = flow

    fun set(state: DeviceState) {
        flow.value = state
    }

    override fun headroom(forecastSeconds: Int): Float? = forecast
}
