package dev.locus.core.ondevice

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryGateTest {

    private fun gib(v: Double) = (v * MemoryGate.GIB).toLong()
    private fun mem(total: Double, advertised: Double? = null, low: Boolean = false) =
        DeviceMemory(totalBytes = gib(total), advertisedBytes = advertised?.let(::gib), availableBytes = gib(total / 2), lowMemory = low)

    @Test
    fun advertisedMemory_isComparedDirectly() {
        assertFalse(MemoryGate.check(8, mem(7.4, advertised = 8.0)).belowMinimum)
        assertTrue(MemoryGate.check(12, mem(7.4, advertised = 8.0)).belowMinimum)
        assertFalse(MemoryGate.check(12, mem(11.0, advertised = 12.0)).belowMinimum)
        assertTrue(MemoryGate.check(8, mem(5.6, advertised = 6.0)).belowMinimum)
    }

    @Test
    fun totalMemory_allowsForKernelReservedMemory() {
        // A typical "8 GB" phone without advertisedMem reports about 7.2–7.6 GiB.
        assertFalse(MemoryGate.check(8, mem(7.2)).belowMinimum)
        // "12 GB" phones report about 10.9–11.3 GiB.
        assertFalse(MemoryGate.check(12, mem(10.9)).belowMinimum)
        // A "6 GB" phone (about 5.5 GiB) is below an 8 GB model.
        assertTrue(MemoryGate.check(8, mem(5.5)).belowMinimum)
        // An "8 GB" phone is below a 12 GB model.
        assertTrue(MemoryGate.check(12, mem(7.5)).belowMinimum)
        // Around the edge: just over 85% of the minimum passes, just under it doesn't.
        assertFalse(MemoryGate.check(8, mem(8 * MemoryGate.TOTAL_MEM_TOLERANCE + 0.01)).belowMinimum)
        assertTrue(MemoryGate.check(8, mem(8 * MemoryGate.TOTAL_MEM_TOLERANCE - 0.01)).belowMinimum)
    }

    @Test
    fun zeroAdvertisedFallsBackToTotal() {
        assertFalse(MemoryGate.check(8, DeviceMemory(gib(7.4), 0, gib(3.0), false)).belowMinimum)
    }

    @Test
    fun unknownMemoryNeverWarns() {
        assertFalse(MemoryGate.check(8, DeviceMemory(0, null, 0, false)).warn)
    }

    @Test
    fun warnings() {
        val below = MemoryGate.check(12, mem(7.5, advertised = 8.0))
        assertEquals(
            "This phone has 8 GB of memory; Gemma 4 E4B is meant for 12 GB or more. It may be slow or fail on this phone.",
            MemoryGate.warning("Gemma 4 E4B", below),
        )
        val low = MemoryGate.check(8, mem(11.0, advertised = 12.0, low = true))
        assertFalse(low.belowMinimum)
        assertTrue(low.warn)
        assertTrue(MemoryGate.warning("Gemma 4 E2B", low)!!.startsWith("Android reports this phone is low on memory"))
        assertNull(MemoryGate.warning("Gemma 4 E2B", MemoryGate.check(8, mem(11.0, advertised = 12.0))))
    }

    @Test
    fun gbFormatting() {
        assertEquals("8", MemoryGate.gb(8.0))
        assertEquals("7.5", MemoryGate.gb(7.46))
        assertEquals("11", MemoryGate.gb(10.98))
    }

    @Test
    fun catalogMinimums_matchGallery() {
        // Google AI Edge Gallery model_allowlists/1_0_22.json: Gemma-4-E2B-it 8, Gemma-4-E4B-it 12.
        assertEquals(8, OnDeviceCatalog.models.first { it.id == "gemma-4-e2b" }.minRamGb)
        assertEquals(12, OnDeviceCatalog.models.first { it.id == "gemma-4-e4b" }.minRamGb)
    }
}
