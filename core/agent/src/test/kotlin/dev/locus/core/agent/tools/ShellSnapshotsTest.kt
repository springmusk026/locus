package dev.locus.core.agent.tools

import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The command snapshot store (docs/design/shell-snapshots.md): what a snapshot holds and leaves out, the diff
 * of a command's changes, content-addressed dedupe, the caps, retention and garbage collection, and speed.
 */
class ShellSnapshotsTest {
    private val base: File = Files.createTempDirectory("snapshots").toFile()
    private val project = File(base, "project").apply { mkdirs() }
    private val outside = File(base, "outside").apply { mkdirs() }
    private var now = 1_000_000L
    private fun store(limits: ShellSnapshots.Limits = ShellSnapshots.Limits(), nanos: () -> Long = System::nanoTime) =
        ShellSnapshots(File(base, "store"), clock = { now }, limits = limits, nanos = nanos)

    @After
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun write(path: String, text: String) = File(project, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun ShellSnapshots.snap(turn: String = "t1", project: String = "p1"): ShellSnapshots.Manifest {
        now += 1_000 // each command a second after the last
        return assertIs<ShellSnapshots.Outcome.Taken>(take(project, this@ShellSnapshotsTest.project, "c", turn, "call")).manifest.also { release(it.id) }
    }

    private fun ShellSnapshots.after(): List<ShellSnapshots.Entry> = assertNotNull(scan(project).first)

    private fun blobCount() = File(base, "store/blobs").walkTopDown().count { it.isFile }

    @Test
    fun `a command's adds, changes, deletes, renames and mode changes are found`() {
        write("keep.txt", "same\n")
        write("edit.txt", "old\n")
        write("gone.txt", "bye\n")
        write("old-name.txt", "moved\n")
        write("run.sh", "echo hi\n")
        val s = store()
        val before = s.snap()

        // What a command might do.
        write("edit.txt", "new\n")
        File(project, "gone.txt").delete()
        File(project, "old-name.txt").renameTo(File(project, "sub/new-name.txt").apply { parentFile.mkdirs() })
        write("added.txt", "hello\n")
        Files.setPosixFilePermissions(File(project, "run.sh").toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))

        val changes = ShellSnapshots.diff(before.files, s.after())
        assertEquals(
            listOf(
                "added.txt" to ShellSnapshots.Change.Kind.ADDED,
                "edit.txt" to ShellSnapshots.Change.Kind.CHANGED,
                "gone.txt" to ShellSnapshots.Change.Kind.DELETED,
                "old-name.txt" to ShellSnapshots.Change.Kind.DELETED,
                "run.sh" to ShellSnapshots.Change.Kind.CHANGED,
                "sub/new-name.txt" to ShellSnapshots.Change.Kind.ADDED,
            ),
            changes.map { it.path to it.kind },
        )
        // The mode change is the mode only: same content, executable now.
        val run = changes.single { it.path == "run.sh" }
        assertEquals(run.before!!.h, run.after!!.h)
        assertEquals("755", Integer.toOctalString(run.after!!.m))
        // Every "before" can be read back from the store, byte for byte.
        changes.mapNotNull { it.before }.forEach { e -> assertEquals(e.s, s.blob(e.h)!!.length()) }
        assertEquals("old\n", s.blob(changes.single { it.path == "edit.txt" }.before!!.h)!!.readText())
        assertEquals("bye\n", s.blob(changes.single { it.path == "gone.txt" }.before!!.h)!!.readText())
    }

    @Test
    fun `nothing changed is no change`() {
        write("a.txt", "a")
        write("dir/b.txt", "b")
        val s = store()
        val before = s.snap()
        assertEquals(emptyList(), ShellSnapshots.diff(before.files, s.after()))
    }

    @Test
    fun `links are never followed or recorded`() {
        File(outside, "secret.txt").writeText("private data outside the project")
        File(outside, "dir").mkdirs()
        File(outside, "dir/inner.txt").writeText("also outside")
        write("real.txt", "inside")
        Files.createSymbolicLink(File(project, "file-link").toPath(), File(outside, "secret.txt").toPath())
        Files.createSymbolicLink(File(project, "dir-link").toPath(), File(outside, "dir").toPath())
        Files.createSymbolicLink(File(project, "dangling").toPath(), File(outside, "nothing").toPath())
        val s = store()
        val m = s.snap()
        assertEquals(listOf("real.txt"), m.files.map { it.p })
        // No byte from outside reached the store.
        val stored = File(base, "store/blobs").walkTopDown().filter { it.isFile }.map { it.readText() }.toList()
        assertEquals(listOf("inside"), stored)
        // A link the command makes is not a change either; the file it replaced is a delete.
        File(project, "real.txt").delete()
        Files.createSymbolicLink(File(project, "real.txt").toPath(), File(outside, "secret.txt").toPath())
        assertEquals(listOf("real.txt" to ShellSnapshots.Change.Kind.DELETED), ShellSnapshots.diff(m.files, s.after()).map { it.path to it.kind })
    }

    @Test
    fun `heavy and version-control folders are left out at any depth`() {
        write("src/main.py", "print(1)")
        for (dir in listOf(".git", "node_modules", "build", ".venv", "__pycache__", "app/build", "web/node_modules/pkg", ".locus/out")) {
            write("$dir/x.txt", "excluded")
        }
        write("building/kept.txt", "a folder that only starts like an excluded one")
        val m = store().snap()
        assertEquals(listOf("building/kept.txt", "src/main.py"), m.files.map { it.p }.sorted())
        assertTrue(ShellSnapshots.EXCLUDED_DIRS.containsAll(listOf(".git", "node_modules", "build", ".venv", "__pycache__")))
    }

    @Test
    fun `content is stored once however many files and snapshots hold it`() {
        repeat(10) { write("copy$it.txt", "the same content") }
        write("other.txt", "different")
        val s = store()
        s.snap("t1")
        assertEquals(2, blobCount())
        s.snap("t2")
        s.snap("t3")
        assertEquals(2, blobCount(), "unchanged files cost nothing in later snapshots")
        write("other.txt", "changed")
        s.snap("t4")
        assertEquals(3, blobCount(), "only the new content is added")
    }

    @Test
    fun `a folder over the caps is skipped, not snapshotted`() {
        repeat(6) { write("f$it.txt", "x".repeat(100)) }
        assertEquals(ShellSnapshots.Outcome.Skipped(ShellSnapshots.SkipReason.TOO_MANY_FILES), store(ShellSnapshots.Limits(maxFiles = 5)).take("p", project, "c", "t", "x"))
        assertEquals(ShellSnapshots.Outcome.Skipped(ShellSnapshots.SkipReason.TOO_LARGE), store(ShellSnapshots.Limits(maxBytes = 500)).take("p", project, "c", "t", "x"))
        // A slow phone: each look at the clock moves it on a second.
        var t = 0L
        assertEquals(ShellSnapshots.Outcome.Skipped(ShellSnapshots.SkipReason.TOO_SLOW), store(ShellSnapshots.Limits(maxMillis = 2_000), nanos = { t += 1_000_000_000; t }).take("p", project, "c", "t", "x"))
        // Nothing was stored for the skipped ones.
        assertEquals(0, blobCount())
        assertTrue(store().manifests("p").isEmpty())
        // The after-scan has the same caps.
        assertEquals(ShellSnapshots.SkipReason.TOO_MANY_FILES, store(ShellSnapshots.Limits(maxFiles = 5)).scan(project).second)
    }

    @Test
    fun `a second command in the same project while one runs is skipped`() {
        write("a.txt", "a")
        val s = store()
        val first = assertIs<ShellSnapshots.Outcome.Taken>(s.take("p", project, "c", "t", "1"))
        assertEquals(ShellSnapshots.Outcome.Skipped(ShellSnapshots.SkipReason.BUSY), s.take("p", project, "c", "t", "2"))
        // Another project isn't held up.
        assertIs<ShellSnapshots.Outcome.Taken>(s.take("q", project, "c", "t", "3"))
        s.release(first.manifest.id)
        assertIs<ShellSnapshots.Outcome.Taken>(s.take("p", project, "c", "t", "4"))
    }

    @Test
    fun `retention keeps the latest turns and days, and collects unreferenced blobs`() {
        val s = store(ShellSnapshots.Limits(keepTurns = 2, keepDays = 14))
        write("a.txt", "v1")
        s.snap("t1")
        write("a.txt", "v2")
        s.snap("t2")
        write("a.txt", "v3")
        s.snap("t3")
        s.snap("t3") // two commands in one turn count as one turn
        assertEquals(3, blobCount())
        s.retain()
        assertEquals(listOf("t3", "t3", "t2"), s.manifests("p1").map { it.turnId })
        assertEquals(2, blobCount(), "v1 was only in t1's snapshot")
        assertNull(s.blob(sha("v1")))
        assertNotNull(s.blob(sha("v2")))

        // Two weeks later t2 and t3 have expired; a new snapshot stays.
        now += 15L * 24 * 60 * 60 * 1000
        write("a.txt", "v4")
        s.snap("t4")
        s.retain()
        assertEquals(listOf("t4"), s.manifests("p1").map { it.turnId })
        assertEquals(1, blobCount())
    }

    @Test
    fun `over the size budget the oldest snapshots go first`() {
        val s = store(ShellSnapshots.Limits(budgetBytes = 2_500))
        write("big.bin", "a".repeat(1_000))
        s.snap("t1")
        write("big.bin", "b".repeat(1_000))
        s.snap("t2")
        write("big.bin", "c".repeat(1_000))
        s.snap("t3")
        s.retain()
        assertEquals(listOf("t3", "t2"), s.manifests("p1").map { it.turnId })
        assertTrue(File(base, "store/blobs").walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 2_500)
    }

    @Test
    fun `a snapshot whose command still runs is never dropped`() {
        val s = store()
        write("a.txt", "v1")
        val running = assertIs<ShellSnapshots.Outcome.Taken>(s.take("p1", project, "c", "t1", "x")).manifest
        // A command running for longer than the snapshots are kept: still there while it runs.
        now += 15L * 24 * 60 * 60 * 1000
        s.retain()
        assertNotNull(s.blob(running.files.single().h))
        s.release(running.id)
        s.retain()
        assertNull(s.blob(running.files.single().h))
    }

    @Test
    fun `clear deletes every snapshot and blob`() {
        write("a.txt", "a")
        val s = store()
        s.snap()
        assertTrue(s.sizeBytes() > 0)
        assertTrue(s.clear() > 0)
        assertEquals(0, blobCount())
        assertTrue(s.manifests("p1").isEmpty())
    }

    @Test
    fun `refs round-trip and nothing else parses as one`() {
        val e = ShellSnapshots.Entry("a", "a".repeat(64), "755".toInt(8), 3)
        assertEquals("a".repeat(64) to "755".toInt(8), ShellSnapshots.parseRef(ShellSnapshots.ref(e)))
        // A file tool's trash entry id never looks like one.
        assertNull(ShellSnapshots.parseRef("1700000000000-3fa2"))
        assertNull(ShellSnapshots.parseRef(null))
        assertNull(ShellSnapshots.parseRef("shell:../../etc:644"))
    }

    /**
     * Performance bound: a typical small project of 2,000 files (about 8 MB) is snapshotted, scanned after a
     * command and snapshotted again well within the time budget. The numbers are printed for the design note.
     */
    @Test
    fun `two thousand files snapshot within the time budget`() {
        val text = "line of source code that is about this long, repeated to fill a file\n".repeat(60) // ~4 KB
        repeat(2_000) { i -> write("src/pkg${i % 40}/File$i.kt", "// $i\n$text") }
        val s = store()
        val (first, firstMs) = timed { s.snap("t1") }
        val (_, scanMs) = timed { s.after() }
        val (_, againMs) = timed { s.snap("t2") }
        println("shell snapshots, 2,000 files / ${first.files.sumOf { it.s } / 1024} KB: first ${firstMs} ms, scan ${scanMs} ms, again (deduped) ${againMs} ms")
        assertEquals(2_000, first.files.size)
        assertTrue(firstMs < 5_000, "first snapshot took $firstMs ms")
        assertTrue(scanMs < 5_000, "scan took $scanMs ms")
        assertTrue(againMs < 5_000, "second snapshot took $againMs ms")
        assertFalse(store().manifests("p1").isEmpty())
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - start) / 1_000_000
    }

    private fun sha(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
