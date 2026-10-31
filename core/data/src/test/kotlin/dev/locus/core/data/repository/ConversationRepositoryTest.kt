package dev.locus.core.data.repository

import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Effort
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ConversationRepository over the in-memory database: titles, branches, forks and per-chat settings. */
class ConversationRepositoryTest {
    private fun TestScope.graph(settings: AppSettings = AppSettings()) = TestGraph(backgroundScope, settings)

    private var clock = 1_000L
    private fun assistant(chat: String, id: String, vararg parts: Part) = Message(id, chat, Role.ASSISTANT, parts.toList(), clock++)
    private fun tool(chat: String, id: String, vararg results: Part.ToolResult) = Message(id, chat, Role.TOOL, results.toList(), clock++)

    @Test
    fun `the first message names the chat, later ones don't`() = runTest {
        val repo = graph().conversations
        val chat = repo.create(null)
        assertEquals("New chat", chat.title)
        repo.appendUserMessage(chat.id, "Plan a trip to Lisbon\nwith a budget of 900 euros")
        assertEquals("Plan a trip to Lisbon", repo.get(chat.id)?.title)
        repo.appendUserMessage(chat.id, "Something else")
        assertEquals("Plan a trip to Lisbon", repo.get(chat.id)?.title)

        val long = repo.create(null)
        repo.appendUserMessage(long.id, "x".repeat(200))
        assertEquals(60, repo.get(long.id)?.title?.length)

        val fileOnly = repo.create(null)
        repo.appendUserMessage(fileOnly.id, "", attachments = listOf(Part.Attachment("report.pdf", "application/pdf", "/data/uploads/report.pdf", "uploads/report.pdf", 10)))
        assertEquals("report.pdf", repo.get(fileOnly.id)?.title)
    }

    @Test
    fun `a new chat freezes the custom instructions and recent chat titles into its prompt`() = runTest {
        val repo = graph(AppSettings(customInstructions = "Answer in French.")).conversations
        val old = repo.create(null)
        repo.appendUserMessage(old.id, "Tax return checklist")
        repo.create(null) // still "New chat": left out
        val chat = repo.create(null)
        assertTrue("Answer in French." in chat.systemPrompt)
        assertTrue("Tax return checklist" in chat.systemPrompt)
        assertFalse("“New chat”" in chat.systemPrompt)
    }

    @Test
    fun `a mode note rides on the message for the model but not in the user's text`() = runTest {
        val repo = graph().conversations
        val chat = repo.create(null)
        val m = repo.appendUserMessage(chat.id, "Refactor the parser", modeNote = "PLAN MODE")
        assertEquals(2, m.parts.size)
        assertEquals("Refactor the parser", m.userText)
        assertEquals("Refactor the parser", repo.get(chat.id)?.title)
    }

    @Test
    fun `editing a message adds a sibling branch and keeps the original`() = runTest {
        val repo = graph().conversations
        val chat = repo.create(null).id
        val first = repo.appendUserMessage(chat, "first question")
        repo.append(assistant(chat, "a1", Part.Text("first answer")))
        val second = repo.appendUserMessage(chat, "second question")
        repo.append(assistant(chat, "a2", Part.Text("second answer")))

        val edited = repo.appendUserMessage(chat, "second question, rephrased", replacing = second.id)
        val thread = repo.thread(chat)
        assertEquals(listOf(first.id, "a1", edited.id), thread.messages.map { it.id })
        assertEquals(listOf(second.id, edited.id), thread.branches.getValue(edited.id).siblings)
        assertEquals(1, thread.branches.getValue(edited.id).index)

        // The original branch is still there and can be shown again, ending at its newest message.
        assertTrue(repo.switchBranch(chat, second.id))
        assertEquals(listOf(first.id, "a1", second.id, "a2"), repo.history(chat).map { it.id })
        assertEquals("a2", repo.activeLeaf(chat))
    }

    @Test
    fun `switching to a message from another chat does nothing`() = runTest {
        val repo = graph().conversations
        val a = repo.create(null).id
        val b = repo.create(null).id
        repo.appendUserMessage(a, "in a")
        val inB = repo.appendUserMessage(b, "in b")
        assertFalse(repo.switchBranch(a, inB.id))
        assertFalse(repo.switchBranch(a, "missing"))
        assertEquals(listOf("in a"), repo.history(a).map { it.userText })
    }

    @Test
    fun `regenerating from an inner message moves the leaf without deleting anything`() = runTest {
        val repo = graph().conversations
        val chat = repo.create(null).id
        val u = repo.appendUserMessage(chat, "q")
        repo.append(assistant(chat, "old", Part.Text("old reply")))
        repo.setActiveLeaf(chat, u.id)
        assertEquals(listOf(u.id), repo.history(chat).map { it.id })
        repo.append(assistant(chat, "new", Part.Text("new reply")))

        val thread = repo.thread(chat)
        assertEquals(listOf(u.id, "new"), thread.messages.map { it.id })
        assertEquals(listOf("old", "new"), thread.branches.getValue("new").siblings)
    }

    @Test
    fun `a fork copies the shown branch up to a message, with fresh ids and its source recorded`() = runTest {
        val g = graph()
        val repo = g.conversations
        val chat = repo.create(null).id
        repo.setEffort(chat, Effort.LOW)
        repo.setPlanMode(chat, true)
        val u1 = repo.appendUserMessage(chat, "Write a haiku about autumn leaves falling slowly")
        repo.append(assistant(chat, "a1", Part.Text("Leaves drift...")))
        val u2 = repo.appendUserMessage(chat, "Now one about winter")
        repo.append(assistant(chat, "a2", Part.Text("Snow...")))

        val upToReply = assertNotNull(repo.fork(chat, "a1", inclusive = true))
        assertEquals(listOf("Write a haiku about autumn leaves falling slowly", "Leaves drift..."), repo.history(upToReply.id).map { it.text })
        assertTrue(upToReply.title.endsWith(" (fork)"))
        assertEquals(Effort.LOW, upToReply.effort)
        assertTrue(upToReply.planMode)
        assertEquals(repo.get(chat)?.systemPrompt, upToReply.systemPrompt)
        val copies = g.database.messageRows.filter { it.conversationId == upToReply.id }
        assertEquals(listOf(u1.id, "a1"), copies.map { it.copiedFrom })
        assertTrue(copies.none { it.id == u1.id || it.id == "a1" })

        // Before a user message: everything up to it, not the message itself.
        val beforeU2 = assertNotNull(repo.fork(chat, u2.id, inclusive = false))
        assertEquals(2, repo.history(beforeU2.id).size)

        // The source is untouched.
        assertEquals(4, repo.history(chat).size)
    }

    @Test
    fun `forking at a message that isn't on the shown branch fails`() = runTest {
        val repo = graph().conversations
        val chat = repo.create(null).id
        val u = repo.appendUserMessage(chat, "q")
        repo.append(assistant(chat, "old", Part.Text("old")))
        repo.setActiveLeaf(chat, u.id)
        repo.append(assistant(chat, "new", Part.Text("new")))
        assertNull(repo.fork(chat, "old", inclusive = true))
        assertNull(repo.fork("no-such-chat", "new", inclusive = true))
    }

    @Test
    fun `a fork taken mid-step answers the calls that have no result`() = runTest {
        val repo = graph().conversations
        val chat = repo.create(null).id
        repo.appendUserMessage(chat, "list the files")
        repo.append(assistant(chat, "a1", Part.Text("Looking."), Part.ToolCall("k1", "list_files", "{}"), Part.ToolCall("k2", "read_file", "{}")))

        val fork = assertNotNull(repo.fork(chat, "a1", inclusive = true))
        val history = repo.history(fork.id)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), history.map { it.role })
        assertEquals(listOf("k1", "k2"), history.last().toolResults.map { it.callId })
        assertTrue(history.last().toolResults.all { it.isError })
        // Answered calls in the copy keep the copy's own call ids.
        assertEquals(history[1].toolCalls.map { it.id }, history.last().toolResults.map { it.callId })
    }

    @Test
    fun `per-chat settings round-trip through the database`() = runTest {
        val repo = graph().conversations
        val chat = repo.create("p").id
        repo.setModel(chat, "q", "  ")
        assertEquals("q", repo.get(chat)?.providerId)
        assertNull(repo.get(chat)?.modelOverride)
        repo.setModel(chat, "q", " big-model ")
        assertEquals("big-model", repo.get(chat)?.modelOverride)

        repo.setDisabledTools(chat, setOf("group:web", "connector:github"))
        assertEquals(setOf("group:web", "connector:github"), repo.get(chat)?.disabledTools)
        repo.setDisabledTools(chat, emptySet())
        assertEquals(emptySet(), repo.get(chat)?.disabledTools)

        repo.setEffort(chat, Effort.MAX)
        assertEquals(Effort.MAX, repo.get(chat)?.effort)
        repo.setEffort(chat, null)
        assertNull(repo.get(chat)?.effort)
    }

    @Test
    fun `empty chats and helper chats are kept out of the user's list`() = runTest {
        val repo = graph().conversations
        val used = repo.create(null).id
        repo.appendUserMessage(used, "hello")
        val empty = repo.create(null).id
        repo.createHelper("h", used, "call", "researcher", "Researcher #1", null, null, "system")

        assertEquals(setOf(used, empty), repo.all.first().map { it.id }.toSet())
        assertEquals(setOf(empty), repo.emptyIds.first())
        assertEquals(setOf(used, empty, "h"), repo.allWithHelpers.first().map { it.id }.toSet())
        assertEquals("h", repo.helperByCall("call")?.id)
        assertEquals(1, repo.countHelpers(used, "researcher"))

        repo.delete(used)
        assertNull(repo.get(used))
        assertTrue(repo.history(used).isEmpty())
    }

    @Test
    fun `audit entries are stored and read back newest first`() = runTest {
        val repo = graph().conversations
        fun entry(id: String, at: Long) = AuditEntry(id, "c", "k$id", "read_file", "{}", ApprovalDecision.AUTO_ALLOWED, "ok", false, at, at + 1)
        repo.record(entry("1", 10))
        repo.record(entry("2", 20))
        assertEquals(listOf("2", "1"), repo.observeAudit().first().map { it.id })
    }

    @Test
    fun `search finds a chat once, through its newest matching message`() = runTest {
        val repo = graph().conversations
        val chat = repo.create(null).id
        repo.appendUserMessage(chat, "lisbon flights")
        repo.append(assistant(chat, "a1", Part.Text("Flights to Lisbon start at 80 euros.")))
        val other = repo.create(null).id
        repo.appendUserMessage(other, "porto")

        val hits = repo.search("lisbon")
        assertEquals(listOf(chat), hits.map { it.conversation.id })
        assertNotEquals("porto", hits.single().conversation.title)
    }
}
