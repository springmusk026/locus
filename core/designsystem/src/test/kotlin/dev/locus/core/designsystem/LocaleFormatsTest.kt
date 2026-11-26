package dev.locus.core.designsystem

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals

/**
 * Dates, times and money in the user's language (#320): fields in the language's order, its words, its
 * hour, and dollars written its way. 1_794_301_500_000 is Tuesday 10 November 2026, 09:05 UTC.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleFormatsTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val utc = ZoneOffset.UTC
    private val morning = 1_794_301_500_000
    private val evening = morning + 12 * 3_600_000

    private val es = Locale.forLanguageTag("es")
    private val pt = Locale.forLanguageTag("pt")
    private val zh = Locale.forLanguageTag("zh-Hans")

    // ICU puts no-break and narrow no-break spaces in some patterns; the words are what matter here.
    private fun plain(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ')

    private fun date(skeleton: String, locale: Locale, millis: Long = morning, is24Hour: Boolean? = null) =
        plain(formatDateTime(millis, skeleton, locale, utc, is24Hour))

    @Test
    fun `English keeps its words and order`() {
        assertEquals("Tue 10 Nov, 09:05", date(DateSkeleton.WEEKDAY_DAY_MONTH_TIME, Locale.UK))
        assertEquals("Tue, Nov 10, 9:05 AM", date(DateSkeleton.WEEKDAY_DAY_MONTH_TIME, Locale.US))
        assertEquals("Nov 10", date(DateSkeleton.DAY_MONTH, Locale.US))
    }

    @Test
    fun `dates are in the language's words and order`() {
        assertEquals("mar, 10 nov, 9:05", date(DateSkeleton.WEEKDAY_DAY_MONTH_TIME, es))
        assertEquals("ter., 10 de nov., 09:05", date(DateSkeleton.WEEKDAY_DAY_MONTH_TIME, pt))
        assertEquals("11月10日周二 09:05", date(DateSkeleton.WEEKDAY_DAY_MONTH_TIME, zh))
        // The issue's case: the day after the month, with 日, not "11月 10".
        assertEquals("11月10日", date(DateSkeleton.DAY_MONTH, zh))
        assertEquals("10 nov", date(DateSkeleton.DAY_MONTH, es))
        assertEquals("2026年11月", date(DateSkeleton.MONTH_YEAR, zh))
        assertEquals("novembro de 2026", date(DateSkeleton.MONTH_YEAR, pt))
        assertEquals("noviembre", date(DateSkeleton.MONTH, es))
    }

    @Test
    fun `the hour follows the phone's 12 or 24-hour setting`() {
        assertEquals("21:05", date(DateSkeleton.TIME, Locale.US, evening, is24Hour = true))
        assertEquals("9:05 pm", date(DateSkeleton.TIME, Locale.UK, evening, is24Hour = false))
        assertEquals("下午9:05", date(DateSkeleton.TIME, zh, evening, is24Hour = false))
        assertEquals("21:05", date(DateSkeleton.TIME, zh, evening, is24Hour = true))
        assertEquals("9:05 p. m.", date(DateSkeleton.TIME, es, evening, is24Hour = false))
    }

    @Test
    fun `dollars are written the language's way`() {
        assertEquals("$1.24", plain(usdAmount(Locale.US, 1.237)))
        assertEquals("1,24 US$", plain(usdAmount(es, 1.237)))
        assertEquals("US$ 1,24", plain(usdAmount(pt, 1.237)))
        assertEquals("US$1.24", plain(usdAmount(zh, 1.237)))
        assertEquals("0,012 US$", plain(usdAmount(es, 0.0123, fractionDigits = 3)))
    }

    @Test
    fun `in English the amounts read as the core writes them`() {
        // RunBudget.usd and HelperSpend.costLabel (core/agent), word for word.
        assertEquals("\$0.42", usdText(res, 0.42))
        assertEquals("<\$0.01", usdText(res, 0.004))
        assertEquals("\$0.00", usdText(res, 0.0))
        assertEquals("≈\$1.20+", usdEstimate(res, 1.2, partial = true))
        assertEquals("<\$0.01", usdEstimate(res, 0.004, partial = false))
        assertEquals("≈\$0.04", usdEstimate(res, 0.04, partial = false))
        assertEquals(TaskBudgetPresets.dollars(2.0), TaskBudgetPresets.dollars(res, 2.0))
        assertEquals(TaskBudgetPresets.dollars(0.25), TaskBudgetPresets.dollars(res, 0.25))
    }

    @Test
    @Config(qualifiers = "es")
    fun `estimates in Spanish`() {
        assertEquals("≈1,24 US$", plain(usdEstimate(res, 1.237, partial = false)))
        assertEquals("≈1,24 US$+", plain(usdEstimate(res, 1.237, partial = true)))
        assertEquals("<0,01 US$", plain(usdEstimate(res, 0.004, partial = false)))
        assertEquals("2 US$", plain(TaskBudgetPresets.dollars(res, 2.0)))
    }

    @Test
    @Config(qualifiers = "pt")
    fun `estimates in Portuguese`() {
        assertEquals("≈US$ 1,24", plain(usdEstimate(res, 1.237, partial = false)))
        assertEquals("US$ 0,25", plain(TaskBudgetPresets.dollars(res, 0.25)))
    }

    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun `estimates in Chinese`() {
        assertEquals("约 US$1.24", plain(usdEstimate(res, 1.237, partial = false)))
        assertEquals("<US$0.01", plain(usdText(res, 0.004)))
    }
}
