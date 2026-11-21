package dev.locus.feature.chat

import dev.locus.core.data.repository.RewindFile
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Part
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rewind here and Summarise from here end to end (docs/design/rewind.md), over the real runner, change tracking
 * and a scripted provider: files go back (a file changed since stays), the chat branches with the old
 * continuation reachable, the agent is told; a summary of the tail is reviewed, edited and stored append-only, and
 * the next request keeps the prefix before the point.
 */
class RewindFlowTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
        toolPolicies = mapOf("write_file" to ToolPolicy.ALLOW, "edit_file" to ToolPolicy.ALLOW),
    )

    private fun TestGraph.replies(vararg bodies: String) {
        val queue = ArrayDeque(bodies.toList())
        http = { request ->
            if (request.url.host == "llm.test") {
                val next = synchronized(queue) { queue.removeFirstOrNull() } ?: error("No scripted reply left")
                respond(next, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private fun TestGraph.bodies(): List<String> =
        requests.filter { it.url.host == "llm.test" }.map { (it.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty() }

    /** A request's messages, each as JSON text. */
    private fun messagesOf(body: String): List<String> = (Json.parseToJsonElement(body).jsonObject["messages"] as JsonArray).map { it.toString() }

    private class Fixture(val graph: TestGraph, val chat: String, val vm: ChatViewModel)

    private suspend fun TestScope.fixture(): Fixture {
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        val chat = g.conversations.create(settings.defaultProviderId).id
        val vm = host.add(ChatViewModel(chat, g.conversations, g.settingsRepository, g.directory, g.runner, g.browser, g.workspaceRepository, g.library, g.workspace, g.onDevice, g.sentRequests, g.sideQuestions, g.changes, g.compute, g.receipts))
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.rewindPreview.collect {} }
        runCurrent()
        return Fixture(g, chat, vm)
    }

    private suspend fun ChatViewModel.state(until: (ChatUiState) -> Boolean) = uiState.first { !it.loading && until(it) }

    private fun call(id: String, tool: String, args: String) = ScriptedProvider.toolCall(id, tool, args)

    @Test
    fun `rewind puts later turns' files back, leaves a file changed since, branches and tells the agent`() = runTest {
        val f = fixture()
        val ws = f.graph.workspace
        f.graph.replies(
            call("c1", "write_file", """{"path":"a.txt","content":"one\n"}"""), ScriptedProvider.text("Wrote a."),
            call("c2", "read_file", """{"path":"a.txt"}"""), call("c3", "write_file", """{"path":"a.txt","content":"two\n"}"""),
            call("c4", "write_file", """{"path":"b.txt","content":"agent\n"}"""), ScriptedProvider.text("Changed a, wrote b."),
            call("c5", "write_file", """{"path":"c.txt","content":"later\n"}"""), ScriptedProvider.text("Wrote c."),
            ScriptedProvider.text("Did it differently."),
        )
        f.vm.send("write a")
        f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.Assistant } == 1 }
        f.vm.send("change a")
        f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.Assistant } == 2 }
        f.vm.send("more")
        val before = f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.Assistant } == 3 && it.items.count { i -> i is ChatItem.Changes } == 3 }
        // Someone changes b.txt after the agent left it.
        ws.resolve("b.txt").writeText("mine\n")

        val second = before.items.filterIsInstance<ChatItem.User>()[1]
        assertNull(before.messageActions.getValue(second.key).rewind)
        assertEquals(dev.locus.core.agent.Rewind.Unavailable.FIRST_MESSAGE, before.messageActions.getValue(before.items.filterIsInstance<ChatItem.User>()[0].key).rewind)
        f.vm.prepareRewind(second.key)
        val preview = f.vm.rewindPreview.first { it != null }!!
        assertTrue(preview.userMessage)
        assertEquals("change a", preview.draft)
        assertEquals(
            listOf(
                Triple("a.txt", TrackedChange.Kind.MODIFIED, RewindFile.State.RESTORE),
                Triple("b.txt", TrackedChange.Kind.CREATED, RewindFile.State.CHANGED_SINCE),
                Triple("c.txt", TrackedChange.Kind.CREATED, RewindFile.State.RESTORE),
            ),
            preview.files.map { Triple(it.path, it.kind, it.state) },
        )
        // Looking changed nothing.
        assertEquals("two\n", ws.resolve("a.txt").readText())

        var draft: String? = null
        f.vm.confirmRewind { draft = it }
        val rewound = f.vm.state { it.laterBranch != null && it.items.count { i -> i is ChatItem.User } == 1 }
        assertEquals("change a", draft)
        assertNull(f.vm.rewindPreview.value)
        // Files: the later turns' changes are gone, newest first; b.txt, changed since, is left alone.
        assertEquals("one\n", ws.resolve("a.txt").readText())
        assertEquals("mine\n", ws.resolve("b.txt").readText())
        assertFalse(ws.resolve("c.txt").exists())
        assertTrue(ws.trashEntries().any { it.originalPath == "c.txt" })
        // The chat continues from the first turn's reply; the notice says what happened.
        assertEquals("Wrote a.", (rewound.items.last { it is ChatItem.Assistant } as ChatItem.Assistant).text)
        assertEquals(dev.locus.core.data.agent.RunNote.Rewound(2, 1), rewound.run.noticeNote)
        // Nothing was deleted: the old continuation is still stored.
        val stored = f.graph.database.messageDao.tree(f.chat).map { it.message.id }
        assertTrue(second.key in stored)

        // The user's next message starts the new branch; the agent is told before that request.
        f.vm.send("change a, but differently")
        val after = f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.User } == 2 && it.items.count { i -> i is ChatItem.Assistant } == 2 }
        val newUser = after.items.filterIsInstance<ChatItem.User>()[1]
        assertEquals("change a, but differently", newUser.text)
        // The old message is its sibling, one switch away.
        assertEquals(listOf(second.key, newUser.key), assertNotNull(newUser.branch).siblings)
        assertNull(after.laterBranch)
        val body = f.graph.bodies().last()
        assertTrue("The user rewound the chat to this point." in body, body)
        assertTrue("put back a.txt, c.txt as they were" in body, body)
        assertTrue("left as they are now, not as they were at this point: b.txt" in body, body)
        // The request continues the first turn's request: the earlier messages are a prefix of it.
        val firstTurn = messagesOf(f.graph.bodies()[1])
        val next = messagesOf(body)
        assertEquals(firstTurn, next.take(firstTurn.size))

        // Switching back shows the old continuation, as it was.
        f.vm.switchBranch(second.key)
        val old = f.vm.state { it.items.count { i -> i is ChatItem.Assistant } == 3 }
        assertEquals("Wrote c.", (old.items.last { it is ChatItem.Assistant } as ChatItem.Assistant).text)
    }

    @Test
    fun `summarise from here drafts a summary of the tail, the user edits it, and the next request keeps the prefix`() = runTest {
        val f = fixture()
        val summary = "## Done so far\nFound the retry off-by-one in retry.ts and fixed it; tests pass."
        f.graph.replies(
            ScriptedProvider.text("Design: POST /charges with idempotency keys."),
            ScriptedProvider.text("Debugging… fixed the retry."),
            ScriptedProvider.text("All tests pass now."),
            ScriptedProvider.text(summary),
            ScriptedProvider.text("Refunds next, then."),
        )
        f.vm.send("design the payments API")
        f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.Assistant } == 1 }
        f.vm.send("the test fails, debug it")
        f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.Assistant } == 2 }
        f.vm.send("run them all")
        val s = f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.Assistant } == 3 }
        val users = s.items.filterIsInstance<ChatItem.User>()
        val replies = s.items.filterIsInstance<ChatItem.Assistant>()
        // The menus: from the second message there are four messages to summarise; the last reply has nothing after it.
        assertEquals(MessageActions(summariseCount = 4), s.messageActions.getValue(users[1].key))
        assertEquals(SummariseUnavailable.NOTHING_AFTER, s.messageActions.getValue(replies.last().key).summarise)
        assertEquals(MessageActions(summariseCount = 4), s.messageActions.getValue(replies[0].key))
        val lastTurn = f.graph.bodies().last()
        val stored = f.graph.conversations.history(f.chat)

        f.vm.summariseFrom(users[1].key, "keep the API decisions, drop the debugging")
        val drafted = f.vm.state { !it.run.running && it.run.summaryDraft != null }
        val draft = assertNotNull(drafted.run.summaryDraft)
        assertEquals(summary, draft.text)
        assertEquals(4, draft.replaced)
        // Nothing is stored until the user uses it.
        assertEquals(stored, f.graph.conversations.history(f.chat))
        // The summary request is the next turn's prefix plus one question, with the scope and the instructions.
        val ask = f.graph.bodies().last()
        val askMessages = messagesOf(ask)
        val lastMessages = messagesOf(lastTurn)
        assertEquals(lastMessages, askMessages.take(lastMessages.size))
        assertTrue("<part_start>\\nthe test fails, debug it\\n</part_start>" in ask, ask)
        assertTrue("keep the API decisions, drop the debugging" in ask, ask)

        f.vm.useSummary("## Done so far\nRetry fixed. Keep idempotency keys on every charge.")
        val used = f.vm.state { it.run.summaryDraft == null && it.items.any { i -> i is ChatItem.Checkpoint } }
        val card = used.items.filterIsInstance<ChatItem.Checkpoint>().single()
        assertTrue(card.partial)
        assertEquals(card.key, used.editableCheckpoint)
        // Append-only: everything stored before is still there, in order, and the checkpoint was added after it.
        val now = f.graph.conversations.history(f.chat)
        assertEquals(stored, now.dropLast(1))
        assertEquals(users[1].key, now.last().parts.filterIsInstance<Part.Checkpoint>().single().from)

        // Edited again before the model reads it: a new checkpoint supersedes it, the old one stays stored.
        f.vm.editSummary(card.key, "## Done so far\nRetry fixed (retry.ts:14). Keep idempotency keys.")
        val edited = f.vm.state { st -> st.items.filterIsInstance<ChatItem.Checkpoint>().singleOrNull()?.edited == true }
        assertEquals(1, edited.items.count { it is ChatItem.Checkpoint })
        assertEquals(now, f.graph.conversations.history(f.chat).dropLast(1))

        f.vm.send("now add refunds")
        val answered = f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.Assistant } == 4 }
        val next = f.graph.bodies().last()
        assertTrue("Retry fixed (retry.ts:14). Keep idempotency keys." in next, next)
        assertFalse("Retry fixed. Keep idempotency keys on every charge." in next)
        assertFalse("Debugging… fixed the retry." in next, "the summarised replies aren't sent")
        // The messages before the point go out exactly as the last turn sent them: system prompt, first message, first reply.
        assertEquals(lastMessages.take(3), messagesOf(next).take(3))
        assertTrue(messagesOf(next)[3].contains("Summary of the rest of this chat from here on"))
        // After the model has read it, the summary can't be edited any more.
        assertNull(answered.editableCheckpoint)
    }

    @Test
    fun `a draft that no longer fits the chat isn't used, and discarding stores nothing`() = runTest {
        val f = fixture()
        f.graph.replies(
            ScriptedProvider.text("One."), ScriptedProvider.text("Two."),
            ScriptedProvider.text("A summary of the second part of the chat, long enough."),
            ScriptedProvider.text("Three."),
            ScriptedProvider.text("Another summary of the second part, long enough too."),
        )
        f.vm.send("first")
        f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.Assistant } == 1 }
        f.vm.send("second")
        val s = f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.Assistant } == 2 }
        val second = s.items.filterIsInstance<ChatItem.User>()[1].key
        f.vm.summariseFrom(second, "")
        f.vm.state { !it.run.running && it.run.summaryDraft != null }
        f.vm.discardSummary()
        f.vm.state { it.run.summaryDraft == null }
        assertTrue(f.graph.conversations.history(f.chat).none { m -> m.parts.any { it is Part.Checkpoint } })

        // A new turn drops a draft: it was written for a chat that has moved on.
        f.vm.send("third")
        f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.Assistant } == 3 }
        f.vm.summariseFrom(second, "")
        f.vm.state { !it.run.running && it.run.summaryDraft != null }
        f.graph.conversations.appendUserMessage(f.chat, "typed elsewhere")
        f.vm.useSummary("An edited summary that no longer fits.")
        val stale = f.vm.state { it.run.summaryDraft == null && it.run.errorNote != null }
        assertEquals(dev.locus.core.data.agent.RunNote.SummaryStale, stale.run.errorNote)
        assertTrue(f.graph.conversations.history(f.chat).none { m -> m.parts.any { it is Part.Checkpoint } })
    }
}
