package dev.locus.core.model

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Workspace writes never go through a link a Linux program planted, at the name or on the way, even
 * when the link appears between Locus's check and its open (docs/design/linux-isolation.md, "Closing
 * the write race"). Every link here is a real symbolic link.
 */
class NoFollowFilesTest {
    private val tmp: File = Files.createTempDirectory("nofollow").toFile().canonicalFile

    /** Locus's own folder that holds the workspace (`files/`): Linux can't name it. */
    private val files = File(tmp, "files")
    private val workspace = File(files, "workspace").apply { mkdirs() }

    /** Stands for Locus's private data (`databases/`): no write may land here. */
    private val private = File(files, "databases").apply { mkdirs(); File(this, "locus.db").writeText("SQLite format 3") }

    @AfterTest
    fun cleanup() {
        FileTrees.delete(tmp)
    }

    private fun link(at: File, target: File) {
        Files.createSymbolicLink(at.toPath(), target.toPath())
    }

    /**
     * Behaves like the Android opener: reports where the descriptor really is (here the path's real
     * path at open time, as `/proc/self/fd` would), and runs [beforeOpen] and [afterOpen] around the
     * open, which is where a racing Linux program would swap a folder.
     */
    private class RacingOpener(private val beforeOpen: () -> Unit = {}, private val afterOpen: () -> Unit = {}) : NoFollowOpener {
        override fun open(path: Path, create: Boolean): NoFollowOpener.Opened {
            beforeOpen()
            val inner = JvmNoFollowOpener.open(path, create)
            val real = path.toRealPath().toString()
            afterOpen()
            return object : NoFollowOpener.Opened by inner {
                override val realPath: String = real
            }
        }

        override fun kernelPath(dir: Path): String = dir.toRealPath().toString()
    }

    @Test
    fun `writes a new file and the folders on the way`() {
        val file = File(workspace, "notes/today.md")
        NoFollowFiles.write(workspace, file, "hello".toByteArray())
        assertEquals("hello", file.readText())
        NoFollowFiles.write(workspace, file, "hi".toByteArray(), RacingOpener())
        assertEquals("hi", file.readText())
    }

    @Test
    fun `a link at the name is refused and its target untouched`() {
        val name = File(workspace, "notes.md")
        link(name, File(private, "locus.db"))
        assertFailsWith<NoFollowFiles.LinkException> { NoFollowFiles.write(workspace, name, "pwned".toByteArray()) }
        assertEquals("SQLite format 3", File(private, "locus.db").readText())
    }

    @Test
    fun `a dangling link at the name creates nothing at its target`() {
        val name = File(workspace, "skill.md")
        val target = File(files, "agent-home/skills/evil/SKILL.md")
        link(name, target)
        assertFailsWith<NoFollowFiles.LinkException> { NoFollowFiles.write(workspace, name, "evil".toByteArray()) }
        assertFalse(target.exists())
    }

    @Test
    fun `a folder on the way that is a link is refused`() {
        link(File(workspace, "app"), private)
        assertFailsWith<NoFollowFiles.LinkException> { NoFollowFiles.write(workspace, File(workspace, "app/locus.db"), "pwned".toByteArray()) }
        assertEquals("SQLite format 3", File(private, "locus.db").readText())
    }

    @Test
    fun `a folder swapped for a link just before the open is caught and the stray file removed`() {
        val dir = File(workspace, "out").apply { mkdirs() }
        val opener = RacingOpener(beforeOpen = { FileTrees.delete(dir); link(dir, private) })
        assertFailsWith<NoFollowFiles.LinkException> { NoFollowFiles.write(workspace, File(dir, "new.log"), "pwned".toByteArray(), opener) }
        assertFalse(File(private, "new.log").exists())
        assertEquals(listOf("locus.db"), private.list()!!.toList())
    }

    @Test
    fun `an existing file reached through a swapped folder is never truncated or written`() {
        val dir = File(workspace, "out").apply { mkdirs() }
        File(dir, "locus.db").writeText("mine")
        // Swapped to the link for the open, then back, so a second look at the folders would pass.
        val opener = RacingOpener(
            beforeOpen = { Files.move(dir.toPath(), File(workspace, "out.real").toPath()); link(dir, private) },
            afterOpen = { Files.delete(dir.toPath()); Files.move(File(workspace, "out.real").toPath(), dir.toPath()) },
        )
        assertFailsWith<NoFollowFiles.LinkException> { NoFollowFiles.write(workspace, File(dir, "locus.db"), "pwned".toByteArray(), opener) }
        assertEquals("SQLite format 3", File(private, "locus.db").readText())
        assertEquals("mine", File(dir, "locus.db").readText())
    }

    @Test
    fun `the workspace itself swapped for a link is caught by the anchor`() {
        val opener = RacingOpener(
            beforeOpen = { Files.move(workspace.toPath(), File(files, "workspace.real").toPath()); link(workspace, private) },
        )
        assertFailsWith<NoFollowFiles.LinkException> { NoFollowFiles.write(workspace, File(workspace, "x.md"), "pwned".toByteArray(), opener) }
        assertFalse(File(private, "x.md").exists())
    }

    @Test
    fun `without the descriptor's path a swap that stays is still caught before writing`() {
        val dir = File(workspace, "out").apply { mkdirs() }
        val opener = object : NoFollowOpener by JvmNoFollowOpener {
            override fun open(path: Path, create: Boolean): NoFollowOpener.Opened {
                FileTrees.delete(dir)
                link(dir, private)
                return JvmNoFollowOpener.open(path, create)
            }
        }
        assertFailsWith<NoFollowFiles.LinkException> { NoFollowFiles.write(workspace, File(dir, "locus.db"), "pwned".toByteArray(), opener) }
        assertEquals("SQLite format 3", File(private, "locus.db").readText())
    }

    @Test
    fun `streamed content gets the same checks`() {
        val file = File(workspace, "downloads/page.bin")
        NoFollowFiles.writeStream(workspace, file, RacingOpener()) { it.write("streamed".toByteArray()) }
        assertEquals("streamed", file.readText())
        val dir = File(workspace, "downloads")
        val opener = RacingOpener(beforeOpen = { FileTrees.delete(dir); link(dir, private) })
        assertFailsWith<NoFollowFiles.LinkException> {
            NoFollowFiles.writeStream(workspace, File(dir, "new.bin"), opener) { it.write("pwned".toByteArray()) }
        }
        assertFalse(File(private, "new.bin").exists())
    }

    @Test
    fun `when the descriptor's path is known, an anchor that can't be opened is refused`() {
        val opener = object : NoFollowOpener {
            private val inner = RacingOpener()

            override fun open(path: Path, create: Boolean) = inner.open(path, create)

            // As when the anchor is itself a link: O_NOFOLLOW refuses to open it.
            override fun kernelPath(dir: Path): String? = null
        }
        assertFailsWith<NoFollowFiles.LinkException> { NoFollowFiles.write(workspace, File(workspace, "x.md"), "hi".toByteArray(), opener) }
        assertFalse(File(workspace, "x.md").exists())
    }

    @Test
    fun `replaceFile checks the temporary file the same way`() {
        File(workspace, "etc").mkdirs()
        FileTrees.replaceFile(workspace, "etc/hosts", "127.0.0.1 localhost\n".toByteArray())
        assertTrue(File(workspace, "etc/hosts").readText().startsWith("127.0.0.1"))
    }
}
