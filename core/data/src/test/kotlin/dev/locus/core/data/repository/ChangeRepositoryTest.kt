package dev.locus.core.data.repository

import dev.locus.core.agent.tools.ChangeOwner
import dev.locus.core.agent.tools.FileChange
import dev.locus.core.agent.tools.FileReads
import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.database.ConversationEntity
import dev.locus.core.testing.FakeDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recording, aggregating per turn, undo (including after later changes) and the storage caps. */
class ChangeRepositoryTest {
    private val base: File = Files.createTempDirectory("locus").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private val db = FakeDatabase()
    private val reads = FileReads()
    private val repo = ChangeRepository(db.fileChangeDao, ws, reads)

    @After
    fun cleanup() {
        base.deleteRecursively()
    }

    private suspend fun chat(id: String) = db.conversationDao.upsert(ConversationEntity(id, "Chat", null, "", 0, 0))

    /** Edits [path] as the edit tools do (a trash copy, then the new text), and records it for [turn]. */
    private suspend fun edit(path: String, text: String, turn: String, chat: String = "c") {
        val file = ws.resolve(path)
        val before = if (file.exists()) ws.backup(file, TrashEntry.Reason.OVERWRITTEN) else null
        file.parentFile?.mkdirs()
        file.writeText(text)
        repo.record(FileChange(ChangeOwner(chat, turn), "call", "write_file", path, before != null, before?.id))
    }

    private suspend fun changes(chat: String = "c") = repo.observe(chat).first()

    @Test
    fun `several edits to a file in one turn are one row, diffed from before the first`() = runTest {
        chat("c")
        ws.resolve("a.txt").writeText("one\ntwo\n")
        edit("a.txt", "one\n2\n", "t1")
        edit("a.txt", "1\n2\n", "t1")
        val row = changes().single()
        assertEquals(TrackedChange.Kind.MODIFIED, row.kind)
        assertEquals(2 to 2, row.added to row.removed)
        assertEquals("--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,2 @@\n-one\n-two\n+1\n+2\n", row.diff)

        // Undo goes back to before the turn's first edit, and the chat must read the file again.
        reads.record("c", ws.resolve("a.txt"))
        assertEquals(listOf("a.txt"), repo.undo(listOf(row.id)))
        assertEquals("one\ntwo\n", ws.resolve("a.txt").readText())
        assertEquals(FileReads.State.REVERTED, reads.state("c", ws.resolve("a.txt")))
        assertTrue(changes().single().undone)
        // A second undo does nothing.
        assertEquals(emptyList(), repo.undo(listOf(row.id)))
    }

    @Test
    fun `a file created in a turn is removed by undo, into the trash`() = runTest {
        chat("c")
        edit("new.txt", "hello\n", "t1")
        val row = changes().single()
        assertEquals(TrackedChange.Kind.CREATED, row.kind)
        assertEquals("--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+hello\n", row.diff)
        repo.undo(listOf(row.id))
        assertFalse(ws.resolve("new.txt").exists())
        assertEquals("hello\n", ws.trashPayload(ws.trashEntries().single { it.originalPath == "new.txt" }.id)!!.readText())
    }

    @Test
    fun `created and removed in the same turn leaves nothing to show`() = runTest {
        chat("c")
        edit("tmp.txt", "x", "t1")
        val entry = ws.trash(ws.resolve("tmp.txt"), TrashEntry.Reason.DELETED)
        repo.record(FileChange(ChangeOwner("c", "t1"), "call", "delete_path", "tmp.txt", true, entry.id))
        assertTrue(changes().isEmpty())
    }

    @Test
    fun `a deleted file comes back on undo`() = runTest {
        chat("c")
        ws.resolve("keep.txt").writeText("precious\n")
        val entry = ws.trash(ws.resolve("keep.txt"), TrashEntry.Reason.DELETED)
        repo.record(FileChange(ChangeOwner("c", "t1"), "call", "delete_path", "keep.txt", true, entry.id))
        val row = changes().single()
        assertEquals(TrackedChange.Kind.DELETED, row.kind)
        assertEquals(0 to 1, row.added to row.removed)
        repo.undo(listOf(row.id))
        assertEquals("precious\n", ws.resolve("keep.txt").readText())
    }

    @Test
    fun `changed since is detected, and undoing an older turn still works`() = runTest {
        chat("c")
        ws.resolve("a.txt").writeText("v0\n")
        edit("a.txt", "v1\n", "t1")
        val first = changes().single()
        assertFalse(repo.check(first.id).changedSince)
        // A later turn changes it again.
        edit("a.txt", "v2\n", "t2")
        assertTrue(repo.check(first.id).changedSince)
        val second = changes().single { it.turnId == "t2" }
        assertFalse(repo.check(second.id).changedSince)
        // So does the user, outside any tool.
        ws.resolve("a.txt").writeText("mine\n")
        assertTrue(repo.check(second.id).changedSince)
        assertTrue(repo.checkTurn("c", "t1").changedSince)

        repo.undo(listOf(first.id))
        assertEquals("v0\n", ws.resolve("a.txt").readText())
        // The newer versions are in the trash.
        assertTrue(ws.trashEntries().any { it.reason == TrashEntry.Reason.MOVED_OVER && ws.trashPayload(it.id)!!.readText() == "mine\n" })
    }

    @Test
    fun `when the trash copy is gone, undo is refused and says why`() = runTest {
        chat("c")
        ws.resolve("a.txt").writeText("v0\n")
        edit("a.txt", "v1\n", "t1")
        val row = changes().single()
        assertTrue(row.canUndo)
        ws.trashEntries().forEach { ws.purgeTrash(it.id) }
        assertFalse(changes().single().canUndo)
        assertNotNull(repo.check(row.id).blocked)
        assertEquals(emptyList(), repo.undo(listOf(row.id)))
        assertEquals("v1\n", ws.resolve("a.txt").readText())
    }

    @Test
    fun `undo all goes back through moves in reverse`() = runTest {
        chat("c")
        ws.resolve("from.txt").writeText("moving\n")
        val kept = ws.backup(ws.resolve("from.txt"), TrashEntry.Reason.MOVED_AWAY)
        ws.resolve("from.txt").renameTo(ws.resolve("to.txt"))
        repo.record(FileChange(ChangeOwner("c", "t1"), "call", "move_path", "from.txt", true, kept.id))
        repo.record(FileChange(ChangeOwner("c", "t1"), "call", "move_path", "to.txt", false, null))
        assertEquals(setOf(TrackedChange.Kind.DELETED, TrackedChange.Kind.CREATED), changes().map { it.kind }.toSet())
        repo.undoTurn("c", "t1")
        assertEquals("moving\n", ws.resolve("from.txt").readText())
        assertFalse(ws.resolve("to.txt").exists())
    }

    @Test
    fun `each chat keeps its latest turns, and the oldest go first`() = runTest {
        chat("c")
        chat("other")
        edit("other.txt", "x\n", "o1", chat = "other")
        repeat(ChangeRepository.MAX_TURNS_PER_CHAT + 3) { i -> edit("f$i.txt", "$i\n", "t$i") }
        val kept = changes().map { it.turnId }
        assertEquals(ChangeRepository.MAX_TURNS_PER_CHAT, kept.size)
        assertEquals((3 until ChangeRepository.MAX_TURNS_PER_CHAT + 3).map { "t$it" }, kept)
        // Other chats keep theirs.
        assertEquals(1, changes("other").size)
        // The files themselves are untouched; only the records go.
        assertTrue(ws.resolve("f0.txt").exists())
    }

    @Test
    fun `diff text overall is capped, newest turn always kept`() = runTest {
        chat("c")
        // About 180k characters of diff per file (under the per-file cap), six files a turn: eight turns pass 8M.
        val big = (1..7_000).joinToString("\n", postfix = "\n") { "line $it with some text" }
        repeat(8) { i -> repeat(6) { f -> edit("big$i-$f.txt", big, "t$i") } }
        val kept = changes()
        assertTrue(kept.sumOf { it.diff?.length ?: 0 } <= ChangeRepository.MAX_DIFF_CHARS, "${kept.sumOf { it.diff?.length ?: 0 }}")
        assertEquals("t7", kept.last().turnId)
        assertNull(kept.firstOrNull { it.turnId == "t0" })
    }

    @Test
    fun `deleting a chat deletes its changes`() = runTest {
        chat("c")
        edit("a.txt", "x\n", "t1")
        db.conversationDao.delete("c")
        assertTrue(db.fileChangeRows.isEmpty())
    }
}
