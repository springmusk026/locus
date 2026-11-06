package dev.locus.core.data.repository

import dev.locus.core.agent.ChatPolicy
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.UsageSource
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Temporary chats end to end (docs/design/temporary-chats.md): what one freezes at creation, that it
 * declares the same tools as a normal chat (its cached prefix keeps the same shape) while refusing memory
 * by policy, that nothing it does reaches memory, and when and how it's deleted, keeping only its cost.
 */
class TemporaryChatsTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false,
        // Reflection on, so the test shows a temporary chat skips it.
        reflectAfterTasks = true,
        toolPolicies = mapOf("write_file" to ToolPolicy.ALLOW, "memory_save" to ToolPolicy.ALLOW),
    )
    private val provider = ScriptedProvider.config.id

    private fun TestScope.graph() = TestGraph(backgroundScope, settings)

    /** Sends [text], waits for the final reply, and returns the chat's tool results. */
    private suspend fun TestGraph.turn(chat: String, text: String): List<Part.ToolResult> {
        val before = conversations.history(chat).count { it.role == Role.ASSISTANT && it.parts.none { p -> p is Part.ToolCall } }
        runner.send(chat, text)
        conversations.observeMessages(chat).first { m -> m.count { it.role == Role.ASSISTANT && it.parts.none { p -> p is Part.ToolCall } } > before }
        return conversations.history(chat).flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
    }

    private fun TestGraph.lastBody(): JsonObject {
        val body = requests.last { it.url.host == "llm.test" }.body as OutgoingContent.ByteArrayContent
        return Json.parseToJsonElement(body.bytes().decodeToString()).jsonObject
    }

    @Test
    fun `a temporary chat is hidden and its prompt has no memory or other chats`() = runTest {
        val g = graph()
        g.home.saveMemory("coffee", "Likes flat whites", "Flat white, no sugar.")
        g.script(ScriptedProvider.text("ok"))
        val normal = g.conversations.create(provider)
        g.turn(normal.id, "Plan a trip to Lisbon")
        g.conversations.rename(normal.id, "Lisbon trip")

        val temp = g.conversations.create(provider, temporary = true)
        assertTrue(temp.temporary && temp.hidden && temp.parentConversationId == null)
        assertFalse(normal.temporary)
        // Not in the chat list (nor, through it, search, shortcuts and recent chats).
        assertTrue(g.conversations.allOnce().none { it.id == temp.id })
        // The prompt: no memory index, no recent chats, and the line that says it's temporary.
        assertTrue("<memory_index>" in normal.systemPrompt && "Likes flat whites" in normal.systemPrompt)
        assertFalse("<memory_index>" in temp.systemPrompt)
        assertFalse("Likes flat whites" in temp.systemPrompt)
        assertFalse("Lisbon trip" in temp.systemPrompt)
        assertTrue(ChatPolicy.TEMPORARY_PROMPT_NOTE in temp.systemPrompt)
        assertFalse(ChatPolicy.TEMPORARY_PROMPT_NOTE in g.conversations.create(provider).systemPrompt)
    }

    @Test
    fun `a temporary chat declares exactly the tools a normal chat does`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.text("one"), ScriptedProvider.text("two"))
        val normal = g.conversations.create(provider)
        val temp = g.conversations.create(provider, temporary = true)
        assertEquals(g.tools.forChat(normal).specs, g.tools.forChat(temp).specs)
        assertTrue(g.tools.forChat(temp).specs.any { it.name == "memory_save" }, "memory tools are refused, not removed")

        g.turn(normal.id, "hi")
        val normalTools = g.lastBody()["tools"]
        g.turn(temp.id, "hi")
        val tempTools = g.lastBody()["tools"]
        assertNotNull(tempTools)
        assertEquals(normalTools, tempTools)
    }

    @Test
    fun `memory and past-chat tools are refused and nothing is saved`() = runTest {
        val g = graph()
        val temp = g.conversations.create(provider, temporary = true).id
        g.script(
            // Each refused call is followed by one that runs: three refused steps in a row would stop the
            // task (ToolLoopGuard), which is right for a model that ignores refusals but not what this tests.
            ScriptedProvider.toolCall("c1", "memory_save", """{"topic":"secret plan","summary":"s","content":"c"}"""),
            ScriptedProvider.toolCall("c4", "write_file", """{"path":"draft.md","content":"kept"}"""),
            ScriptedProvider.toolCall("c2", "memory_search", """{"query":"coffee"}"""),
            ScriptedProvider.toolCall("c5", "write_file", """{"path":"b.md","content":"b"}"""),
            ScriptedProvider.toolCall("c3", "chat_history_search", """{"query":"Lisbon"}"""),
            ScriptedProvider.toolCall("c6", "write_file", """{"path":"c.md","content":"c"}"""),
            ScriptedProvider.toolCall("c7", "write_file", """{"path":"d.md","content":"d"}"""),
            ScriptedProvider.toolCall("c8", "write_file", """{"path":"e.md","content":"e"}"""),
            ScriptedProvider.toolCall("c9", "write_file", """{"path":"f.md","content":"f"}"""),
            ScriptedProvider.text("done"),
            // What the after-task reflection would get, were it to run: six tools that ran are enough to start it.
            ScriptedProvider.toolCall("r1", "memory_save", """{"topic":"lesson","summary":"s","content":"c"}"""),
            ScriptedProvider.text("saved"),
        )
        val results = g.turn(temp, "go")
        runCurrent()
        // Ten requests for the task, none for a reflection.
        assertEquals(10, g.requests.count { it.url.host == "llm.test" })
        listOf("c1", "c2", "c3").forEach { id ->
            val r = results.single { it.callId == id }
            assertTrue(r.isError && "temporary chat" in r.content, r.content)
        }
        assertTrue(g.home.memoryTopics().isEmpty())
        // Files still go to the workspace.
        assertFalse(results.single { it.callId == "c4" }.isError)
        assertEquals("kept", File(g.workspace.base, "draft.md").readText())
        // The policy itself: only for temporary chats, only for these tools.
        assertNull(ChatPolicy.temporaryDenial("memory_save", temporary = false))
        assertNull(ChatPolicy.temporaryDenial("write_file", temporary = true))
    }

    @Test
    fun `a temporary chat can't be moved into a project`() = runTest {
        val g = graph()
        val project = assertNotNull(g.projects.create("Garden"))
        val temp = g.conversations.create(provider, temporary = true)
        assertFalse(g.conversations.moveToProject(temp.id, project.id))
        assertNull(g.conversations.get(temp.id)!!.projectId)
        // Asked to start in a project, it starts outside it.
        assertNull(g.conversations.create(provider, project.id, temporary = true).projectId)
    }

    @Test
    fun `leaving deletes it but keeps what it cost, and normal chats are never touched`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.text("ok", promptTokens = 120, completionTokens = 30), ScriptedProvider.text("fine"))
        val temp = g.conversations.create(provider, temporary = true).id
        val normal = g.conversations.create(provider).id
        g.turn(temp, "hi")
        g.turn(normal, "hi")
        g.temporaryChats.opened(temp)
        g.temporaryChats.opened(normal)

        g.temporaryChats.left(normal)
        g.temporaryChats.left(temp)
        runCurrent()
        assertNull(g.conversations.get(temp))
        assertTrue(g.database.messageRows.none { it.conversationId == temp })
        assertNotNull(g.conversations.get(normal))
        // The reply's tokens stay, linked to no chat, so Insights and the spend still count them.
        val kept = g.database.usageEventRows.single { it.conversationId == null }
        assertEquals(UsageSource.CHAT.wire, kept.source)
        assertTrue("\"input\":120" in kept.usageJson.orEmpty(), kept.usageJson)
        assertEquals(ScriptedProvider.config.model, kept.model)
        // Not a temporary chat: delete refuses it.
        assertFalse(g.temporaryChats.delete(normal))
    }

    @Test
    fun `a chat still open on another screen isn't deleted`() = runTest {
        val g = graph()
        val temp = g.conversations.create(provider, temporary = true).id
        g.temporaryChats.opened(temp)
        g.temporaryChats.opened(temp)
        g.temporaryChats.left(temp)
        runCurrent()
        assertNotNull(g.conversations.get(temp))
        g.temporaryChats.left(temp)
        runCurrent()
        assertNull(g.conversations.get(temp))
    }

    @Test
    fun `a task still running finishes first, and is stopped after 24 hours`() = runTest {
        val g = graph()
        // The model answers only when the test says so: the task stays running until then.
        val release = CompletableDeferred<Unit>()
        g.http = { request ->
            if (request.url.host == "llm.test") {
                release.await()
                respond(ScriptedProvider.text("late"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        val first = g.conversations.create(provider, temporary = true).id
        g.temporaryChats.opened(first)
        g.runner.send(first, "slow")
        g.runner.states.first { it[first]?.running == true }
        g.temporaryChats.left(first)
        runCurrent()
        assertNotNull(g.conversations.get(first), "kept while its task runs")
        release.complete(Unit)
        g.runner.states.first { it[first]?.running != true }
        runCurrent()
        assertNull(g.conversations.get(first), "deleted once the task ended")

        // A task that never ends: stopped and deleted once the limit (a day in the app) has passed since the
        // chat was left. Shortened here: on virtual time the model's stream would time out (5 minutes) first.
        g.temporaryChats.runningLimitMs = 10_000
        g.http = { request ->
            if (request.url.host == "llm.test") CompletableDeferred<Unit>().await()
            respond("", HttpStatusCode.NotFound)
        }
        val second = g.conversations.create(provider, temporary = true).id
        g.temporaryChats.opened(second)
        g.runner.send(second, "forever")
        g.runner.states.first { it[second]?.running == true }
        g.temporaryChats.left(second)
        advanceTimeBy(9_000)
        runCurrent()
        assertNotNull(g.conversations.get(second))
        assertEquals(true, g.runner.states.value[second]?.running)
        advanceTimeBy(2_000)
        g.runner.states.first { it[second]?.running != true }
        runCurrent()
        assertNull(g.conversations.get(second))
    }

    @Test
    fun `start-up sweeps leftover temporary chats but not open ones`() = runTest {
        val g = graph()
        val leftover = g.conversations.create(provider, temporary = true).id
        val restored = g.conversations.create(provider, temporary = true).id
        val normal = g.conversations.create(provider).id
        g.temporaryChats.opened(restored)
        assertEquals(1, g.temporaryChats.sweep())
        assertNull(g.conversations.get(leftover))
        assertNotNull(g.conversations.get(restored))
        assertNotNull(g.conversations.get(normal))
    }

    @Test
    fun `a copy of a temporary chat is temporary too`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.text("ok"))
        val temp = g.conversations.create(provider, temporary = true).id
        g.turn(temp, "hi")
        val copy = assertNotNull(g.conversations.fork(temp, g.conversations.history(temp).last().id, inclusive = true))
        assertTrue(copy.temporary)
    }
}
