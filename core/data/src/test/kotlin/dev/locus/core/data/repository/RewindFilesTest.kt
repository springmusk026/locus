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

/** Rewind here's files (docs/design/rewind.md): later turns put back newest first, a file changed since left alone. */
class RewindFilesTest {
    private val base: File = Files.createTempDirectory("locus").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private val db = FakeDatabase()
    private val reads = FileReads()
    private val repo = ChangeRepository(db.fileChangeDao, ws, reads, kotlinx.coroutines.Dispatchers.IO)

    @After
    fun cleanup() {
        base.deleteRecursively()
    }

    private suspend fun chat() = db.conversationDao.upsert(ConversationEntity("c", "Chat", null, "", 0, 0))

    private suspend fun edit(path: String, text: String, turn: String) {
        val file = ws.resolve(path)
        val before = if (file.exists()) ws.backup(file, TrashEntry.Reason.OVERWRITTEN) else null
        file.parentFile?.mkdirs()
        file.writeText(text)
        repo.record(FileChange(ChangeOwner("c", turn), "call", "write_file", path, before != null, before?.id))
    }

    private suspend fun delete(path: String, turn: String) {
        val entry = ws.trash(ws.resolve(path), TrashEntry.Reason.DELETED)
        repo.record(FileChange(ChangeOwner("c", turn), "call", "delete_path", path, true, entry.id))
    }

    @Test
    fun `later turns go back newest first, to before the oldest of them`() = runTest {
        chat()
        ws.resolve("a.txt").writeText("v0\n")
        edit("a.txt", "v1\n", "t1") // before the point: stays
        edit("a.txt", "v2\n", "t2")
        edit("a.txt", "v3\n", "t3")
        edit("new.txt", "made later\n", "t3")
        ws.resolve("gone.txt").writeText("keep me\n")
        delete("gone.txt", "t2")

        val plan = repo.rewindPlan("c", listOf("t2", "t3"))
        assertEquals(listOf("a.txt", "gone.txt", "new.txt"), plan.map { it.path })
        assertTrue(plan.all { it.state == RewindFile.State.RESTORE })
        assertEquals(TrackedChange.Kind.MODIFIED, plan[0].kind)
        assertEquals(TrackedChange.Kind.DELETED, plan[1].kind)
        assertEquals(TrackedChange.Kind.CREATED, plan[2].kind)
        // Nothing changed by looking.
        assertEquals("v3\n", ws.resolve("a.txt").readText())

        reads.record("c", ws.resolve("a.txt"))
        val outcome = repo.rewind("c", listOf("t2", "t3"))
        assertEquals(setOf("a.txt", "gone.txt", "new.txt"), outcome.restored.toSet())
        assertTrue(outcome.skipped.isEmpty())
        // t3 was undone before t2, so a.txt is as t1 left it, not as before t3.
        assertEquals("v1\n", ws.resolve("a.txt").readText())
        assertEquals("keep me\n", ws.resolve("gone.txt").readText())
        assertFalse(ws.resolve("new.txt").exists())
        assertEquals(FileReads.State.REVERTED, reads.state("c", ws.resolve("a.txt")))
        // The turn before the point keeps its change; the later ones show as undone.
        val rows = repo.observe("c").first()
        assertFalse(rows.single { it.turnId == "t1" }.undone)
        assertTrue(rows.filter { it.turnId != "t1" }.all { it.undone })
        // What was there is in the trash, so nothing is lost.
        assertTrue(ws.trashEntries().any { it.originalPath == "new.txt" })
    }

    @Test
    fun `a file changed outside Locus since is reported and never overwritten`() = runTest {
        chat()
        ws.resolve("a.txt").writeText("v0\n")
        ws.resolve("b.txt").writeText("b0\n")
        edit("a.txt", "v1\n", "t2")
        edit("b.txt", "b1\n", "t2")
        // The user (or a command) changes a.txt after the agent left it.
        ws.resolve("a.txt").writeText("mine\n")

        val plan = repo.rewindPlan("c", listOf("t2"))
        assertEquals(RewindFile.State.CHANGED_SINCE, plan.single { it.path == "a.txt" }.state)
        assertEquals(RewindFile.State.RESTORE, plan.single { it.path == "b.txt" }.state)

        val outcome = repo.rewind("c", listOf("t2"))
        assertEquals(listOf("b.txt"), outcome.restored)
        assertEquals(listOf("a.txt"), outcome.skipped.map { it.path })
        assertEquals("mine\n", ws.resolve("a.txt").readText())
        assertEquals("b0\n", ws.resolve("b.txt").readText())
        assertFalse(repo.observe("c").first().single { it.path == "a.txt" }.undone)
    }

    @Test
    fun `a file changed between the plan and the confirm is left alone`() = runTest {
        chat()
        ws.resolve("a.txt").writeText("v0\n")
        edit("a.txt", "v1\n", "t2")
        assertEquals(RewindFile.State.RESTORE, repo.rewindPlan("c", listOf("t2")).single().state)
        ws.resolve("a.txt").writeText("typed meanwhile\n")
        val outcome = repo.rewind("c", listOf("t2"))
        assertTrue(outcome.restored.isEmpty())
        assertEquals("typed meanwhile\n", ws.resolve("a.txt").readText())
    }

    @Test
    fun `a file whose earlier version left the trash can't be put back`() = runTest {
        chat()
        ws.resolve("a.txt").writeText("v0\n")
        edit("a.txt", "v1\n", "t2")
        ws.emptyTrash()
        assertEquals(RewindFile.State.GONE, repo.rewindPlan("c", listOf("t2")).single().state)
        assertEquals(RewindFile.State.GONE, repo.rewind("c", listOf("t2")).skipped.single().state)
        assertEquals("v1\n", ws.resolve("a.txt").readText())
    }
}
