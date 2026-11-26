package dev.locus.core.agent

import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryTest {
    private fun msg(role: Role, vararg parts: Part) = Message(role.name + parts.size, "c", role, parts.toList(), 0)

    @Test
    fun `finished conversations need nothing`() {
        val r = recoverTranscript(listOf(msg(Role.USER, Part.Text("hi")), msg(Role.ASSISTANT, Part.Text("hello"))))
        assertTrue(r.missingResults.isEmpty())
        assertFalse(r.unfinished)
    }

    @Test
    fun `killed between tool call and result is repaired and resumable`() {
        val r = recoverTranscript(
            listOf(msg(Role.USER, Part.Text("run it")), msg(Role.ASSISTANT, Part.ToolCall("c1", "run_command", "{}"), Part.ToolCall("c2", "read_file", "{}"))),
        )
        assertEquals(listOf("c1", "c2"), r.missingResults.map { it.callId })
        assertTrue(r.missingResults.all { it.isError })
        assertTrue(r.unfinished)
    }

    @Test
    fun `killed before the model answered is resumable without repairs`() {
        val r = recoverTranscript(listOf(msg(Role.USER, Part.Text("hi"))))
        assertTrue(r.missingResults.isEmpty())
        assertTrue(r.unfinished)
        val afterTool = recoverTranscript(
            listOf(msg(Role.ASSISTANT, Part.ToolCall("c1", "x", "{}")), msg(Role.TOOL, Part.ToolResult("c1", "x", "ok"))),
        )
        assertTrue(afterTool.missingResults.isEmpty())
        assertTrue(afterTool.unfinished)
    }

    @Test
    fun `a checkpoint after a finished turn is not an interrupted run`() {
        val history = listOf(
            dev.locus.core.model.Message("1", "c", dev.locus.core.model.Role.USER, listOf(dev.locus.core.model.Part.Text("hi")), 0),
            dev.locus.core.model.Message("2", "c", dev.locus.core.model.Role.ASSISTANT, listOf(dev.locus.core.model.Part.Text("hello")), 1),
            dev.locus.core.model.Message("3", "c", dev.locus.core.model.Role.USER, listOf(dev.locus.core.model.Part.Checkpoint("summary", 2)), 2),
        )
        kotlin.test.assertFalse(recoverTranscript(history).unfinished)
    }

    @Test
    fun `the audit log tells a call that was running from calls that never started`() {
        val history = listOf(
            msg(Role.USER, Part.Text("send the report")),
            msg(
                Role.ASSISTANT,
                Part.ToolCall("c1", "write_file", "{}"), Part.ToolCall("c2", "email_send", "{}"),
                Part.ToolCall("c3", "run_command", "{}"), Part.ToolCall("c4", "read_file", "{}"),
            ),
        )
        // c1 finished; c2 was running when Locus died (no entry yet); c3 and c4 never started.
        val r = recoverTranscript(history, audited = mapOf("c1" to dev.locus.core.model.ApprovalDecision.AUTO_ALLOWED))
        val byCall = r.missingResults.associate { it.callId to it.content }
        assertEquals(RAN_BEFORE_CLOSE, byCall["c1"])
        assertEquals("Locus was closed while this was running; it may have completed. Check before running it again.", byCall["c2"])
        assertEquals("Not run: Locus was closed before this ran.", byCall["c3"])
        assertEquals("Not run: Locus was closed before this ran.", byCall["c4"])
        assertTrue(r.missingResults.all { it.isError })
        assertTrue(r.unfinished)
    }

    @Test
    fun `a refused call is not run, and with no audit entries the first call may have run`() {
        val history = listOf(
            msg(Role.ASSISTANT, Part.ToolCall("c1", "email_send", "{}"), Part.ToolCall("c2", "run_command", "{}")),
        )
        val refused = recoverTranscript(history, audited = mapOf("c1" to dev.locus.core.model.ApprovalDecision.USER_DENIED))
        assertEquals("Not run: Locus was closed before this ran.", refused.missingResults[0].content)
        assertEquals(CLOSED_WHILE_RUNNING, refused.missingResults[1].content)

        val none = recoverTranscript(history, audited = emptyMap())
        assertEquals(listOf(CLOSED_WHILE_RUNNING, "Not run: Locus was closed before this ran."), none.missingResults.map { it.content })
        // Without the audit log (a branch switch, a fork) every call reads as before.
        assertTrue(recoverTranscript(history).missingResults.all { it.content == "Not run: Locus was closed before this ran." })
    }
}
