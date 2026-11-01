package dev.locus.core.data.agent

import dev.locus.core.database.TaskRunEntity
import dev.locus.core.model.AppSettings
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each task the runner starts gets a record for its receipt: opened when it starts, closed with how it
 * ended. Records are UI only: the next request never carries them.
 */
class TaskRecordTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private fun TestGraph.chatRequests() = requests.filter { it.url.host == "llm.test" }

    private fun HttpRequestData.text(): String = (body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()

    @Test
    fun `a finished task is recorded as done, and its receipt never reaches the model`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.script(ScriptedProvider.text("Hello."), ScriptedProvider.text("Again."))
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        runner.send(chat, "hi")
        runner.states.first { it[chat]?.running == false && g.chatRequests().size == 1 }
        val record = g.database.taskRunRows.single()
        assertEquals("done", record.outcome)
        assertNull(record.detail)
        assertTrue(record.endedAt!! >= record.startedAt)
        val receipt = g.receipts.latest(chat)!!
        assertEquals(1, receipt.steps)
        assertTrue(dev.locus.core.agent.TaskReceipts.summary(receipt).startsWith("Done · "))

        runner.send(chat, "again")
        runner.states.first { it[chat]?.running == false && g.chatRequests().size == 2 }
        assertEquals(listOf("done", "done"), g.database.taskRunRows.map { it.outcome })
        val second = g.chatRequests().last().text()
        assertFalse("Task receipt" in second || "cost unknown" in second || "Done · " in second, second)
    }

    @Test
    fun `Stop records the task as stopped by the user`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        val asked = CompletableDeferred<Unit>()
        g.http = { request ->
            if (request.url.host == "llm.test") {
                asked.complete(Unit)
                CompletableDeferred<Unit>().await() // never answers
                respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        runner.send(chat, "think hard")
        asked.await()
        runner.cancel(chat)
        runner.states.first { it[chat]?.running == false }
        val record = g.database.taskRunRows.single()
        assertEquals("stopped", record.outcome)
        assertNull(record.detail, "no detail: the user stopped it")
    }

    @Test
    fun `a failure is recorded with its reason`() = runTest {
        val g = TestGraph(backgroundScope, settings.copy(providers = emptyList(), defaultProviderId = null))
        val chat = g.conversations.create(null).id
        val runner = g.runner
        runCurrent()

        runner.send(chat, "hi")
        runner.states.first { it[chat]?.running == false && g.database.taskRunRows.firstOrNull()?.outcome != null }
        val record = g.database.taskRunRows.single()
        assertEquals("failed", record.outcome)
        assertEquals("Add a model provider in Settings first.", record.detail)
    }

    @Test
    fun `a summary the user asked for isn't a task`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()
        runner.compactNow(chat)
        runner.states.first { it[chat]?.running == false }
        assertTrue(g.database.taskRunRows.isEmpty())
    }

    @Test
    fun `a task cut off when the app was killed is closed as interrupted at startup`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        g.database.taskRunDao.insert(TaskRunEntity("old", chat, startedAt = 1))
        g.runner // its init recovers
        runCurrent()
        val record = g.database.taskRunRows.single()
        assertEquals("interrupted", record.outcome)
        assertTrue(record.endedAt != null)
    }
}
