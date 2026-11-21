package dev.locus.core.agent

import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolRisk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where Rewind here cuts, which turns' files go back, and what's listed as staying done (docs/design/rewind.md). */
class RewindTest {
    private fun msg(id: String, role: Role, vararg parts: Part) = Message(id, "c", role, parts.toList(), 0)

    private fun call(id: String, name: String, args: String = "{}") = Part.ToolCall(id, name, args)

    private fun result(id: String, name: String, decision: ApprovalDecision? = ApprovalDecision.AUTO_ALLOWED) =
        Part.ToolResult(id, name, "ok", decision = decision)

    private val path = listOf(
        msg("u1", Role.USER, Part.Text("Set up the project")),
        msg("a1", Role.ASSISTANT, call("c0", "write_file", """{"path":"a.txt"}""")),
        msg("t1", Role.TOOL, result("c0", "write_file")),
        msg("r1", Role.ASSISTANT, Part.Text("Done.")),
        msg("u2", Role.USER, Part.Text("Install and deploy")),
        msg(
            "a2", Role.ASSISTANT,
            call("c1", "run_command", """{"command":"npm install\nnpm test"}"""),
            call("c2", "edit_file", """{"path":"a.txt"}"""),
            call("c3", "fetch_url", """{"url":"https://example.com"}"""),
            call("c4", "fetch_url", """{"url":"https://hooks.example.com","method":"POST"}"""),
            call("c5", "agent_spawn", """{"type":"explore"}"""),
            call("c6", "memory_write", """{"name":"prefs"}"""),
            call("c7", "read_file", """{"path":"a.txt"}"""),
            call("c8", "run_command", """{"command":"rm -rf /"}"""),
            call("c9", "call_tool", """{"name":"linear_create_issue"}"""),
        ),
        msg(
            "t2", Role.TOOL,
            result("c1", "run_command"), result("c2", "edit_file"), result("c3", "fetch_url"), result("c4", "fetch_url"),
            result("c5", "agent_spawn"), result("c6", "memory_write"), result("c7", "read_file"),
            result("c8", "run_command", ApprovalDecision.USER_DENIED), result("c9", "call_tool", ApprovalDecision.USER_APPROVED),
        ),
        msg("r2", Role.ASSISTANT, Part.Text("Installed and deployed.")),
        msg("n2", Role.USER, Part.Text(ModeNote.wrap(ModeNote.ranAnyway("git push --force")))),
        msg("u3", Role.USER, Part.Text("Thanks")),
        msg("r3", Role.ASSISTANT, Part.Text("Any time.")),
    )

    private val risks = mapOf(
        "run_command" to ToolRisk.EXECUTE, "fetch_url" to ToolRisk.NETWORK, "memory_write" to ToolRisk.WRITE,
        "read_file" to ToolRisk.READ, "write_file" to ToolRisk.WRITE, "edit_file" to ToolRisk.WRITE,
        "agent_spawn" to ToolRisk.WRITE, "call_tool" to ToolRisk.EXECUTE,
    )

    @Test
    fun `a message the user sent rewinds to just before it, with its text for the composer`() {
        val cut = Rewind.plan(path, "u2").cut!!
        assertEquals(4, cut.keep)
        assertEquals("r1", cut.leaf)
        assertEquals("Install and deploy", cut.draft)
        // The turns after the point, keyed as AgentLoop keys their changes: the message after each turn start.
        assertEquals(listOf("a2", "r3"), cut.laterTurns)
        assertEquals(path.drop(4), cut.later)
    }

    @Test
    fun `a reply rewinds to the end of its turn, keeping the turn's changes`() {
        val cut = Rewind.plan(path, "r1").cut!!
        assertEquals("r1", cut.leaf)
        assertNull(cut.draft)
        assertEquals(listOf("a2", "r3"), cut.laterTurns)
        // From the reply of the second turn: only the third turn goes.
        val second = Rewind.plan(path, "r2").cut!!
        assertEquals("n2", second.leaf)
        assertEquals(listOf("r3"), second.laterTurns)
    }

    @Test
    fun `not offered on the first message, the last turn's reply, or a message on another branch`() {
        assertEquals(Rewind.Unavailable.FIRST_MESSAGE, Rewind.plan(path, "u1").unavailable)
        assertEquals(Rewind.Unavailable.NOTHING_AFTER, Rewind.plan(path, "r3").unavailable)
        assertEquals(Rewind.Unavailable.NOT_ON_BRANCH, Rewind.plan(path, "elsewhere").unavailable)
        // The last user message still can be: its turn goes and the text comes back.
        assertEquals(listOf("r3"), Rewind.plan(path, "u3").cut!!.laterTurns)
    }

    @Test
    fun `what ran after the point and stays is listed by kind, file tools and reads left out`() {
        val effects = Rewind.effects(Rewind.plan(path, "u2").cut!!.later) { risks[it] }
        assertEquals(
            listOf(
                Rewind.Effect(Rewind.EffectKind.COMMAND, "run_command", "npm install"),
                Rewind.Effect(Rewind.EffectKind.OUTSIDE, "fetch_url", "https://hooks.example.com"),
                Rewind.Effect(Rewind.EffectKind.HELPER, "agent_spawn", "agent_spawn"),
                Rewind.Effect(Rewind.EffectKind.OTHER, "memory_write", "prefs"),
                Rewind.Effect(Rewind.EffectKind.OUTSIDE, "call_tool", "linear_create_issue"),
                Rewind.Effect(Rewind.EffectKind.OTHER, Rewind.RAN_ANYWAY, "git push --force"),
            ),
            effects,
        )
        // The agent's note names each one.
        assertEquals("run_command: npm install", effects.first().note)
        // A tool the chat no longer has counts as another change.
        val unknown = Rewind.effects(listOf(msg("a", Role.ASSISTANT, call("x", "gone_tool")), msg("t", Role.TOOL, result("x", "gone_tool")))) { null }
        assertEquals(Rewind.EffectKind.OTHER, unknown.single().kind)
    }

    @Test
    fun `the agent's note says what it no longer sees, what was put back and what stays`() {
        val note = ModeNote.rewound(listOf("a.txt"), listOf("b.txt"), listOf("run_command: npm install"))
        assertTrue(note.startsWith("The user rewound the chat to this point."))
        assertTrue("put back a.txt as it was" in note && "read it again" in note, note)
        assertTrue("left as they are now, not as they were at this point: b.txt" in note, note)
        assertTrue("run_command: npm install" in note, note)
        assertTrue(ModeNote.isRewound(Message("m", "c", Role.USER, listOf(Part.Text(ModeNote.wrap(note))), 0)))
    }
}
