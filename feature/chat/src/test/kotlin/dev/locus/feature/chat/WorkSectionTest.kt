package dev.locus.feature.chat

import dev.locus.core.data.repository.FileEdit
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TodoItem
import dev.locus.core.model.TodoStatus
import dev.locus.core.model.Verification
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A turn's work is one section; repeated check runs are one row; the transcript's quieter pieces. */
class WorkSectionTest {
    private var t = 0L
    private fun msg(role: Role, vararg parts: Part) = Message("m${t++}", "c", role, parts.toList(), t)
    private fun run(id: String, command: String) = Part.ToolCall(id, "run_command", """{"command":${kotlinx.serialization.json.JsonPrimitive(command)}}""")
    private fun result(id: String, text: String, error: Boolean = false) = Part.ToolResult(id, "run_command", text, isError = error, decision = ApprovalDecision.AUTO_ALLOWED)

    /** Build, test, fix and rerun: five test runs, a lint, edits and narration in one turn. */
    private val loop = listOf(
        msg(Role.USER, Part.Text("make the tests pass")),
        msg(Role.ASSISTANT, run("r1", "npm test"), Part.ToolCall("e1", "edit_file", """{"path":"a.ts"}""")),
        msg(Role.TOOL, result("r1", "Exit code 1 (3.0 s).\nTests: 2 failed, 3 passed", error = true), Part.ToolResult("e1", "edit_file", "ok")),
        msg(Role.ASSISTANT, Part.Text("Rerunning."), run("r2", "npm test -- --run")),
        msg(Role.TOOL, result("r2", "Exit code 1 (2.0 s).\nTests: 1 failed, 4 passed", error = true)),
        msg(Role.ASSISTANT, run("r3", "npx vitest run"), run("l1", "npm run lint")),
        msg(Role.TOOL, result("r3", "Exit code 0 (2.5 s).\nTests: 5 passed"), result("l1", "Exit code 0 (1.0 s).")),
        msg(Role.ASSISTANT, run("r4", "npm test 2>&1 | tail -5")),
        msg(Role.TOOL, result("r4", "Exit code 0 (2.0 s).\nTests: 5 passed")),
        msg(Role.ASSISTANT, run("r5", "npm test")),
        msg(Role.TOOL, result("r5", "Exit code 0 (2.2 s).\nTests: 5 passed")),
        msg(Role.ASSISTANT, Part.Text("All green.")),
    )

    @Test
    fun `a turn's work, check runs included, is one section, and the reply stays apart`() {
        val rows = groupSteps(transcriptItems(loop))
        assertEquals(listOf(TranscriptRow.Single::class, TranscriptRow.Steps::class, TranscriptRow.Single::class), rows.map { it::class })
        val steps = (rows[1] as TranscriptRow.Steps).steps
        assertEquals(6, steps.count { it is ChatItem.Verification })
        assertTrue(rows[1].containsKey("m1:r1"))
    }

    @Test
    fun `repeated runs of one kind of check are one group, with the latest result and run count`() {
        val steps = (groupSteps(transcriptItems(loop))[1] as TranscriptRow.Steps).steps
        val groups = checkGroups(steps)
        assertEquals(listOf(Verification.Kind.TEST, Verification.Kind.CHECK), groups.map { it.kind })
        val tests = groups[0]
        assertEquals(5, tests.runs.size)
        assertEquals(
            listOf(RunState.FAILED, RunState.FAILED, RunState.PASSED, RunState.UNCLEAR, RunState.PASSED),
            tests.runs.map { runState(it, running = false) },
        )
        assertEquals("Tests · passed on run 5 of 5", checkGroupLabel(tests, running = false))
        assertEquals("Check passed", checkGroupLabel(groups[1], running = false))
        // The step list leaves check runs to their group.
        assertTrue(steps.filter { checkRunOf(it) == null }.none { it is ChatItem.Verification })
    }

    @Test
    fun `a rerun that's still going updates the same group in place`() {
        // The fifth run's call is saved; its result isn't in yet.
        val going = loop.dropLast(2)
        val steps = (groupSteps(transcriptItems(going)).last { it is TranscriptRow.Steps } as TranscriptRow.Steps).steps
        val tests = checkGroups(steps).first()
        assertEquals(5, tests.runs.size)
        assertNull(tests.latest.outcome)
        assertEquals("Tests · running (run 5)", checkGroupLabel(tests, running = true))
        // Stopped before it finished (no result, nothing running).
        assertEquals("Tests · stopped on run 5 of 5", checkGroupLabel(tests, running = false))
        // A plain command that hasn't finished stays a step.
        assertNull(checkRunOf(ChatItem.ToolCall("x", "run_command", """{"command":"ls"}""", null)))
    }

    @Test
    fun `a failed run says why in a line, and an untrusted pass says so`() {
        val failed = Verification.ofRunCommand("""{"command":"npm test"}""", "Exit code 1 (1.0 s).\nTests: 1 failed, 2 passed", isError = true)!!
        assertEquals("1 failed, 2 passed", verificationDetail(failed))
        val piped = Verification.ofRunCommand("""{"command":"npm test | tail"}""", "Exit code 0 (1.0 s).\nok", isError = false)!!
        assertEquals("Exit status not reliable: the command pipes or chains the check", verificationDetail(piped))
    }

    @Test
    fun `memory reads, the checklist and the changes card read compactly`() {
        assertEquals("Read 1 memory note", memoryReadSummary(1))
        assertEquals("Read 3 memory notes", memoryReadSummary(3))
        val todos = listOf(TodoItem("Plan", TodoStatus.COMPLETED), TodoItem("Redesign the portfolio", TodoStatus.IN_PROGRESS), TodoItem("Ship", TodoStatus.PENDING))
        assertEquals("1 of 3 done · Redesign the portfolio", checklistLine(todos))
        assertTrue(changesExpandedByDefault(1))
        assertTrue(changesExpandedByDefault(3))
        assertFalse(changesExpandedByDefault(4))
        assertFalse(changesExpandedByDefault(11))
    }

    @Test
    fun `a changes card sums what still stands and counts each file's edits`() {
        fun change(id: Long, added: Int, removed: Int, undone: Boolean = false, edits: List<FileEdit> = emptyList()) =
            TrackedChange(id, "t", "f$id", TrackedChange.Kind.MODIFIED, added, removed, null, binary = false, isDirectory = false, undone = undone, canUndo = true, edits = edits)
        val edits = listOf(
            FileEdit("f1", "k1", "write_file", 1),
            FileEdit("f1", "", "command", 2, outside = true),
            FileEdit("f1", "k2", "edit_file", 3),
        )
        val files = listOf(change(1, 10, 2, edits = edits), change(2, 5, 5), change(3, 100, 100, undone = true))
        assertEquals(15 to 7, changesTotals(files))
        assertEquals(2, files[0].editCount)
        assertEquals("Edited 2 times", editCountLabel(files[0]))
        assertNull(editCountLabel(files[1]))
        assertEquals("Changed outside the file tools (a command, or you)", editLabel(edits[1]))
    }
}
