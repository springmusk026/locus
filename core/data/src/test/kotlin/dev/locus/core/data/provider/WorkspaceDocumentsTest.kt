package dev.locus.core.data.provider

import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The workspace documents provider's rules for ids and names that come from other apps (#368). */
class WorkspaceDocumentsTest {
    private val base: File = Files.createTempDirectory("locus").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private val docs = WorkspaceDocuments(ws)

    @After fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun `the workspace root can't be renamed away, by its id or a path that means it`() {
        File(ws.root, "notes.md").writeText("keep")
        for (id in listOf(WorkspaceDocuments.ROOT_DOC, ".", "", "projects/..")) {
            File(ws.root, "projects").mkdirs()
            assertFailsWith<UnsupportedOperationException>(id) { docs.rename(id, "x") }
        }
        assertFalse(File(base, "x").exists())
        assertEquals("keep", File(ws.root, "notes.md").readText())
    }

    @Test
    fun `the workspace root can't be deleted through a path that means it`() {
        File(ws.root, "a").mkdirs()
        for (id in listOf(WorkspaceDocuments.ROOT_DOC, ".", "a/..")) {
            assertFailsWith<UnsupportedOperationException>(id) { docs.delete(id) }
        }
        assertTrue(File(ws.root, "a").isDirectory)
    }

    @Test
    fun `a taken name never escapes the folder, and the number goes before the extension`() {
        File(ws.root, "inbox").mkdirs()
        val name = "../../shared_prefs/x"
        val first = docs.create("inbox", directory = false, displayName = name)
        val second = docs.create("inbox", directory = false, displayName = name)
        assertEquals(File(ws.root, "inbox").canonicalFile, first.parentFile.canonicalFile)
        assertEquals(File(ws.root, "inbox").canonicalFile, second.parentFile.canonicalFile)
        assertFalse(File(base, "shared_prefs").exists())

        docs.create("inbox", directory = false, displayName = "a.txt")
        assertEquals("a (1).txt", docs.create("inbox", directory = false, displayName = "a.txt").name)
        assertEquals("a (2).txt", docs.create("inbox", directory = false, displayName = "a.txt").name)
        docs.create("inbox", directory = true, displayName = "v1.2")
        assertEquals("v1.2 (1)", docs.create("inbox", directory = true, displayName = "v1.2").name)
        docs.create("inbox", directory = false, displayName = ".env")
        assertEquals(".env (1)", docs.create("inbox", directory = false, displayName = ".env").name)
    }

    @Test
    fun `dot names and empty names are refused`() {
        File(ws.root, "a").mkdirs()
        File(ws.root, "a/b.txt").writeText("x")
        for (name in listOf(".", "..", "", "  ")) {
            assertFailsWith<IllegalArgumentException>("create '$name'") { docs.create("a", directory = true, displayName = name) }
            assertFailsWith<IllegalArgumentException>("rename '$name'") { docs.rename("a/b.txt", name) }
        }
        assertEquals("x", File(ws.root, "a/b.txt").readText())
    }

    @Test
    fun `a rename stays in its folder`() {
        File(ws.root, "a").mkdirs()
        File(ws.root, "a/b.txt").writeText("x")
        val renamed = docs.rename("a/b.txt", "../../c.txt")
        assertEquals(File(ws.root, "a").canonicalFile, renamed.parentFile.canonicalFile)
        assertEquals("x", renamed.readText())
    }

    @Test
    fun `a grant for one folder doesn't cover a sibling reached with dot-dot`() {
        File(ws.root, "projects/a").mkdirs()
        File(ws.root, "projects/b").mkdirs()
        File(ws.root, "projects/b/secret.txt").writeText("s")
        File(ws.root, "projects/a/mine.txt").writeText("m")
        File(ws.root, "projects/ab").mkdirs()
        assertFalse(docs.isChild("projects/a", "projects/a/../b/secret.txt"))
        assertFalse(docs.isChild("projects/a", "projects/ab"))
        assertTrue(docs.isChild("projects/a", "projects/a/mine.txt"))
        assertTrue(docs.isChild("projects/a", "projects/a/./mine.txt"))
        assertTrue(docs.isChild("projects/a", "projects/a"))
        assertTrue(docs.isChild(WorkspaceDocuments.ROOT_DOC, "projects/b/secret.txt"))
        assertFalse(docs.isChild(WorkspaceDocuments.ROOT_DOC, "../trash"))
    }

    @Test
    fun `another app's write keeps the old contents in the trash first`() {
        File(ws.root, "notes.md").writeText("before")
        docs.beforeOpen("notes.md", "r")
        assertTrue(ws.trashEntries().isEmpty(), "a read keeps nothing")
        docs.beforeOpen("notes.md", "wt")
        val entry = ws.trashEntries().single()
        assertEquals("notes.md", entry.originalPath)
        assertEquals(TrashEntry.Reason.OVERWRITTEN, entry.reason)
        File(ws.root, "notes.md").writeText("after")
        ws.restore(entry.id)
        assertEquals("before", File(ws.root, "notes.md").readText())
    }
}
