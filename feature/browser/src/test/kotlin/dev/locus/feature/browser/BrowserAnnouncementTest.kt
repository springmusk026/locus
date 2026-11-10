package dev.locus.feature.browser

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** What TalkBack hears when something waits in the browser, in the module's English strings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrowserAnnouncementTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `what TalkBack hears and the queue count`() {
        assertEquals("1 of 3", queueText(res, 3))
        assertEquals("Locus needs your approval. Click: Sign in", waitingAnnouncement(res, "Click: Sign in", null, 1))
        assertEquals("Locus asks: Which date? 1 of 2 waiting.", waitingAnnouncement(res, null, "Which date?", 2))
        assertEquals("Locus needs your approval. Click: Sign in. 1 of 2 waiting.", waitingAnnouncement(res, "Click: Sign in", null, 2))
        assertEquals("", waitingAnnouncement(res, null, null, 0))
    }
}
