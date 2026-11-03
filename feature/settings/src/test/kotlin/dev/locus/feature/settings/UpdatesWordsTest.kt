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

/** What Settings → About & privacy → Updates says in each state. */
class UpdatesWordsTest {
    private val relative = { _: Long, _: Long -> "5 minutes ago" }
    private val info = UpdateInfo(UpdateAvailability.Available, enabled = true, checkedAt = 1000, installedVersion = "0.1.4")
    private val release = Release(tag = "v0.1.5")

    @Test fun `the status line covers every state`() {
        assertEquals("Checking GitHub…", updateStatusLine(info, UpdateState.Checking, 2000, relative))
        assertEquals("Up to date (0.1.4) · checked 5 minutes ago", updateStatusLine(info, UpdateState.UpToDate("0.1.4"), 2000, relative))
        assertEquals("No release published yet · checked 5 minutes ago", updateStatusLine(info, UpdateState.UpToDate(null), 2000, relative))
        assertEquals("0.1.5 available · checked 5 minutes ago", updateStatusLine(info, UpdateState.Available(release, false), 2000, relative))
        assertEquals("0.1.5 skipped · checked 5 minutes ago", updateStatusLine(info, UpdateState.Available(release, true), 2000, relative))
        assertEquals("Updating to 0.1.5", updateStatusLine(info, UpdateState.Downloading(release, 1, 2), 2000, relative))
        assertEquals("Checked 5 minutes ago", updateStatusLine(info, UpdateState.Failed(UpdateProblem.NoNetwork, null), 2000, relative))
        assertEquals("Waiting for Wi-Fi to check", updateStatusLine(info.copy(checkedAt = 0), UpdateState.Idle, 2000, relative))
        assertEquals("Not checked yet", updateStatusLine(info.copy(checkedAt = 0, enabled = false), UpdateState.Idle, 2000, relative))
    }

    @Test fun `builds that can't update themselves say why`() {
        assertNull(availabilityNote(UpdateAvailability.Available))
        assertTrue(availabilityNote(UpdateAvailability.DebugBuild)!!.startsWith("Debug build: updates come from your own builds"))
        assertTrue(availabilityNote(UpdateAvailability.FromStore("F-Droid"))!!.contains("F-Droid"))
        assertTrue(availabilityNote(UpdateAvailability.DisabledInBuild)!!.contains("doesn't check"))
    }

    @Test fun `each problem has its own message, and the key mismatch links to backup`() {
        val time = { _: Long -> "14:05" }
        assertTrue(problemMessage(UpdateProblem.SignatureMismatch, time).contains("Settings → Backup & restore"))
        assertTrue(problemMessage(UpdateProblem.ChecksumMismatch, time).contains("deleted"))
        assertTrue(problemMessage(UpdateProblem.InstallNotAllowed, time).contains("Allow from this source"))
        assertTrue(problemMessage(UpdateProblem.RateLimited(0), time).contains("14:05"))
        assertTrue(problemMessage(UpdateProblem.NoNetwork, time).contains("connection"))
        assertEquals("Android couldn't install the update.", problemMessage(UpdateProblem.InstallFailed(null), time))
        assertEquals("GitHub answered with an error (HTTP 502). Try again later.", problemMessage(UpdateProblem.Server(502), time))
    }

    @Test fun `download progress reads in megabytes`() {
        assertEquals("1.5 of 17.1 MB", downloadProgress(1_500_000, 17_053_000))
        assertEquals("0.3 MB", downloadProgress(300_000, -1))
    }
}
