package dev.locus.core.data.tasks

import androidx.work.WorkInfo
import dev.locus.core.data.agent.RunNote
import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Taking over a scheduled run (docs/design/scheduled-runs.md): the user sends a message (or taps Stop) in
 * its chat while it works; it stops at the next step boundary with every call answered, is recorded as
 * stopped because the user took over, releases its marker, and the user's message runs once as a normal,
 * interactive turn. Typing alone stops nothing.
 */
class TakeOverTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private class Fixture(val graph: TestGraph, val run: ScheduledRun) {
        val notified = mutableListOf<Pair<String, String>>()

        /** Each reply, in order; the first (the scheduled run's first step) waits for [release]. */
        val replies = ArrayDeque<String>()
        val asked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0

        fun chatRequests() = graph.requests.count { it.url.host == "llm.test" }

        suspend fun task() = graph.database.scheduledTaskDao.get(TASK)!!

        suspend fun go() = run.run(TASK, "w1", runNow = false, stopReason = { WorkInfo.STOP_REASON_UNKNOWN }, notify = { t, x, _ -> notified += t to x })

        suspend fun chat(): String = graph.database.scheduledTaskDao.get(TASK)!!.runConversationId!!
    }

    private suspend fun TestScope.fixture(vararg replies: String): Fixture {
        val g = TestGraph(backgroundScope, settings)
        g.database.scheduledTaskDao.upsert(ScheduledTaskEntity(TASK, "Weather", "Will it rain?", 60, true, 0, null, null, null))
        val f = Fixture(g, ScheduledRun(g.tasks, g.conversations, g.settingsRepository, g.runner, g.secretResolver, g.httpClient, g.secrets))
        f.replies.addAll(replies)
        g.http = { request ->
            if (request.url.host == "llm.test") {
                val first = synchronized(f) { f.calls++ == 0 }
                if (first) {
                    f.asked.complete(Unit)
                    f.release.await()
                }
                val next = synchronized(f) { f.replies.removeFirstOrNull() } ?: error("No scripted reply left")
                respond(next, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        return f
    }

    private suspend fun <T> real(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(20_000) { block() } }

    @Test
    fun `a message stops the run at the step boundary, and it runs once as a normal turn`() = runTest {
        val f = fixture(
            // The scheduled run's first step: a read-only call.
            ScriptedProvider.toolCall("c1", "memory_search", """{"query":"umbrella"}"""),
            // The user's turn: a memory write, which a scheduled (read-only) run would refuse.
            ScriptedProvider.toolCall("c2", "memory_save", """{"topic":"umbrella","summary":"Keeps an umbrella at work","content":"Keeps an umbrella at work."}"""),
            ScriptedProvider.text("Saved it."),
        )
        f.run.onScreen = { true }
        val worker = async { f.go() }
        real { f.asked.await() }
        val chat = f.chat()
        val runner = f.graph.runner

        runner.send(chat, "Never mind the forecast. Remember I keep an umbrella at work.")
        // Waiting for the boundary: the message is queued, and the chat says the run is stopping.
        val stopping = runner.states.value.getValue(chat)
        assertEquals(RunNote.TakingOver, stopping.progressNote)
        real { runner.states.first { it[chat]?.queued?.size == 1 } }

        f.release.complete(Unit)
        assertEquals(ScheduledRun.Result.DONE, real { worker.await() })

        // The run's own call finished (no half call), then it stopped: no second background step.
        val task = f.task()
        assertEquals(TaskRunOutcome.STOPPED.name, task.lastOutcome)
        assertEquals(ScheduledRun.TAKEN_OVER_SUMMARY, task.lastSummary)
        assertNull(task.runWorkId, "the marker is released")
        assertTrue(f.notified.isEmpty(), "the user is in the chat: no notification")

        // The user's message runs as a normal turn: its memory write isn't refused as read-only.
        real { runner.states.first { it[chat]?.running == false && f.chatRequests() == 3 } }
        val saved = f.graph.home.memoryTopics().single()
        assertEquals("umbrella", saved.name)
        val history = f.graph.conversations.history(chat)
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT),
            history.map { it.role },
        )
        // Each sent once: the scheduled prompt and the user's message.
        assertEquals(1, history.count { it.role == Role.USER && it.userText.startsWith("Will it rain?") })
        val mine = history.filter { it.role == Role.USER && "umbrella at work" in it.userText }
        assertEquals(1, mine.size)
        // It carries the note that the chat is a normal one from here.
        assertTrue(mine.single().parts.any { it is Part.Text && ModeNote.isNote(it.text) && "no longer apply" in it.text })
        val toolResults = history.filter { it.role == Role.TOOL }.flatMap { it.toolResults }
        assertFalse(toolResults.single { it.toolName == "memory_save" }.isError, toolResults.toString())
        assertEquals(RunNote.TookOver(message = true), runner.states.value.getValue(chat).noticeNote)
        assertTrue(runner.states.value.getValue(chat).queued.isEmpty())
    }

    @Test
    fun `Stop alone stops the run at the boundary and sends nothing more`() = runTest {
        val f = fixture(
            ScriptedProvider.toolCall("c1", "memory_search", """{"query":"umbrella"}"""),
            ScriptedProvider.text("Unused."),
        )
        f.run.onScreen = { false }
        val worker = async { f.go() }
        real { f.asked.await() }
        val chat = f.chat()
        f.graph.runner.cancel(chat)
        f.release.complete(Unit)
        assertEquals(ScheduledRun.Result.DONE, real { worker.await() })
        assertEquals(1, f.chatRequests())
        val task = f.task()
        assertEquals(TaskRunOutcome.STOPPED.name, task.lastOutcome)
        assertNull(task.runWorkId)
        // Locus wasn't on screen any more: the notification says so.
        assertEquals("Weather: stopped", f.notified.single().first)
        assertTrue(f.notified.single().second.startsWith(ScheduledRun.TAKEN_OVER_SUMMARY))
        val state = f.graph.runner.states.value.getValue(chat)
        assertFalse(state.running)
        assertEquals(RunNote.TookOver(message = false), state.noticeNote)
        // The step's call has its result: the transcript is valid to continue.
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), f.graph.conversations.history(chat).map { it.role })
    }

    @Test
    fun `a reply that arrives after the user took over doesn't run its calls, but answers them`() = runTest {
        val f = fixture(
            ScriptedProvider.toolCall("c1", "memory_search", """{"query":"umbrella"}"""),
            ScriptedProvider.text("Hello."),
        )
        f.run.onScreen = { true }
        val worker = async { f.go() }
        real { f.asked.await() }
        val chat = f.chat()
        // The takeover lands while the first reply is still streaming.
        f.graph.runner.send(chat, "Hi")
        f.release.complete(Unit)
        real { worker.await() }
        real { f.graph.runner.states.first { it[chat]?.running == false && f.chatRequests() == 2 } }
        val result = f.graph.conversations.history(chat).first { it.role == Role.TOOL }.toolResults.single()
        assertTrue(result.isError)
        assertTrue("took over" in result.content, result.content)
    }

    @Test
    fun `without a send the run carries on through its steps`() = runTest {
        val f = fixture(
            ScriptedProvider.toolCall("c1", "memory_search", """{"query":"umbrella"}"""),
            ScriptedProvider.text("No rain today."),
        )
        val worker = async { f.go() }
        real { f.asked.await() }
        val chat = f.chat()
        // Opening the chat and typing a draft touch nothing in the runner: only Send and Stop take over.
        assertNull(f.graph.runner.states.value.getValue(chat).progressNote)
        f.release.complete(Unit)
        assertEquals(ScheduledRun.Result.DONE, real { worker.await() })
        assertEquals(2, f.chatRequests())
        assertEquals(TaskRunOutcome.DONE.name, f.task().lastOutcome)
        assertEquals("No rain today.", f.task().lastSummary)
        assertEquals("Weather", f.notified.single().first)
    }

    private companion object {
        const val TASK = "task-1"
    }
}
