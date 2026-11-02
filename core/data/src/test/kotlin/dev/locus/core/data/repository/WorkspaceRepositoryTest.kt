package dev.locus.core.data.repository

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Workspace screen's file operations: listing, previews, and delete/restore through the trash. */
class WorkspaceRepositoryTest {
    @Test
    fun `lists folders first, then files by name, case-insensitively`() = runTest {
        val g = TestGraph(backgroundScope)
        val ws = g.workspace
        ws.resolve("b.txt").writeText("b")
        ws.resolve("A.txt").writeText("a")
        ws.resolve("zoo").mkdirs()
        val entries = g.workspaceRepository.list(".")
        assertEquals(listOf("zoo", "A.txt", "b.txt"), entries.map { it.name })
        assertTrue(entries.first().isDirectory)
        assertEquals(1, entries.last().size)
    }

    @Test
    fun `previews text and refuses binary`() = runTest {
        val g = TestGraph(backgroundScope)
        g.workspace.resolve("notes.md").writeText("# Notes\nhello")
        g.workspace.resolve("photo.bin").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0, 0x47))
        assertEquals("# Notes\nhello", g.workspaceRepository.preview("notes.md"))
        assertEquals("# No", g.workspaceRepository.preview("notes.md", maxChars = 4))
        assertNull(g.workspaceRepository.preview("photo.bin"))
    }

    @Test
    fun `recent files skip hidden folders and dependency trees`() = runTest {
        val g = TestGraph(backgroundScope)
        val ws = g.workspace
        fun write(path: String, at: Long) = ws.resolve(path).apply { parentFile.mkdirs(); writeText(path); setLastModified(at) }
        write("report.md", 1_000_000)
        write("src/app.py", 3_000_000)
        write("node_modules/lib/index.js", 9_000_000)
        write(".git/HEAD", 9_000_000)
        write(".hidden", 9_000_000)
        assertEquals(listOf("src/app.py", "report.md"), g.workspaceRepository.recent(5).map { it.path })
        assertEquals(1, g.workspaceRepository.recent(1).size)
    }

    @Test
    fun `deleting goes to the trash and restoring puts it back, trashing whatever took its place`() = runTest {
        val g = TestGraph(backgroundScope)
        val repo = g.workspaceRepository
        g.workspace.resolve("docs").mkdirs()
        g.workspace.resolve("docs/plan.md").writeText("v1")

        repo.delete("docs/plan.md")
        assertFalse(g.workspace.resolve("docs/plan.md").exists())
        val deleted = repo.trash().single()
        assertEquals("docs/plan.md", deleted.originalPath)
        assertEquals(TrashEntry.Reason.DELETED, deleted.reason)

        g.workspace.resolve("docs/plan.md").writeText("v2")
        assertEquals("docs/plan.md", repo.restore(deleted.id))
        assertEquals("v1", g.workspace.resolve("docs/plan.md").readText())
        // The newer file that stood in the way is kept, not lost.
        val displaced = repo.trash().single()
        assertEquals(TrashEntry.Reason.MOVED_OVER, displaced.reason)

        repo.purge(displaced.id)
        assertTrue(repo.trash().isEmpty())
        assertEquals("v1", g.workspace.resolve("docs/plan.md").readText())
    }

    @Test
    fun `a whole folder round-trips through the trash`() = runTest {
        val g = TestGraph(backgroundScope)
        g.workspace.resolve("site/css").mkdirs()
        g.workspace.resolve("site/index.html").writeText("<h1>hi</h1>")
        g.workspace.resolve("site/css/a.css").writeText("body{}")
        g.workspaceRepository.delete("site")
        val entry = g.workspaceRepository.trash().single()
        assertTrue(entry.isDirectory)
        g.workspaceRepository.restore(entry.id)
        assertEquals("body{}", g.workspace.resolve("site/css/a.css").readText())
    }

    @Test
    fun `paths outside the workspace are refused, through dot-dot or a symlink`() = runTest {
        val g = TestGraph(backgroundScope)
        val outside = File(g.root, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("secret")
        val repo = g.workspaceRepository

        assertFailsWith<ToolException> { repo.list("../outside") }
        assertFailsWith<ToolException> { repo.preview("../outside/secret.txt") }
        assertFailsWith<ToolException> { repo.delete("../../files/agent-home") }
        assertFailsWith<ToolException> { repo.preview("docs/../../outside/secret.txt") }

        val link = g.workspace.resolve(".").toPath().resolve("link")
        val linked = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
        if (linked) {
            assertFailsWith<ToolException> { repo.preview("link/secret.txt") }
            assertFailsWith<ToolException> { repo.delete("link") }
        }
        assertTrue(File(outside, "secret.txt").exists())
        // A leading slash means the workspace root, not the filesystem's.
        assertEquals(g.workspace.resolve("."), g.workspace.resolve("/"))
    }
}
