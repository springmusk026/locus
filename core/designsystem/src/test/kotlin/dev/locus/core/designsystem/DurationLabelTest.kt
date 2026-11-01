package dev.locus.core.designsystem

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class DurationLabelTest {
    @Test
    fun `durations read short and follow the locale`() {
        assertEquals("—", durationLabel(-1, Locale.US))
        assertEquals("420ms", durationLabel(420, Locale.US))
        assertEquals("3.4s", durationLabel(3_400, Locale.US))
        assertEquals("3,4s", durationLabel(3_400, Locale.GERMANY))
        assertEquals("12s", durationLabel(12_300, Locale.US))
        assertEquals("2m 06s", durationLabel(126_000, Locale.US))
    }
}
