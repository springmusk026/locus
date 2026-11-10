package dev.locus.feature.settings

import dev.locus.core.data.backup.BackupManifest
import dev.locus.core.data.backup.BackupSection
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupSettingsTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `sizes read naturally`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1 MB", formatBytes(1024 * 1024))
        assertEquals("700 MB", formatBytes(700L * 1024 * 1024))
        assertEquals("2.3 GB", formatBytes(2_469_606_195))
        // The decimal separator follows the language.
        assertEquals("1,5 KB", formatBytes(1536, java.util.Locale.forLanguageTag("es")))
        assertEquals("1.5 KB", formatBytes(1536, java.util.Locale.US))
    }

    @Test
    fun `backup outcomes say what happened, and running out of space links to Storage`() {
        val full = backupFailure(java.io.IOException("write failed: ENOSPC (No space left on device)"))
        assertTrue(full.failed)
        assertEquals(dev.locus.core.model.SettingsDestination.Storage, full.link)
        assertTrue(full.text.asString(res).startsWith("Backup failed: the phone ran out of space."), full.text.asString(res))
        val other = backupFailure(java.io.IOException("EIO"))
        assertNull(other.link)
        assertEquals(
            "Backup failed: Locus couldn't write the file. Try again, or save it in another folder. Delete the incomplete file where you saved it.",
            other.text.asString(res),
        )
        assertEquals("1 entry", sectionSummary(res, "vault", 1, 0))
        assertEquals("1 file · 1 KB", sectionSummary(res, "workspace", 1, 1024))
    }

    @Test
    fun `the backup password needs eight characters, typed twice`() {
        assertEquals(PasswordProblem.TooShort, backupPasswordProblem("short", "short"))
        assertEquals(PasswordProblem.Mismatch, backupPasswordProblem("long enough", "long enougH"))
        assertNull(backupPasswordProblem("long enough", "long enough"))
    }

    @Test
    fun `the restore warning says what is replaced and what is kept`() {
        fun manifest(vararg names: String) = BackupManifest(appVersion = "0.1.0", appVersionCode = 1, schemaVersion = 8, createdAt = 0, sections = names.map { BackupSection(it, 1, 1) })
        val plain = restoreWarning(res, manifest("database", "settings", "agent-home"))
        assertTrue("workspace files stay" in plain && "Keys saved on this phone are kept" in plain, plain)
        val full = restoreWarning(res, manifest("database", "settings", "agent-home", "workspace", "vault"))
        assertTrue("workspace is replaced" in full && "keys are replaced" in full, full)
        assertEquals("3 entries", sectionSummary(res, "vault", 3, 120))
        assertEquals("2 files · 1 KB", sectionSummary(res, "workspace", 2, 1024))
    }

    @Test
    fun `a restore that doesn't fit says how much it needs and how much to free`() {
        val need = SpaceNeeded(neededBytes = 2L * 1024 * 1024 * 1024, freeBytes = 512L * 1024 * 1024)
        assertEquals(1536L * 1024 * 1024, need.shortBytes)
        val text = noSpaceText(res, need)
        assertTrue("needs about 2 GB" in text && "512 MB is free" in text && "at least 1.5 GB" in text, text)
        assertEquals(0, SpaceNeeded(10, 20).shortBytes)
    }
}
