package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerPolicyTest {
    private val on = PowerSettings()
    private val hot = DeviceState(thermal = ThermalLevel.SEVERE)
    private val critical = DeviceState(thermal = ThermalLevel.CRITICAL)
    private val saver = DeviceState(powerSave = true)

    @Test fun `thermal levels map Android's numbers, unknown high values count as the highest`() {
        assertEquals(ThermalLevel.NONE, ThermalLevel.fromAndroid(0))
        assertEquals(ThermalLevel.SEVERE, ThermalLevel.fromAndroid(3))
        assertEquals(ThermalLevel.SHUTDOWN, ThermalLevel.fromAndroid(6))
        assertEquals(ThermalLevel.SHUTDOWN, ThermalLevel.fromAndroid(42))
        assertEquals(ThermalLevel.NONE, ThermalLevel.fromAndroid(-1))
    }

    @Test fun `background work waits from severe up and in battery saver, heat first`() {
        assertNull(PowerPolicy.deferReason(DeviceState(thermal = ThermalLevel.MODERATE), on))
        assertEquals(PauseReason.HOT, PowerPolicy.deferReason(hot, on))
        assertEquals(PauseReason.HOT, PowerPolicy.deferReason(critical.copy(powerSave = true), on))
        assertEquals(PauseReason.POWER_SAVE, PowerPolicy.deferReason(saver, on))
        assertNull(PowerPolicy.deferReason(DeviceState(batteryPercent = 5), on), "a low battery alone is WorkManager's job")
    }

    @Test fun `turning deferral off runs everything`() {
        assertNull(PowerPolicy.deferReason(critical, on.copy(deferWhenHot = false)))
    }

    @Test fun `on-device warning at severe, critical or battery saver`() {
        assertNull(PowerPolicy.localWarning(DeviceState(), on))
        assertNull(PowerPolicy.localWarning(DeviceState(thermal = ThermalLevel.MODERATE), on))
        assertEquals(PauseReason.HOT, PowerPolicy.localWarning(hot, on)?.reason)
        assertEquals(ThermalLevel.CRITICAL, PowerPolicy.localWarning(critical, on)?.thermal)
        assertEquals(PauseReason.POWER_SAVE, PowerPolicy.localWarning(saver, on)?.reason)
        assertNull(PowerPolicy.localWarning(hot, on.copy(warnOnDevice = false)))
    }

    @Test fun `a headroom forecast of severe throttling warns before the status changes`() {
        val w = PowerPolicy.localWarning(DeviceState(thermal = ThermalLevel.LIGHT), on, headroom = 1.05f)
        assertEquals(PauseReason.HOT, w?.reason)
        assertEquals(ThermalLevel.SEVERE, w?.thermal)
        assertNull(PowerPolicy.localWarning(DeviceState(), on, headroom = 0.8f))
        assertNull(PowerPolicy.localWarning(DeviceState(), on, headroom = Float.NaN))
    }

    @Test fun `fewer threads only matter on a CPU engine`() {
        assertTrue(PowerPolicy.localWarning(hot, on, onGpu = false)!!.fewerThreads)
        assertFalse(PowerPolicy.localWarning(hot, on, onGpu = true)!!.fewerThreads)
        assertFalse(PowerPolicy.localWarning(hot, on, onGpu = null)!!.fewerThreads)
    }

    @Test fun `generation stops at critical and above only`() {
        assertFalse(PowerPolicy.stopLocalGeneration(hot))
        assertTrue(PowerPolicy.stopLocalGeneration(critical))
        assertTrue(PowerPolicy.stopLocalGeneration(DeviceState(thermal = ThermalLevel.EMERGENCY)))
        assertFalse(PowerPolicy.stopLocalGeneration(saver))
    }

    @Test fun `idle unload is sooner in battery saver, heat and low memory`() {
        assertEquals(PowerPolicy.IDLE_UNLOAD_MS, PowerPolicy.idleUnloadMs(DeviceState()))
        assertEquals(PowerPolicy.IDLE_UNLOAD_SAVING_MS, PowerPolicy.idleUnloadMs(saver))
        assertEquals(PowerPolicy.IDLE_UNLOAD_SAVING_MS, PowerPolicy.idleUnloadMs(hot))
        assertEquals(PowerPolicy.IDLE_UNLOAD_LOW_MEMORY_MS, PowerPolicy.idleUnloadMs(saver.copy(lowMemory = true)))
        assertTrue(PowerPolicy.IDLE_UNLOAD_LOW_MEMORY_MS < PowerPolicy.IDLE_UNLOAD_SAVING_MS)
    }

    @Test fun `services are suggested for stopping only at critical with some running`() {
        assertFalse(PowerPolicy.suggestStoppingServices(hot, 2))
        assertFalse(PowerPolicy.suggestStoppingServices(critical, 0))
        assertTrue(PowerPolicy.suggestStoppingServices(critical, 1))
    }

    @Test fun `task defaults are battery not low, and older tasks keep no conditions`() {
        assertEquals(TaskConditions(requiresCharging = false, unmeteredOnly = false, batteryNotLow = true), PowerSettings().taskDefaults)
        assertTrue(PowerSettings().downloadsUnmeteredOnly)
        assertEquals("Only battery not low", TaskConditions().summary)
        assertEquals("Any time", TaskConditions.LEGACY.summary)
        assertEquals("Only when charging, on Wi-Fi", TaskConditions(requiresCharging = true, unmeteredOnly = true).summary)
    }

    @Test fun `the description reads as one line`() {
        assertEquals(
            "Hot · 41%, charging · battery saver · metered network",
            PowerPolicy.describe(DeviceState(ThermalLevel.SEVERE, 41, charging = true, powerSave = true, network = NetworkKind.METERED)),
        )
    }
}
