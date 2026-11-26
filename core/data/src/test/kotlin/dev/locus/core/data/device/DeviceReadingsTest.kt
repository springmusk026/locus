package dev.locus.core.data.device

import android.os.BatteryManager
import dev.locus.core.model.NetworkKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How `AndroidDeviceConditions` reads the platform's answers (docs/design/battery-thermal.md). */
class DeviceReadingsTest {
    @Test
    fun `no default network reads as offline, not Wi-Fi`() {
        assertEquals(NetworkKind.NONE, networkKindOf(connected = false, notMetered = false))
        assertEquals(NetworkKind.METERED, networkKindOf(connected = true, notMetered = false))
        assertEquals(NetworkKind.UNMETERED, networkKindOf(connected = true, notMetered = true))
    }

    @Test
    fun `charging means plugged in, even when the battery reports full`() {
        assertFalse(chargingFrom(BatteryManager.BATTERY_STATUS_FULL, plugged = 0), "unplugged at 100%")
        assertTrue(chargingFrom(BatteryManager.BATTERY_STATUS_FULL, plugged = BatteryManager.BATTERY_PLUGGED_AC))
        assertTrue(chargingFrom(BatteryManager.BATTERY_STATUS_CHARGING, plugged = BatteryManager.BATTERY_PLUGGED_USB))
        assertFalse(chargingFrom(BatteryManager.BATTERY_STATUS_DISCHARGING, plugged = 0))
        // No plug extra: only the status can say.
        assertTrue(chargingFrom(BatteryManager.BATTERY_STATUS_CHARGING, plugged = -1))
    }

    @Test
    fun `headroom is cached per forecast length and asked again after the interval`() {
        val cache = HeadroomCache(intervalMs = 10_000)
        var calls = 0
        assertEquals(0.5f, cache.get(10, now = 1_000) { calls++; 0.5f })
        assertEquals(0.5f, cache.get(10, now = 5_000) { calls++; 0.9f })
        // Another forecast length is its own question, not the 10-second answer.
        assertEquals(1.2f, cache.get(60, now = 5_000) { calls++; 1.2f })
        assertEquals(0.9f, cache.get(10, now = 11_000) { calls++; 0.9f })
        assertEquals(3, calls)
        // NaN (asked too often, or unsupported) is no answer.
        assertEquals(null, cache.get(30, now = 20_000) { Float.NaN })
    }
}
