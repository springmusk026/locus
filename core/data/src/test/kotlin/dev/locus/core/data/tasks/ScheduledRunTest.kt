package dev.locus.core.data.tasks

import androidx.work.WorkInfo
import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.model.AppSettings
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A scheduled run records how it ended and notifies, even when Android stops it, and a retried
 * worker never sends the prompt twice.
 */
class ScheduledRunTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private class Fixture(val graph: TestGraph, val run: ScheduledRun) {
        val notified = mutableListOf<Pair<String, String>>()

        /** Completed when a chat request arrives; a reply waits for [release] when it's set. */
        val asked = CompletableDeferred<Unit>()
        var release: CompletableDeferred<Unit>? = null

        fun chatRequests() = graph.requests.count { it.url.host == "llm.test" }

        suspend fun task() = graph.database.scheduledTaskDao.get(TASK)!!

        suspend fun go(workId: String, reason: Int = WorkInfo.STOP_REASON_UNKNOWN) =
            run.run(TASK, workId, runNow = false, stopReason = { reason }, notify = { t, x -> notified += t to x })
    }

    private suspend fun TestScope.fixture(): Fixture {
        val g = TestGraph(backgroundScope, settings)
        g.database.scheduledTaskDao.upsert(ScheduledTaskEntity(TASK, "Weather", "Will it rain?", 60, true, 0, null, null, null))
        val f = Fixture(g, ScheduledRun(g.tasks, g.conversations, g.settingsRepository, g.runner, g.secretResolver, g.httpClient))
        g.http = { request ->
            if (request.url.host == "llm.test") {
                f.asked.complete(Unit)
                f.release?.await()
                respond(ScriptedProvider.text("No rain today."), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        return f
    }

    @Test
    fun `a finished run records its answer, notifies and clears the marker`() = runTest {
        val f = fixture()
        assertEquals(ScheduledRun.Result.DONE, f.go("w1"))
        val task = f.task()
        assertEquals(TaskRunOutcome.DONE.name, task.lastOutcome)
        assertEquals("No rain today.", task.lastSummary)
        assertNull(task.runWorkId)
        // The answer, then the run's task receipt in one line (the scripted provider has no price).
        val (title, text) = f.notified.single()
        assertEquals("Weather", title)
        val (answer, receipt) = text.lines()
        assertEquals("No rain today.", answer)
        assertTrue(receipt.startsWith("Done · ") && receipt.endsWith(" · cost unknown"), receipt)
        val record = f.graph.database.taskRunRows.single()
        assertEquals(task.lastConversationId, record.conversationId)
        assertEquals("done", record.outcome)
        // The receipt is for the user only: nothing of it is in the chat the model reads.
        assertTrue(f.graph.conversations.history(record.conversationId).none { "cost unknown" in it.text })
        // The next period of the same periodic work is a new run.
        f.go("w1")
        assertEquals(2, f.chatRequests())
    }

    @Test
    fun `stopped by Android, the stop is recorded and notified, and the retry doesn't send the prompt again`() = runTest {
        val f = fixture()
        f.release = CompletableDeferred()
        val first = async { runCatching { f.go("w1", WorkInfo.STOP_REASON_QUOTA) } }
        f.asked.await()
        first.cancel()
        first.join()

        val stopped = f.task()
        assertEquals(TaskRunOutcome.STOPPED.name, stopped.lastOutcome)
        assertTrue(stopped.lastSummary!!.startsWith("Stopped by Android (background work quota used up)"), stopped.lastSummary)
        assertEquals(listOf("Weather: stopped"), f.notified.map { it.first })
        assertEquals("w1", stopped.runWorkId)

        // WorkManager retries the stopped worker: it finds its own marker and sends nothing.
        f.release!!.complete(Unit)
        assertEquals(ScheduledRun.Result.SKIPPED, f.go("w1"))
        assertEquals(1, f.chatRequests())
        assertNull(f.task().runWorkId)
        assertEquals(1, f.notified.size)
    }

    @Test
    fun `a retry after the process died records the stop once and never re-sends`() = runTest {
        val f = fixture()
        val chat = f.graph.conversations.create(ScriptedProvider.config.id).id
        f.graph.tasks.markRun(TASK, RunMarker("w1", chat, startedAt = 0))

        assertEquals(ScheduledRun.Result.SKIPPED, f.go("w1"))
        assertEquals(0, f.chatRequests())
        val task = f.task()
        assertEquals(TaskRunOutcome.STOPPED.name, task.lastOutcome)
        assertEquals(chat, task.lastConversationId)
        assertTrue(task.lastSummary!!.contains("Locus was closed"))
        assertNull(task.runWorkId)
        assertEquals(listOf("Weather: stopped"), f.notified.map { it.first })
    }

    @Test
    fun `a run past the budget stops itself and says so`() = runTest {
        val f = fixture()
        f.run.budget = 2.minutes
        f.release = CompletableDeferred()
        assertEquals(ScheduledRun.Result.DONE, f.go("w1"))
        val task = f.task()
        assertEquals(TaskRunOutcome.STOPPED.name, task.lastOutcome)
        assertTrue(task.lastSummary!!.contains("longer than 2 minutes"), task.lastSummary)
        assertNull(task.runWorkId)
        assertEquals(listOf("Weather: stopped"), f.notified.map { it.first })
        // The receipt says it was stopped, not by the user.
        assertEquals("stopped", f.graph.database.taskRunRows.single().outcome)
        assertEquals("it reached the time limit for a scheduled run", f.graph.database.taskRunRows.single().detail)
        assertTrue(f.notified.single().second.lines().last().startsWith("Stopped · "), f.notified.single().second)
    }

    @Test
    fun `a run that fails is recorded as failed`() = runTest {
        val f = fixture()
        f.graph.settingsStore.updateData { it.copy(providers = emptyList(), defaultProviderId = null) }
        assertEquals(ScheduledRun.Result.FAILED, f.go("w1"))
        val task = f.task()
        assertEquals(TaskRunOutcome.FAILED.name, task.lastOutcome)
        assertTrue(task.lastSummary!!.startsWith("Failed:"), task.lastSummary)
        assertEquals(listOf("Weather: failed"), f.notified.map { it.first })
        val record = f.graph.database.taskRunRows.single()
        assertEquals("failed", record.outcome)
        assertEquals("Add a model provider in Settings first.", record.detail)
        assertTrue(f.notified.single().second.lines().last().startsWith("Failed · "), f.notified.single().second)
    }

    @Test
    fun `another run in flight is left alone, a stale one is closed`() = runTest {
        val f = fixture()
        val now = System.currentTimeMillis()
        f.run.clock = { now }
        f.graph.tasks.markRun(TASK, RunMarker("other", "c", startedAt = now - 60_000))
        assertEquals(ScheduledRun.Result.SKIPPED, f.go("w1"))
        assertEquals(0, f.chatRequests())

        f.graph.tasks.markRun(TASK, RunMarker("other", "c", startedAt = now - ScheduledRun.MARKER_STALE.inWholeMilliseconds))
        assertEquals(ScheduledRun.Result.DONE, f.go("w1"))
        assertEquals(1, f.chatRequests())
        assertNull(f.task().runWorkId)
    }

    @Test
    fun `decideStart and stop reasons`() {
        assertEquals(RunStart.Send(null), decideStart(null, "w", 0))
        val mine = RunMarker("w", "c", 0)
        assertEquals(RunStart.AlreadySent(mine), decideStart(mine, "w", Long.MAX_VALUE))
        assertEquals(RunStart.Busy, decideStart(RunMarker("x", "c", 1_000), "w", 2_000))
        val stale = RunMarker("x", "c", 0)
        assertEquals(RunStart.Send(stale), decideStart(stale, "w", ScheduledRun.MARKER_STALE.inWholeMilliseconds))
        assertEquals("network lost", stopReasonText(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY))
        assertEquals("time limit for background work", stopReasonText(WorkInfo.STOP_REASON_TIMEOUT))
        assertEquals("reason unknown", stopReasonText(12345))
        assertTrue(ScheduledRun.RUN_BUDGET < 10.minutes)
    }

    private companion object {
        const val TASK = "t1"
    }
}
