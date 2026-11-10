package dev.locus.core.designsystem

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DurationLabelTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `durations read short and follow the locale`() {
        assertEquals("—", durationLabel(res, -1, Locale.US))
        assertEquals("420ms", durationLabel(res, 420, Locale.US))
        assertEquals("3.4s", durationLabel(res, 3_400, Locale.US))
        assertEquals("3,4s", durationLabel(res, 3_400, Locale.GERMANY))
        assertEquals("12s", durationLabel(res, 12_300, Locale.US))
        assertEquals("2m 06s", durationLabel(res, 126_000, Locale.US))
    }
}
