package dev.locus.core.linux

import dev.locus.core.model.FileTrees
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * proot resolves links in a binding's host path, and Linux can replace a bound folder with a link, so
 * what Locus binds must be checked first (docs/design/linux-isolation.md, "Bind points").
 */
class BindSourcesTest {
    private val base: File = Files.createTempDirectory("binds").toFile().canonicalFile
    private val appData = File(base, "databases").apply { mkdirs(); File(this, "locus.db").writeText("chats") }
    private val workspace = File(base, "workspace").apply { mkdirs() }

    @After
    fun cleanup() {
        FileTrees.delete(base)
    }

    private fun link(at: File, to: File) = Files.createSymbolicLink(at.toPath(), to.toPath())

    @Test
    fun `a bound folder Linux replaced with a link is put back as a real folder`() {
        // `rmdir /tmp; ln -s /data/data/dev.locus /tmp` inside Linux.
        val tmp = File(base, "tmp")
        link(tmp, appData)
        BindSources.realDir(tmp)
        assertFalse(FileTrees.isLink(tmp))
        assertTrue(tmp.isDirectory)
        assertEquals("chats", File(appData, "locus.db").readText())
    }

    @Test
    fun `a project folder that links outside the workspace is refused`() {
        // `rm -rf /workspace/app; ln -s /data/data/dev.locus /workspace/app`.
        link(File(workspace, "app"), appData)
        assertFailsWith<IllegalStateException> { BindSources.inside(workspace, File(workspace, "app")) }
        assertFailsWith<IllegalStateException> { BindSources.inside(workspace, File(workspace, "app/sub")) }
    }

    @Test
    fun `a dangling link on the way is refused`() {
        link(File(workspace, "app"), File(appData, "not-yet"))
        assertFailsWith<IllegalStateException> { BindSources.inside(workspace, File(workspace, "app")) }
    }

    @Test
    fun `a workspace that is itself a link is refused`() {
        val linked = File(base, "linked").also { link(it, appData) }
        assertFailsWith<IllegalStateException> { BindSources.inside(linked, linked) }
    }

    @Test
    fun `a link inside the workspace is resolved to its target there`() {
        val real = File(workspace, "real").apply { mkdirs() }
        link(File(workspace, "app"), real)
        assertEquals(real, BindSources.inside(workspace, File(workspace, "app")))
        assertEquals(workspace, BindSources.inside(workspace, workspace))
    }

    @Test
    fun `a fake proc file replaced with a link isn't plain`() {
        val fake = File(base, "stat").also { link(it, File(appData, "locus.db")) }
        assertFalse(BindSources.isPlainFile(fake))
        assertTrue(BindSources.isPlainFile(File(appData, "locus.db")))
    }
}
