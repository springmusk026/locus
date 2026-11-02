package dev.locus.core.data.agent

import dev.locus.core.data.integrations.TelegramRemoteHub
import dev.locus.core.model.AppSettings
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.TaskOutcome
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whoever started a run (Telegram remote, the intent API) hears how that run ended, with that run's
 * own answer. The Telegram chat is reused for every remote task: a task that ends without a reply
 * must never come back with the previous task's answer.
 */
class RunResultTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private val refusal = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\ndata: [DONE]\n\n"

    @Test
    fun `a finished run returns its own answer`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.script(ScriptedProvider.text("First answer."), ScriptedProvider.text("Second answer."))
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        val first = runner.awaitRun(runner.sendFromApp(chat, "one", "[note]")!!)!!
        assertEquals(TaskOutcome.DONE, first.outcome)
        assertEquals("First answer.", first.answer)
        assertNull(first.endLine)
        runner.states.first { it[chat]?.running == false }
        val second = runner.awaitRun(runner.sendFromApp(chat, "two", "[note]")!!)!!
        assertEquals("Second answer.", second.answer)
        assertEquals("Second answer.", TelegramRemoteHub.remoteReply(second))
    }

    @Test
    fun `a refused run after an answered one never returns the old answer`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.script(ScriptedProvider.text("Old answer."), refusal)
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        assertEquals("Old answer.", runner.awaitRun(runner.sendFromApp(chat, "one", "[note]")!!)!!.answer)
        runner.states.first { it[chat]?.running == false }
        val run = runner.awaitRun(runner.sendFromApp(chat, "two", "[note]")!!)!!
        assertEquals(TaskOutcome.REFUSED, run.outcome)
        assertNull(run.answer)
        assertFalse("Old answer." in TelegramRemoteHub.remoteReply(run), TelegramRemoteHub.remoteReply(run))
        assertTrue(TelegramRemoteHub.remoteReply(run).contains("declined"))
    }

    @Test
    fun `a failed run after an answered one reports its error, not the old answer`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        var calls = 0
        g.http = { request ->
            if (request.url.host == "llm.test") {
                if (++calls == 1) {
                    respond(ScriptedProvider.text("Old answer."), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                } else {
                    respond("{\"error\":{\"message\":\"bad request\"}}", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        runner.awaitRun(runner.sendFromApp(chat, "one", "[note]")!!)
        runner.states.first { it[chat]?.running == false }
        val run = runner.awaitRun(runner.sendFromApp(chat, "two", "[note]")!!)!!
        assertEquals(TaskOutcome.FAILED, run.outcome)
        assertNull(run.answer)
        assertNotNull(run.error)
        val reply = TelegramRemoteHub.remoteReply(run)
        assertFalse("Old answer." in reply, reply)
        assertTrue(reply.startsWith("The task stopped. Failed:"), reply)
    }

    @Test
    fun `a stopped run after an answered one says it stopped`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        val asked = CompletableDeferred<Unit>()
        var calls = 0
        g.http = { request ->
            if (request.url.host == "llm.test") {
                if (++calls == 1) {
                    respond(ScriptedProvider.text("Old answer."), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                } else {
                    asked.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    respond("", HttpStatusCode.OK)
                }
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        runner.awaitRun(runner.sendFromApp(chat, "one", "[note]")!!)
        runner.states.first { it[chat]?.running == false }
        val id = runner.sendFromApp(chat, "two", "[note]")!!
        asked.await()
        runner.cancel(chat)
        val run = runner.awaitRun(id)!!
        assertEquals(TaskOutcome.STOPPED, run.outcome)
        assertNull(run.answer)
        assertEquals("The task stopped. Stopped in Locus.", TelegramRemoteHub.remoteReply(run))
    }

    @Test
    fun `a run stopped at its budget says so, not an earlier answer`() = runTest {
        val g = TestGraph(backgroundScope, settings.copy(taskBudget = TaskBudget(tokens = 1)))
        g.script(
            ScriptedProvider.text("Old answer."),
            // A tool call that uses tokens, so the budget is spent at the next step boundary.
            ScriptedProvider.toolCall("call-1", "no_such_tool", "{}").removeSuffix("data: [DONE]\n\n") +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n",
            ScriptedProvider.text("never sent"),
        )
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        // The first task is over budget at once too, but it ends with its answer before any boundary.
        runner.awaitRun(runner.sendFromApp(chat, "one", "[note]")!!)
        runner.states.first { it[chat]?.running == false }
        val id = runner.sendFromApp(chat, "two", "[note]")!!
        runner.states.first { it[chat]?.budgetPause != null }
        runner.answerBudget(chat, continueTask = false)
        val run = runner.awaitRun(id)!!
        assertEquals(TaskOutcome.BUDGET, run.outcome)
        assertNull(run.answer)
        val reply = TelegramRemoteHub.remoteReply(run)
        assertFalse("Old answer." in reply, reply)
        assertTrue(reply.startsWith("The task stopped. Stopped:"), reply)
    }

    @Test
    fun `every outcome has its own line`() {
        assertNull(BackgroundRun("Hi.", null, TaskOutcome.DONE).endLine)
        assertEquals("Hi.", BackgroundRun("Hi.", null, TaskOutcome.DONE).answer)
        TaskOutcome.entries.filter { it != TaskOutcome.DONE }.forEach { outcome ->
            val run = BackgroundRun("A mid-task reply.", if (outcome == TaskOutcome.FAILED) "boom" else null, outcome, "detail")
            assertNull(run.answer, "$outcome")
            assertNotNull(run.endLine, "$outcome")
            assertFalse("A mid-task reply." in TelegramRemoteHub.remoteReply(run), "$outcome")
        }
        assertEquals("Stopped at the step limit (after 40 steps).", BackgroundRun(null, null, TaskOutcome.STEP_LIMIT, "after 40 steps").endLine)
    }
}
