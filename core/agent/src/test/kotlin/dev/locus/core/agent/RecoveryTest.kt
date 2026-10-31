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
}
