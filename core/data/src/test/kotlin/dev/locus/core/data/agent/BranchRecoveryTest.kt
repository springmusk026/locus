package dev.locus.core.data.agent

import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.SubagentStatus
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dangling tool calls after the app was killed, or on a branch that was left mid-step: every call gets
 * an answer (providers reject unanswered calls) on the branch it belongs to, and only an unfinished
 * turn offers Continue.
 */
class BranchRecoveryTest {
    private var clock = 1_000L
    private fun assistant(chat: String, id: String, vararg parts: Part) = Message(id, chat, Role.ASSISTANT, parts.toList(), clock++)

    @Test
    fun `a branch left mid-call is answered when it's shown again, and offers Continue`() = runTest {
        val g = TestGraph(backgroundScope)
        val repo = g.conversations
        val chat = repo.create(null).id
        val ask = repo.appendUserMessage(chat, "clean up the downloads folder")
        repo.append(assistant(chat, "a1", Part.ToolCall("k1", "list_files", "{}")))
        // The user edited the question while the first branch was mid-call; the new branch finished.
        val edit = repo.appendUserMessage(chat, "only delete the .tmp files", replacing = ask.id)
        repo.append(assistant(chat, "a2", Part.Text("Done.")))
        val runner = g.runner
        runCurrent()
        // Startup recovery sees only the shown branch, which finished.
        assertFalse(runner.states.value[chat]?.interrupted == true)
        assertEquals(listOf(edit.id, "a2"), repo.history(chat).map { it.id })

        runner.switchBranch(chat, ask.id)
        runCurrent()
        val shown = repo.history(chat)
        assertEquals(listOf(ask.id, "a1"), shown.take(2).map { it.id })
        val answer = shown.last()
        assertEquals(Role.TOOL, answer.role)
        assertEquals("k1", answer.toolResults.single().callId)
        assertTrue(answer.toolResults.single().isError)
        val state = runner.states.value.getValue(chat)
        assertTrue(state.interrupted)
        assertTrue(state.notice.orEmpty().contains("Continue"))

        // Back to the finished branch: nothing to repair, nothing to continue, and the other branch keeps its answer.
        runner.switchBranch(chat, edit.id)
        runCurrent()
        assertEquals(listOf(edit.id, "a2"), repo.history(chat).map { it.id })
        assertFalse(runner.states.value.getValue(chat).interrupted)
        assertNull(runner.states.value.getValue(chat).notice)
        assertEquals(1, g.database.messageRows.count { it.role == Role.TOOL.name })
    }

    @Test
    fun `startup recovery answers dangling calls once and offers Continue`() = runTest {
        val g = TestGraph(backgroundScope)
        val repo = g.conversations
        val chat = repo.create(null).id
        repo.appendUserMessage(chat, "run the tests")
        repo.append(assistant(chat, "a1", Part.ToolCall("k1", "run_command", "{}"), Part.ToolCall("k2", "read_file", "{}")))
        val runner = g.runner
        runCurrent()

        val last = repo.history(chat).last()
        assertEquals(listOf("k1", "k2"), last.toolResults.map { it.callId })
        assertTrue(runner.states.value.getValue(chat).interrupted)
    }

    @Test
    fun `a checkpoint after a finished turn is not an interrupted run`() = runTest {
        val g = TestGraph(backgroundScope)
        val repo = g.conversations
        val chat = repo.create(null).id
        repo.appendUserMessage(chat, "hi")
        repo.append(assistant(chat, "a1", Part.Text("hello")))
        repo.append(Message("cp", chat, Role.USER, listOf(Part.Checkpoint("summary", 2)), clock++))
        val runner = g.runner
        runCurrent()
        assertFalse(runner.states.value[chat]?.interrupted == true)
        assertEquals(3, repo.history(chat).size)
    }

    @Test
    fun `a helper cut off with the app is marked interrupted, its calls answered, and never offered Continue`() = runTest {
        val g = TestGraph(backgroundScope)
        val repo = g.conversations
        val parent = repo.create(null).id
        repo.appendUserMessage(parent, "research this")
        repo.append(assistant(parent, "p1", Part.ToolCall("spawn1", dev.locus.core.model.SubagentTools.SPAWN, "{}")))
        repo.createHelper("helper", parent, "spawn1", "researcher", "Researcher #1", null, null, "system")
        repo.append(Message("hu", "helper", Role.USER, listOf(Part.Text("task")), clock++))
        repo.append(assistant("helper", "h1", Part.ToolCall("hk", "web_search", "{}")))
        val runner = g.runner
        runCurrent()

        assertEquals(SubagentStatus.INTERRUPTED, repo.get("helper")?.agentStatus)
        assertEquals("hk", repo.history("helper").last().toolResults.single().callId)
        assertNull(runner.states.value["helper"])
        // The parent's spawn call is answered with which helper it was.
        val spawnResult = repo.history(parent).last().toolResults.single()
        assertEquals("spawn1", spawnResult.callId)
        assertTrue("helper" in spawnResult.content || "Researcher" in spawnResult.content)
        assertTrue(runner.states.value.getValue(parent).interrupted)
    }
}
