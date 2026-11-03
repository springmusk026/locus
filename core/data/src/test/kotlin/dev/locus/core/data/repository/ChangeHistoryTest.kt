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
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Each tool edit of a file in a turn is kept in order with its own diff; changes made in between are noted. */
class ChangeHistoryTest {
    private val base: File = Files.createTempDirectory("locus").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private val db = FakeDatabase()
    private val historyDir = File(base, "history")
    private val repo = ChangeRepository(db.fileChangeDao, ws, FileReads(), kotlinx.coroutines.Dispatchers.IO, ChangeHistory(historyDir))
    private var now = 1_000L

    init {
        repo.clock = { now }
    }

    @After
    fun cleanup() {
        base.deleteRecursively()
    }

    private suspend fun edit(path: String, text: String, turn: String = "t1", tool: String = "edit_file", call: String = "call") {
        val file = ws.resolve(path)
        val before = if (file.exists()) ws.backup(file, TrashEntry.Reason.OVERWRITTEN) else null
        file.parentFile?.mkdirs()
        file.writeText(text)
        repo.record(FileChange(ChangeOwner("c", turn), call, tool, path, before != null, before?.id))
    }

    private suspend fun changes() = repo.observe("c").first()

    @Test
    fun `edits are kept in order with their own diffs, and the row has the net diff`() = runTest {
        db.conversationDao.upsert(ConversationEntity("c", "Chat", null, "", 0, 0))
        edit("a.txt", "one\n", tool = "write_file", call = "k1")
        now = 2_000
        edit("a.txt", "one\ntwo\n", call = "k2")
        now = 3_000
        edit("a.txt", "1\ntwo\n", tool = "multi_edit", call = "k3")
        val row = changes().single()
        assertEquals(3, row.editCount)
        assertEquals(3_000L, row.updatedAt)
        assertEquals(listOf("write_file", "edit_file", "multi_edit"), row.edits.map { it.toolName })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), row.edits.map { it.at })
        assertEquals(listOf(1 to 0, 1 to 0, 1 to 1), row.edits.map { it.added to it.removed })
        assertEquals("--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,2 @@\n-one\n+1\n two\n", row.edits.last().diff)
        // The net diff is still from before the turn: a new file.
        assertEquals(2 to 0, row.added to row.removed)
    }

    @Test
    fun `a change made between two tool edits is noted, without a diff of its own`() = runTest {
        db.conversationDao.upsert(ConversationEntity("c", "Chat", null, "", 0, 0))
        edit("a.txt", "one\n")
        // A command rewrites it.
        ws.resolve("a.txt").writeText("formatted\n")
        now = 5_000
        edit("a.txt", "formatted\nmore\n")
        val edits = changes().single().edits
        assertEquals(listOf(false, true, false), edits.map { it.outside })
        assertEquals(ChangeRepository.OUTSIDE_TOOL, edits[1].toolName)
        assertEquals(null, edits[1].diff)
        assertEquals(2, changes().single().editCount)
        assertTrue(edits.zipWithNext().all { (a, b) -> a.at <= b.at })
    }

    @Test
    fun `undo then a new edit starts the history afresh, and a file created and removed leaves none`() = runTest {
        db.conversationDao.upsert(ConversationEntity("c", "Chat", null, "", 0, 0))
        ws.resolve("b.txt").writeText("x\n")
        edit("b.txt", "y\n")
        edit("b.txt", "z\n")
        repo.undo(listOf(changes().single().id))
        edit("b.txt", "w\n")
        assertEquals(1, changes().single().editCount)

        edit("tmp.txt", "t\n")
        val tmp = ws.resolve("tmp.txt")
        ws.trash(tmp, TrashEntry.Reason.DELETED)
        repo.record(FileChange(ChangeOwner("c", "t1"), "del", "delete_path", "tmp.txt", true, null))
        assertFalse(changes().any { it.path == "tmp.txt" })
        assertTrue(ChangeHistory(historyDir).forTurn("t1").none { it.path == "tmp.txt" })
    }

    @Test
    fun `caps - a huge edit keeps its counts, and pruned turns lose their history`() = runTest {
        val store = ChangeHistory(historyDir)
        val big = "x".repeat(ChangeHistory.MAX_EDIT_CHARS + 1)
        store.append("t1", FileEdit("a", "k", "write_file", 1, added = 1, diff = big))
        store.append("t1", FileEdit("a", "k2", "edit_file", 2, added = 2, diff = "small"))
        assertEquals(listOf(null, "small"), store.forTurn("t1").map { it.diff })
        store.append("t2", FileEdit("b", "k", "write_file", 3))
        store.retain(setOf("t2"))
        assertTrue(store.forTurn("t1").isEmpty())
        assertEquals(1, store.forTurn("t2").size)
        // A store without a folder keeps nothing and never fails.
        ChangeHistory(null).append("t", FileEdit("a", "k", "write_file", 1))
        assertTrue(ChangeHistory(null).forTurn("t").isEmpty())
    }

    @Test
    fun `a link at the history file is never read`() {
        historyDir.mkdirs()
        val outside = File(base, "secret.json").apply { writeText("""[{"path":"x","callId":"k","toolName":"t","at":1}]""") }
        Files.createSymbolicLink(File(historyDir, "t9.json").toPath(), outside.toPath())
        assertTrue(ChangeHistory(historyDir).forTurn("t9").isEmpty())
    }
}
