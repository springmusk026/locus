package dev.locus.feature.settings

import dev.locus.core.data.backup.BackupManifest
import dev.locus.core.data.backup.BackupSection
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupSettingsTest {
    @Test
    fun `sizes read naturally`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1 MB", formatBytes(1024 * 1024))
        assertEquals("700 MB", formatBytes(700L * 1024 * 1024))
        assertEquals("2.3 GB", formatBytes(2_469_606_195))
    }

    @Test
    fun `the backup password needs eight characters, typed twice`() {
        assertEquals("Too short", backupPasswordProblem("short", "short"))
        assertEquals("Doesn't match", backupPasswordProblem("long enough", "long enougH"))
        assertNull(backupPasswordProblem("long enough", "long enough"))
    }

    @Test
    fun `the restore warning says what is replaced and what is kept`() {
        fun manifest(vararg names: String) = BackupManifest(appVersion = "0.1.0", appVersionCode = 1, schemaVersion = 8, createdAt = 0, sections = names.map { BackupSection(it, 1, 1) })
        val plain = restoreWarning(manifest("database", "settings", "agent-home"))
        assertTrue("workspace files stay" in plain && "Keys saved on this phone are kept" in plain, plain)
        val full = restoreWarning(manifest("database", "settings", "agent-home", "workspace", "vault"))
        assertTrue("workspace is replaced" in full && "keys are replaced" in full, full)
        assertEquals("3 entries", sectionSummary("vault", 3, 120))
        assertEquals("2 files · 1 KB", sectionSummary("workspace", 2, 1024))
    }
}
