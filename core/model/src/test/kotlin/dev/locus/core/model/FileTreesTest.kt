package dev.locus.core.model

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Trees Linux can write hold links Linux made, with any host path as their target (proot keeps a
 * link's target as written). [FileTrees] must never follow one (docs/design/linux-isolation.md).
 */
class FileTreesTest {
    private val base: File = Files.createTempDirectory("filetrees").toFile()

    /** Stands in for Locus's private data: `/data/data/dev.locus/databases` and the like. */
    private val appData = File(base, "private").apply { mkdirs() }
    private val db = File(appData, "locus.db").apply { writeText("chats") }

    /** Stands in for a tree Linux can write: the rootfs, /tmp or /workspace. */
    private val guest = File(base, "guest").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        FileTrees.delete(base)
    }

    private fun link(at: File, to: File) {
        at.parentFile.mkdirs()
        Files.createSymbolicLink(at.toPath(), to.absoluteFile.toPath())
    }

    @Test
    fun `the stdlib follows links, which is why FileTrees exists`() {
        link(File(guest, "root/evil"), appData)
        File(guest, "root").deleteRecursively()
        // The Kotlin call deleted what the link pointed at: Locus's database.
        assertFalse(db.exists())
    }

    @Test
    fun `delete removes links, never what they point at`() {
        File(guest, "etc").mkdirs()
        File(guest, "etc/hosts").writeText("x")
        link(File(guest, "root/evil"), appData)
        link(File(guest, "root/db"), db)
        link(File(guest, "dangling"), File(base, "nothing-here"))

        assertTrue(FileTrees.delete(guest))

        assertFalse(FileTrees.exists(guest))
        assertEquals("chats", db.readText())
        assertTrue(appData.isDirectory)
    }

    @Test
    fun `deleting a link removes only the link`() {
        val evil = File(guest, "evil").also { link(it, appData) }
        assertTrue(FileTrees.delete(evil))
        assertFalse(FileTrees.exists(evil))
        assertEquals("chats", db.readText())
    }

    @Test
    fun `delete of nothing succeeds`() {
        assertTrue(FileTrees.delete(File(base, "missing")))
    }

    @Test
    fun `copy keeps links as links and never reads their targets`() {
        File(guest, "src/main.kt").apply { parentFile.mkdirs(); writeText("fun main() {}") }
        link(File(guest, "src/data"), appData)
        link(File(guest, "src/db.txt"), db)
        val to = File(base, "copy")

        FileTrees.copy(File(guest, "src"), to)

        assertEquals("fun main() {}", File(to, "main.kt").readText())
        assertTrue(FileTrees.isLink(File(to, "data")))
        assertTrue(FileTrees.isLink(File(to, "db.txt")))
        // The copy holds no regular file with Locus's data in it.
        val regular = to.walkTopDown().onEnter { !FileTrees.isLink(it) }.filter { !FileTrees.isLink(it) && it.isFile }.toList()
        assertEquals(listOf(File(to, "main.kt")), regular)
    }

    @Test
    fun `copy refuses to replace unless asked, and replaces a link instead of writing through it`() {
        File(guest, "a.txt").writeText("new")
        val to = File(base, "out").apply { mkdirs() }
        link(File(to, "a.txt"), db)

        assertFailsWith<FileAlreadyExistsException> { FileTrees.copy(File(guest, "a.txt"), File(to, "a.txt")) }
        FileTrees.copy(File(guest, "a.txt"), File(to, "a.txt"), overwrite = true)

        assertFalse(FileTrees.isLink(File(to, "a.txt")))
        assertEquals("new", File(to, "a.txt").readText())
        assertEquals("chats", db.readText())
    }

    @Test
    fun `copy merges a folder into an existing one`() {
        File(guest, "d/one").apply { parentFile.mkdirs(); writeText("1") }
        val to = File(base, "d").apply { mkdirs() }
        File(to, "two").writeText("2")
        FileTrees.copy(File(guest, "d"), to, overwrite = true)
        assertEquals("1", File(to, "one").readText())
        assertEquals("2", File(to, "two").readText())
    }

    @Test
    fun `replaceFile replaces a planted link instead of writing through it`() {
        // `ln -sf /data/data/dev.locus/databases/locus.db /etc/motd` inside Linux.
        link(File(guest, "etc/motd"), db)

        FileTrees.replaceFile(guest, "etc/motd", ByteArray(0))

        assertEquals("chats", db.readText())
        assertFalse(FileTrees.isLink(File(guest, "etc/motd")))
        assertEquals(0L, File(guest, "etc/motd").length())
        // No temporary file is left behind.
        assertEquals(listOf("motd"), File(guest, "etc").list()!!.toList())
    }

    @Test
    fun `replaceFile refuses a folder on the way that is a link`() {
        link(File(guest, "etc"), appData)
        assertFailsWith<IOException> { FileTrees.replaceFile(guest, "etc/locus.db", "x".toByteArray()) }
        assertEquals("chats", db.readText())
    }

    @Test
    fun `replaceFile creates missing folders and refuses climbing out`() {
        FileTrees.replaceFile(guest, "etc/apk/repositories", "a\n".toByteArray())
        assertEquals("a\n", File(guest, "etc/apk/repositories").readText())
        assertFailsWith<IllegalArgumentException> { FileTrees.replaceFile(guest, "../private/locus.db", "x".toByteArray()) }
        assertEquals("chats", db.readText())
    }

    @Test
    fun `writeReplacing writes a saved output even when Linux planted a link at its name`() {
        val out = File(guest, "workspace/.locus/output/20261103-120000-abc-run_command.log")
        link(out, db)
        FileTrees.writeReplacing(File(guest, "workspace"), out, "build log")
        assertEquals("build log", out.readText())
        assertFalse(FileTrees.isLink(out))
        assertEquals("chats", db.readText())
    }

    @Test
    fun `writeReplacing refuses a folder on the way that Linux swapped for a link`() {
        // The command itself runs `rm -rf /workspace/.locus; ln -s /data/data/dev.locus/... /workspace/.locus`
        // after Locus picked the output file's path and before it writes it.
        val workspace = File(guest, "workspace").apply { mkdirs() }
        link(File(workspace, ".locus"), appData)
        val out = File(workspace, ".locus/output/20261103-120000-abc-run_command.log")

        assertFailsWith<IOException> { FileTrees.writeReplacing(workspace, out, "build log") }
        assertEquals(listOf("locus.db"), appData.list()!!.toList())
        assertFailsWith<IOException> { FileTrees.writeReplacing(workspace, File(base, "elsewhere.log"), "x") }
    }

    @Test
    fun `copy of a regular file keeps its executable bit`() {
        val script = File(guest, "run.sh").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        FileTrees.copy(script, File(base, "run.sh"))
        assertTrue(File(base, "run.sh").canExecute())
        assertEquals("#!/bin/sh\n", File(base, "run.sh").readText())
    }
}
