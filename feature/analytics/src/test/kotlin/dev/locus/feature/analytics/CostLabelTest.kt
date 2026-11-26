package dev.locus.feature.analytics

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.data.repository.CostEstimate
import dev.locus.core.data.repository.CostReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs on Robolectric so the labels and notes are checked in the module's English strings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CostLabelTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `a spend with no prices is unknown, never zero`() {
        assertEquals("Unknown", costLabel(res, CostEstimate(unpriced = 3)))
        assertEquals("–", costLabel(res, CostEstimate()))
    }

    @Test
    fun `priced spends are marked as estimates, and as a floor when some requests had no price`() {
        assertEquals("≈$1.24", costLabel(res, CostEstimate(usd = 1.237, priced = 2)))
        assertEquals("≈$1.24+", costLabel(res, CostEstimate(usd = 1.237, priced = 2, unpriced = 1)))
        assertEquals("<$0.01", costLabel(res, CostEstimate(usd = 0.004, priced = 1)))
        // A model on the phone: priced, and free.
        assertEquals("$0.00", costLabel(res, CostEstimate(usd = 0.0, priced = 1)))
    }

    @Test
    fun `the note says why the spend reads as it does`() {
        assertTrue(costNote(res, CostReport(pricesOn = false))!!.contains("which is off"))
        assertTrue(costNote(res, CostReport(pricesOn = true, month = CostEstimate(unpriced = 2)))!!.contains("unknown"))
        assertTrue(costNote(res, CostReport(pricesOn = true, month = CostEstimate(usd = 1.0, priced = 3, unpriced = 1)))!!.contains("1 of 4 requests"))
        assertNull(costNote(res, CostReport(pricesOn = true)))
    }

    @Test
    fun `the card offers to turn prices on, and to try again when they didn't load`() {
        val off = CostReport(pricesOn = false)
        val on = CostReport(pricesOn = true)
        assertEquals(CostAction.TurnOn, costAction(off, catalogLoaded = false, setup = PricesSetup.Idle))
        // While fetching there's nothing to tap twice; the note says what's happening.
        assertNull(costAction(off, catalogLoaded = false, setup = PricesSetup.Fetching))
        assertTrue(costNote(res, off, catalogLoaded = false, setup = PricesSetup.Fetching)!!.startsWith("Fetching"))
        assertEquals(CostAction.TryAgain, costAction(on, catalogLoaded = false, setup = PricesSetup.Failed))
        assertTrue(costNote(res, on, catalogLoaded = false, setup = PricesSetup.Failed)!!.startsWith("Couldn't download"))
        // On but not loaded yet (or a fetch at start failed quietly): unknown, with a way to fetch again.
        assertEquals(CostAction.TryAgain, costAction(on, catalogLoaded = false, setup = PricesSetup.Idle))
        assertTrue(costNote(res, on, catalogLoaded = false)!!.contains("haven't loaded"))
        assertNull(costAction(on, catalogLoaded = true, setup = PricesSetup.Idle))
    }

    // #320: Insights wrote costs the US way and dates in a fixed order, whatever the language.
    private fun plain(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ')
    private val tuesday = 1_794_301_500_000 // 10 November 2026, 09:05 UTC

    @Test
    fun `in English the chart's day and the spend read as before`() {
        assertEquals("Nov 10", chartDate(res, tuesday, java.time.ZoneOffset.UTC))
    }

    @Test
    @Config(qualifiers = "es")
    fun `in Spanish costs and dates are written the Spanish way`() {
        assertEquals("≈1,24 US$", plain(costLabel(res, CostEstimate(usd = 1.237, priced = 2))))
        assertEquals("≈1,24 US$+", plain(costLabel(res, CostEstimate(usd = 1.237, priced = 2, unpriced = 1))))
        assertEquals("<0,01 US$", plain(costLabel(res, CostEstimate(usd = 0.004, priced = 1))))
        assertEquals("0,00 US$", plain(costLabel(res, CostEstimate(usd = 0.0, priced = 1))))
        assertEquals("10 nov", chartDate(res, tuesday, java.time.ZoneOffset.UTC))
    }

    @Test
    @Config(qualifiers = "pt")
    fun `in Portuguese costs and dates are written the Portuguese way`() {
        assertEquals("≈US$ 1,24", plain(costLabel(res, CostEstimate(usd = 1.237, priced = 2))))
        assertEquals("10 de nov.", chartDate(res, tuesday, java.time.ZoneOffset.UTC))
    }

    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun `in Chinese costs and dates are written the Chinese way`() {
        assertEquals("约 US$1.24", plain(costLabel(res, CostEstimate(usd = 1.237, priced = 2))))
        assertEquals("11月10日", chartDate(res, tuesday, java.time.ZoneOffset.UTC))
    }
}
