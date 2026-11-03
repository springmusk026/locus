package dev.locus.core.data.diagnostics

import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.datastore.VaultCheck
import dev.locus.core.datastore.VaultState
import dev.locus.core.linux.LinuxReport
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.FileTrees
import dev.locus.core.model.ProviderConfig
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings → About & privacy → Diagnostics: the self-test's rules and the Storage screen's sizes and clean-ups. */
class SelfTestAndStorageTest {
    private val base: File = Files.createTempDirectory("storage").toFile()

    @After
    fun cleanup() {
        FileTrees.delete(base)
    }

    // ---- Self-test rules ------------------------------------------------------------------------

    @Test
    fun `the vault check passes with counts, and fails with the right fix otherwise`() {
        val ok = SelfTestRules.vault(VaultCheck(VaultState.Ready, entries = 3))
        assertEquals(SelfTestLevel.Pass, ok.level)
        assertTrue("3 saved values decrypt" in ok.detail)
        assertNull(ok.fix)

        val broken = SelfTestRules.vault(VaultCheck(VaultState.Ready, problem = "The saved-keys file doesn't decrypt with the current key."))
        assertEquals(SelfTestLevel.Fail, broken.level)

        assertEquals(SelfTestFix.ResetSavedKeys, SelfTestRules.vault(VaultCheck(VaultState.NeedsReset("gone", permanent = true))).fix)
        assertEquals(SelfTestLevel.Warn, SelfTestRules.vault(VaultCheck(VaultState.Locked)).level)
        assertEquals(SelfTestFix.RunAgain, SelfTestRules.vault(VaultCheck(VaultState.Unavailable("KeyStoreException"))).fix)
    }

    @Test
    fun `quick_check problems fail the database check, and a check that can't run says so`() {
        assertEquals(SelfTestLevel.Pass, SelfTestRules.database(emptyList(), null).level)
        val bad = SelfTestRules.database(listOf("row 3 missing from index idx_messages", "wrong # of entries"), null)
        assertEquals(SelfTestLevel.Fail, bad.level)
        assertTrue("2 problems" in bad.detail && "idx_messages" in bad.detail && "Backup" in bad.detail)
        val failed = SelfTestRules.database(null, "SQLiteException")
        assertEquals(SelfTestFix.RunAgain, failed.fix)
    }

    @Test
    fun `free storage uses Linux's thresholds`() {
        val gb = 1024L * 1024 * 1024
        assertEquals(SelfTestLevel.Pass, SelfTestRules.storage(10 * gb, 128 * gb).level)
        assertEquals(SelfTestLevel.Warn, SelfTestRules.storage(gb, 128 * gb).level)
        assertEquals(SelfTestFix.OpenStorage, SelfTestRules.storage(gb, 128 * gb).fix)
        assertEquals(SelfTestLevel.Fail, SelfTestRules.storage(100L * 1024 * 1024, 128 * gb).level)
        assertEquals(SelfTestLevel.Warn, SelfTestRules.storage(null, null).level)
    }

    @Test
    fun `notifications warn when off or not allowed`() {
        assertEquals(SelfTestLevel.Pass, SelfTestRules.notifications(enabled = true, permission = true).level)
        assertEquals(SelfTestLevel.Pass, SelfTestRules.notifications(enabled = true, permission = null).level)
        assertEquals(SelfTestFix.NotificationSettings, SelfTestRules.notifications(enabled = true, permission = false).fix)
        assertEquals(SelfTestLevel.Warn, SelfTestRules.notifications(enabled = false, permission = true).level)
    }

    @Test
    fun `Linux is skipped when not installed and otherwise follows its own health check`() {
        assertEquals(SelfTestLevel.Skipped, SelfTestRules.linux(installed = false, report = null).level)
        assertEquals(SelfTestFix.OpenLinux, SelfTestRules.linux(installed = true, report = null).fix)
        val healthy = LinuxReport(takenAt = 0, roundTrip = LinuxReport.RoundTrip(ok = true, millis = 200))
        assertEquals(SelfTestLevel.Pass, SelfTestRules.linux(true, healthy).level)
        val broken = healthy.copy(runtime = LinuxReport.Runtime(prootPresent = false))
        val result = SelfTestRules.linux(true, broken)
        assertEquals(SelfTestLevel.Fail, result.level)
        assertEquals(SelfTestFix.OpenLinux, result.fix)
    }

    @Test
    fun `provider results say whether it answered and took the key`() {
        val p = ProviderConfig("p1", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5")
        assertEquals(SelfTestLevel.Pass, SelfTestRules.provider(p, null, listed = 12, error = null).level)
        val missing = SelfTestRules.provider(p, "OpenAI has no API key. Add it in Settings → Models.", null, null)
        assertEquals(SelfTestFix.OpenModels, missing.fix)
        assertTrue(missing.detail.startsWith("Not checked"))
        assertEquals(SelfTestFix.OpenModels, SelfTestRules.provider(p, null, null, ProviderError("Unauthorized", 401)).fix)
        assertEquals(SelfTestLevel.Warn, SelfTestRules.provider(p, null, null, ProviderError("Not found", 404)).level)
        val down = SelfTestRules.provider(p, null, null, ProviderError("Connection refused", null))
        assertEquals(SelfTestLevel.Fail, down.level)
        assertTrue("api.openai.com" in down.detail)
        assertEquals("provider:p1", down.id)
    }

    // ---- Storage --------------------------------------------------------------------------------

    private fun link(at: File, to: File) {
        at.parentFile.mkdirs()
        Files.createSymbolicLink(at.toPath(), to.absoluteFile.toPath())
    }

    @Test
    fun `sizes never count what a link in the workspace or Linux points at`() = runTest {
        val private = File(base, "private").apply { mkdirs() }
        File(private, "locus.db").writeBytes(ByteArray(50_000))
        val workspace = File(base, "workspace").apply { mkdirs() }
        File(workspace, "notes.md").writeBytes(ByteArray(300))
        link(File(workspace, "escape"), private)
        val rootfs = File(base, "linux/alpine").apply { mkdirs() }
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/busybox").writeBytes(ByteArray(1_000))
        link(File(rootfs, "root/db"), File(private, "locus.db"))

        assertEquals(300L, StorageMeter.size(listOf(workspace)))
        assertEquals(1_000L, StorageMeter.size(listOf(File(base, "linux"))))
        assertEquals(1_300L, StorageMeter.size(listOf(workspace, File(base, "linux"), File(base, "missing"))))
    }

    @Test
    fun `clearing caches keeps the browser's own and anything in use, and never follows a link`() {
        val cache = File(base, "cache").apply { mkdirs() }
        val private = File(base, "private").apply { mkdirs() }
        val db = File(private, "locus.db").apply { writeBytes(ByteArray(5_000)) }
        val now = 100L * 60 * 60_000
        val old = now - 2 * StorageMeter.IN_USE_MS
        File(cache, "exports").apply { mkdirs() }.resolve("chat.md").writeBytes(ByteArray(700))
        File(cache, "models-dev-catalog.json").writeBytes(ByteArray(300))
        File(cache, "WebView").apply { mkdirs() }.resolve("Cache").writeBytes(ByteArray(900))
        val fresh = File(cache, "backup-1").apply { mkdirs() }.also { it.resolve("db").writeBytes(ByteArray(400)) }
        link(File(cache, "exports/escape"), private)
        listOf("exports", "models-dev-catalog.json", "WebView").forEach { File(cache, it).setLastModified(old) }
        fresh.setLastModified(now - 60_000)

        assertEquals(listOf("backup-1", "exports", "models-dev-catalog.json"), StorageMeter.cacheEntries(cache).map { it.name }.sorted())
        val freed = StorageMeter.clearCaches(cache, now)

        assertEquals(1_000L, freed)
        assertFalse(File(cache, "exports").exists())
        assertFalse(File(cache, "models-dev-catalog.json").exists())
        assertTrue(File(cache, "WebView/Cache").exists(), "the browser's cache is browser data")
        assertTrue(File(fresh, "db").exists(), "a scratch folder touched just now may be in use")
        assertTrue(db.exists() && db.length() == 5_000L, "the link was removed, not followed")
    }

    @Test
    fun `emptying the trash deletes every entry and nothing a link inside points at`() {
        val private = File(base, "private").apply { mkdirs() }
        val db = File(private, "locus.db").apply { writeText("chats") }
        val ws = Workspace(File(base, "workspace"), File(base, "trash"))
        val doomed = File(ws.base, "old").apply { mkdirs() }
        File(doomed, "a.txt").writeText("a")
        link(File(doomed, "escape"), private)
        ws.trash(doomed, TrashEntry.Reason.DELETED)
        ws.trash(File(ws.base, "b.txt").apply { writeText("b") }, TrashEntry.Reason.DELETED)
        assertEquals(2, ws.trashEntries().size)

        assertEquals(2, ws.emptyTrash())
        assertTrue(ws.trashEntries().isEmpty())
        assertEquals(0L, FileTrees.size(ws.trashFolder))
        assertTrue(db.exists())
    }
}
