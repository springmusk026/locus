package dev.locus.feature.analytics

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.model.toolActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs on Robolectric so the titles are checked in the English strings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SlowToolTitleTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `tools of the same kind get their own titles`() {
        assertNotEquals(slowToolTitle(res, "agent_spawn"), slowToolTitle(res, "agent_wait"))
        assertEquals("Wait for helpers", slowToolTitle(res, "agent_wait"))
    }

    @Test
    fun `wording that needs arguments falls back to the kind of work`() {
        assertEquals(toolActivity("write_file"), slowToolTitle(res, "write_file"))
    }
}
