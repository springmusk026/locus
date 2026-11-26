package dev.locus.feature.tasks

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.model.Reminder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * A reminder's line in Tasks in the user's language and clock (#320): it used English day and month
 * names on a 24-hour clock for everyone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderSubtitleTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val utc = ZoneOffset.UTC
    private val now = 1_794_301_500_000 // Tuesday 10 November 2026, 09:05 UTC
    private val day = 24 * 3_600_000L

    private fun plain(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ')
    private fun line(r: Reminder, is24Hour: Boolean?) = plain(reminderSubtitle(res, r, now, utc, is24Hour))

    // Saturday 14 November, 17:30; tomorrow at 10:00; shown today at 08:00.
    private val later = Reminder("a", "Call mum", at = now + 4 * day + 8 * 3_600_000 + 25 * 60_000)
    private val tomorrow = Reminder("b", "Bins", at = now + day + 55 * 60_000)
    private val shown = Reminder("c", "Stretch", at = now - 65 * 60_000, shownAt = now - 65 * 60_000)

    @Test
    fun `in English it reads as before, or on a 12-hour clock`() {
        assertEquals("Sat, Nov 14, 17:30", line(later, is24Hour = true))
        assertEquals("Tomorrow 10:00", line(tomorrow, is24Hour = true))
        assertEquals("Tomorrow 10:00 AM", line(tomorrow, is24Hour = false))
        assertEquals("Shown at 8:00 AM", line(shown, is24Hour = false))
    }

    @Test
    @Config(qualifiers = "es")
    fun `in Spanish`() {
        assertEquals("sáb, 14 nov, 17:30", line(later, is24Hour = true))
        assertEquals("Mañana 10:00", line(tomorrow, is24Hour = true))
        assertEquals("Mostrado a las 8:00 a. m.", line(shown, is24Hour = false))
    }

    @Test
    @Config(qualifiers = "pt")
    fun `in Portuguese`() {
        assertEquals("sáb., 14 de nov., 17:30", line(later, is24Hour = true))
        assertEquals("Amanhã 10:00", line(tomorrow, is24Hour = true))
    }

    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun `in Chinese`() {
        assertEquals("11月14日周六 17:30", line(later, is24Hour = true))
        assertEquals("11月14日周六 下午5:30", line(later, is24Hour = false))
        assertEquals("明天 10:00", line(tomorrow, is24Hour = true))
    }
}
