package dev.locus.feature.settings

import dev.locus.core.data.updates.Release
import dev.locus.core.data.updates.UpdateAvailability
import dev.locus.core.data.updates.UpdateInfo
import dev.locus.core.data.updates.UpdateProblem
import dev.locus.core.data.updates.UpdateState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Settings → About & privacy → Updates says in each state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdatesWordsTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    private val relative = { _: Long, _: Long -> "5 minutes ago" }
    private val info = UpdateInfo(UpdateAvailability.Available, enabled = true, checkedAt = 1000, installedVersion = "0.1.4")
    private val release = Release(tag = "v0.1.5")

    @Test fun `the status line covers every state`() {
        assertEquals("Checking GitHub…", updateStatusLine(res, info, UpdateState.Checking, 2000, relative))
        assertEquals("Up to date (0.1.4) · checked 5 minutes ago", updateStatusLine(res, info, UpdateState.UpToDate("0.1.4"), 2000, relative))
        assertEquals("No release published yet · checked 5 minutes ago", updateStatusLine(res, info, UpdateState.UpToDate(null), 2000, relative))
        assertEquals("0.1.5 available · checked 5 minutes ago", updateStatusLine(res, info, UpdateState.Available(release, false), 2000, relative))
        assertEquals("0.1.5 skipped · checked 5 minutes ago", updateStatusLine(res, info, UpdateState.Available(release, true), 2000, relative))
        assertEquals("Beta 0.2.0-beta.1 available · checked 5 minutes ago", updateStatusLine(res, info, UpdateState.Available(Release(tag = "v0.2.0-beta.1", prerelease = true), false), 2000, relative))
        assertEquals("Updating to 0.1.5", updateStatusLine(res, info, UpdateState.Downloading(release, 1, 2), 2000, relative))
        assertEquals("Checked 5 minutes ago", updateStatusLine(res, info, UpdateState.Failed(UpdateProblem.NoNetwork, null), 2000, relative))
        assertEquals("Waiting for Wi-Fi to check", updateStatusLine(res, info.copy(checkedAt = 0), UpdateState.Idle, 2000, relative))
        assertEquals("Not checked yet", updateStatusLine(res, info.copy(checkedAt = 0, enabled = false), UpdateState.Idle, 2000, relative))
    }

    @Test fun `builds that can't update themselves say why`() {
        assertNull(availabilityNote(res, UpdateAvailability.Available))
        assertTrue(availabilityNote(res, UpdateAvailability.DebugBuild)!!.startsWith("Debug build: updates come from your own builds"))
        assertTrue(availabilityNote(res, UpdateAvailability.FromStore("F-Droid"))!!.contains("F-Droid"))
        assertTrue(availabilityNote(res, UpdateAvailability.DisabledInBuild)!!.contains("doesn't check"))
    }

    @Test fun `each problem has its own message, and the key mismatch links to backup`() {
        val time = { _: Long -> "14:05" }
        assertTrue(problemMessage(res, UpdateProblem.SignatureMismatch, time).contains("Settings → Backup & restore"))
        assertTrue(problemMessage(res, UpdateProblem.ChecksumMismatch, time).contains("deleted"))
        assertTrue(problemMessage(res, UpdateProblem.InstallNotAllowed, time).contains("Allow from this source"))
        assertTrue(problemMessage(res, UpdateProblem.RateLimited(0), time).contains("14:05"))
        assertTrue(problemMessage(res, UpdateProblem.NoNetwork, time).contains("connection"))
        assertEquals("Android couldn't install the update.", problemMessage(res, UpdateProblem.InstallFailed(null), time))
        assertEquals("GitHub answered with an error (HTTP 502). Try again later.", problemMessage(res, UpdateProblem.Server(502), time))
    }

    @Test fun `download progress reads in megabytes`() {
        assertEquals("1.5 of 17.1 MB", downloadProgress(res, 1_500_000, 17_053_000))
        assertEquals("0.3 MB", downloadProgress(res, 300_000, -1))
    }
}
