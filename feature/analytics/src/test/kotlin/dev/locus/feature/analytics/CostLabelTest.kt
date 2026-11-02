package dev.locus.feature.analytics

import dev.locus.core.data.repository.CostEstimate
import dev.locus.core.data.repository.CostReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CostLabelTest {
    @Test
    fun `a spend with no prices is unknown, never zero`() {
        assertEquals("Unknown", costLabel(CostEstimate(unpriced = 3)))
        assertEquals("–", costLabel(CostEstimate()))
    }

    @Test
    fun `priced spends are marked as estimates, and as a floor when some requests had no price`() {
        assertEquals("≈$1.24", costLabel(CostEstimate(usd = 1.237, priced = 2)))
        assertEquals("≈$1.24+", costLabel(CostEstimate(usd = 1.237, priced = 2, unpriced = 1)))
        assertEquals("<$0.01", costLabel(CostEstimate(usd = 0.004, priced = 1)))
        // A model on the phone: priced, and free.
        assertEquals("$0.00", costLabel(CostEstimate(usd = 0.0, priced = 1)))
    }

    @Test
    fun `the note says why the spend reads as it does`() {
        assertTrue(costNote(CostReport(pricesOn = false))!!.contains("which is off"))
        assertTrue(costNote(CostReport(pricesOn = true, month = CostEstimate(unpriced = 2)))!!.contains("unknown"))
        assertTrue(costNote(CostReport(pricesOn = true, month = CostEstimate(usd = 1.0, priced = 3, unpriced = 1)))!!.contains("1 of 4 requests"))
        assertNull(costNote(CostReport(pricesOn = true)))
    }

    @Test
    fun `the card offers to turn prices on, and to try again when they didn't load`() {
        val off = CostReport(pricesOn = false)
        val on = CostReport(pricesOn = true)
        assertEquals(CostAction.TurnOn, costAction(off, catalogLoaded = false, setup = PricesSetup.Idle))
        // While fetching there's nothing to tap twice; the note says what's happening.
        assertNull(costAction(off, catalogLoaded = false, setup = PricesSetup.Fetching))
        assertTrue(costNote(off, catalogLoaded = false, setup = PricesSetup.Fetching)!!.startsWith("Fetching"))
        assertEquals(CostAction.TryAgain, costAction(on, catalogLoaded = false, setup = PricesSetup.Failed))
        assertTrue(costNote(on, catalogLoaded = false, setup = PricesSetup.Failed)!!.startsWith("Couldn't download"))
        // On but not loaded yet (or a fetch at start failed quietly): unknown, with a way to fetch again.
        assertEquals(CostAction.TryAgain, costAction(on, catalogLoaded = false, setup = PricesSetup.Idle))
        assertTrue(costNote(on, catalogLoaded = false)!!.contains("haven't loaded"))
        assertNull(costAction(on, catalogLoaded = true, setup = PricesSetup.Idle))
    }
}
