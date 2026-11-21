package dev.locus.core.data.repository

import dev.locus.core.agent.ChatScope
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.tools.ChangeOwner
import dev.locus.core.agent.tools.FileChange
import dev.locus.core.agent.tools.FileReads
import dev.locus.core.agent.tools.ShellSnapshots
import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.database.ConversationEntity
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.MemoryScope
import dev.locus.core.testing.FakeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Shell commands' changes as tracked changes (docs/design/shell-snapshots.md): recorded for the turn, on the card,
 * undone from the snapshot's blobs with the same "changed since" check, rewound, skipped with a note, and nothing
 * at all with the setting off.
 */
class ShellChangeTrackerTest {
    private val base: File = Files.createTempDirectory("locus").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private val db = FakeDatabase()
    private val reads = FileReads()
    private val snapshots = ShellSnapshots(File(base, "snapshots"))
    private val notes = ShellNotes(File(base, "notes.json"))
    private val repo = ChangeRepository(db.fileChangeDao, ws, reads, Dispatchers.IO, ChangeHistory(File(base, "history")), snapshots, notes)
    private var enabled = setOf("p")
    private fun tracker(store: ShellSnapshots = snapshots) = ShellChangeTracker(ws, store, repo, notes, Dispatchers.IO, { it in enabled })
    private val scope = ChatScopeSpec("p", "projects/app", MemoryScope.PROJECT_AND_GLOBAL)
    private val folder = File(base, "workspace/projects/app").apply { mkdirs() }

    @After
    fun cleanup() {
        base.deleteRecursively()
    }

    private suspend fun chat() = db.conversationDao.upsert(ConversationEntity("c", "Chat", null, "", 0, 0))

    private fun file(path: String) = File(folder, path)
    private fun write(path: String, text: String) = file(path).apply { parentFile.mkdirs(); writeText(text) }

    /** Runs [command] as run_command would in turn [turn] of a project chat ([inProject]), through the tracker. */
    private suspend fun runCommand(
        turn: String = "t1",
        inProject: Boolean = true,
        tracker: ShellChangeTracker = tracker(),
        result: ToolResult = ToolResult("Exit code 0 (0.1 s).\n(no output)"),
        command: () -> Unit,
    ): ToolResult {
        val ctx = ToolCallContext("c", "call-$turn", 0, null, changeOwner = ChangeOwner("c", turn))
        return withContext(ctx + ChatScope.element(scope.takeIf { inProject })) { tracker.track { command(); result } }
    }

    private suspend fun changes() = repo.observe("c").first()

    @Test
    fun `a command's changes are recorded for its turn, and the result is untouched`() = runTest {
        chat()
        write("main.py", "print('one')\n")
        write("old.txt", "remove me\n")
        write("script.sh", "echo hi\n")
        val result = ToolResult("Exit code 0 (0.1 s).\nok")
        val returned = runCommand(result = result) {
            write("main.py", "print('two')\n")
            file("old.txt").delete()
            write("out/report.txt", "made by the command\n")
            Files.setPosixFilePermissions(file("script.sh").toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        assertSame(result, returned)
        val rows = changes()
        assertEquals(
            listOf(
                "projects/app/main.py" to TrackedChange.Kind.MODIFIED,
                "projects/app/old.txt" to TrackedChange.Kind.DELETED,
                "projects/app/out/report.txt" to TrackedChange.Kind.CREATED,
                "projects/app/script.sh" to TrackedChange.Kind.MODIFIED,
            ),
            rows.map { it.path to it.kind }.sortedBy { it.first },
        )
        assertTrue(rows.all { it.turnId == "t1" && it.byCommand && it.canUndo })
        val main = rows.single { it.path.endsWith("main.py") }
        assertEquals(1 to 1, main.added to main.removed)
        assertEquals("--- a/projects/app/main.py\n+++ b/projects/app/main.py\n@@ -1 +1 @@\n-print('one')\n+print('two')\n", main.diff)
        assertEquals(ShellSnapshots.TOOL, main.edits.single().toolName)
        assertTrue(main.beforeInSnapshot)
        assertTrue(notes.observe("c").first().isEmpty())
    }

    @Test
    fun `undo puts every file back from the snapshot, mode included`() = runTest {
        chat()
        write("main.py", "print('one')\n")
        write("old.txt", "remove me\n")
        write("script.sh", "echo hi\n")
        Files.setPosixFilePermissions(file("script.sh").toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        runCommand {
            write("main.py", "print('two')\n")
            file("old.txt").delete()
            write("new.txt", "new\n")
            Files.setPosixFilePermissions(file("script.sh").toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        reads.record("c", file("main.py"))
        val undone = repo.undoTurn("c", "t1")
        assertEquals(4, undone.size)
        assertEquals("print('one')\n", file("main.py").readText())
        assertEquals("remove me\n", file("old.txt").readText())
        assertFalse(file("new.txt").exists())
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file("script.sh").toPath())))
        assertEquals(FileReads.State.REVERTED, reads.state("c", file("main.py")))
        // What was there goes to the trash, so the undo can be undone too.
        assertTrue(ws.trashEntries().any { it.originalPath == "projects/app/main.py" })
        assertTrue(ws.trashEntries().any { it.originalPath == "projects/app/new.txt" })
        assertTrue(changes().all { it.undone })
    }

    @Test
    fun `a file changed after the command is reported before undo, and a rewind leaves it`() = runTest {
        chat()
        write("a.txt", "before\n")
        write("b.txt", "before\n")
        runCommand("t2") {
            write("a.txt", "by the command\n")
            write("b.txt", "by the command\n")
        }
        // The user (or another app) changes a.txt afterwards.
        write("a.txt", "the user's own edit\n")
        val a = changes().single { it.path.endsWith("a.txt") }
        assertTrue(repo.check(a.id).changedSince)
        assertFalse(repo.check(changes().single { it.path.endsWith("b.txt") }.id).changedSince)

        val plan = repo.rewindPlan("c", listOf("t2"))
        assertEquals(RewindFile.State.CHANGED_SINCE, plan.single { it.path.endsWith("a.txt") }.state)
        assertEquals(RewindFile.State.RESTORE, plan.single { it.path.endsWith("b.txt") }.state)
        val outcome = repo.rewind("c", listOf("t2"))
        assertEquals(listOf("projects/app/b.txt"), outcome.restored)
        assertEquals("the user's own edit\n", file("a.txt").readText(), "never overwritten")
        assertEquals("before\n", file("b.txt").readText())
    }

    @Test
    fun `rewind across a shell turn and a file-tool turn puts both back`() = runTest {
        chat()
        write("notes.md", "v0\n")
        write("data.csv", "x,y\n")
        // Turn 1: a file tool edits notes.md (with its trash copy, as the tools do).
        val stored = "projects/app/notes.md"
        val trash = ws.backup(file("notes.md"), TrashEntry.Reason.OVERWRITTEN)
        write("notes.md", "v1\n")
        repo.record(FileChange(ChangeOwner("c", "t1"), "call", "write_file", stored, true, trash.id))
        // Turn 2: a command changes both files, then a file tool edits notes.md again in the same turn.
        runCommand("t2") {
            write("notes.md", "v2 from sed\n")
            write("data.csv", "x,y\n1,2\n")
        }
        val trash2 = ws.backup(file("notes.md"), TrashEntry.Reason.OVERWRITTEN)
        write("notes.md", "v3\n")
        repo.record(FileChange(ChangeOwner("c", "t2"), "call2", "edit_file", stored, true, trash2.id))
        // The turn's history: the command's change, then the tool's, nothing "outside".
        val notesRow = changes().single { it.turnId == "t2" && it.path == stored }
        assertEquals(listOf(ShellSnapshots.TOOL, "edit_file"), notesRow.edits.map { it.toolName })
        assertTrue(notesRow.edits.none { it.outside })

        // The sheet can tell which files a command changed.
        assertTrue(repo.rewindPlan("c", listOf("t1", "t2")).all { it.byCommand })
        assertFalse(repo.rewindPlan("c", listOf("t1")).single().byCommand)
        val outcome = repo.rewind("c", listOf("t1", "t2"))
        assertEquals(setOf(stored, "projects/app/data.csv"), outcome.restored.toSet())
        assertEquals("v0\n", file("notes.md").readText())
        assertEquals("x,y\n", file("data.csv").readText())
    }

    @Test
    fun `with the setting off, or outside a project, nothing is snapshotted or recorded`() = runTest {
        chat()
        write("a.txt", "a\n")
        enabled = emptySet()
        val result = ToolResult("Exit code 0 (0.1 s).\n(no output)")
        assertSame(result, runCommand(result = result) { write("a.txt", "changed\n") })
        enabled = setOf("p")
        assertSame(result, runCommand(inProject = false, result = result) { write("a.txt", "changed again\n") })
        assertTrue(changes().isEmpty())
        assertFalse(File(base, "snapshots").exists(), "no snapshot store was even made")
        assertTrue(notes.observe("c").first().isEmpty())
    }

    @Test
    fun `a folder over the cap runs the command anyway and says why on the turn`() = runTest {
        chat()
        repeat(4) { write("f$it.txt", "x") }
        val small = ShellSnapshots(File(base, "small"), limits = ShellSnapshots.Limits(maxFiles = 3))
        var ran = false
        runCommand(tracker = tracker(small)) {
            ran = true
            write("f0.txt", "changed")
        }
        assertTrue(ran)
        assertTrue(changes().isEmpty())
        val note = notes.observe("c").first().getValue("t1").single()
        assertEquals(ShellSnapshots.SkipReason.TOO_MANY_FILES, note.reason)
        assertEquals("call-t1", note.callId)
        // The repository serves the same notes to the chat.
        assertEquals(listOf(note), repo.observeNotes("c").first().getValue("t1"))
    }

    @Test
    fun `a command that changes too many files is noted, not half recorded`() = runTest {
        chat()
        val limited = ShellSnapshots(File(base, "limited"), limits = ShellSnapshots.Limits(maxChanges = 2))
        runCommand(tracker = tracker(limited)) { repeat(3) { write("gen$it.txt", "x") } }
        assertTrue(changes().isEmpty())
        assertEquals(ShellSnapshots.SkipReason.TOO_MANY_CHANGES, notes.observe("c").first().getValue("t1").single().reason)
    }

    @Test
    fun `once its snapshot is cleared a command's change can't be undone, and says why`() = runTest {
        chat()
        write("a.txt", "before\n")
        runCommand { write("a.txt", "after\n") }
        snapshots.clear()
        val row = changes().single()
        assertFalse(row.canUndo)
        assertTrue(row.beforeInSnapshot)
        val blocked = assertNotNull(repo.check(row.id).blocked)
        assertTrue("command snapshot" in blocked, blocked)
        assertEquals(emptyList(), repo.undo(listOf(row.id)))
        assertEquals("after\n", file("a.txt").readText())
    }

    @Test
    fun `a stopped command's changes are still recorded`() = runTest {
        chat()
        write("a.txt", "before\n")
        val ctx = ToolCallContext("c", "call-t1", 0, null, changeOwner = ChangeOwner("c", "t1"))
        runCatching {
            withContext(ctx + ChatScope.element(scope)) {
                tracker().track {
                    write("a.txt", "half done\n")
                    throw kotlinx.coroutines.CancellationException("stopped")
                }
            }
        }
        assertEquals(TrackedChange.Kind.MODIFIED, changes().single().kind)
    }
}
