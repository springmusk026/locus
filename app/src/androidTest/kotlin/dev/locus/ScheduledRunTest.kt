package dev.locus

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidTest
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.model.TaskRunOutcome
import dev.locus.testing.FakeModel
import dev.locus.testing.Gate
import dev.locus.testing.LocusTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import javax.inject.Inject

/**
 * A scheduled task through WorkManager on a device: the real TaskWorker (built by Hilt's worker
 * factory) runs when WorkManager's test driver says its constraints and period are met, and its
 * outcome is recorded; a worker Android stops is recorded as stopped
 * (https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing).
 */
@HiltAndroidTest
class ScheduledRunTest : LocusTest() {
    @Inject lateinit var tasks: TaskRepository

    /** Creates a task (which schedules its periodic work) and returns its id and its work's id. */
    private fun createTask(title: String): Pair<String, UUID> = runBlocking {
        tasks.create(title, "Summarise the news", intervalMinutes = 60)
        val id = tasks.tasks.first { list -> list.any { it.title == title } }.single { it.title == title }.id
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("task-$id").get().single()
        id to work.id
    }

    private fun outcome(id: String): TaskRunOutcome? = runBlocking { tasks.get(id)?.lastOutcome?.let(TaskRunOutcome::valueOf) }

    @Test
    fun aScheduledRunCompletesAndIsRecorded() {
        FakeModel.script { FakeModel.text("Three headlines today.") }
        val (id, work) = createTask("Morning news")
        // The first period of periodic work is due at once; only its network constraint holds it.
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work)

        waitUntil("the run to be recorded") { outcome(id) != null }
        assertEquals(TaskRunOutcome.DONE, outcome(id))
        val task = runBlocking { tasks.get(id) }!!
        assertEquals("Three headlines today.", task.lastSummary)
        // Its chat holds the run, read-only: one request, no tools needed.
        val chat = runBlocking { conversations.get(task.lastConversationId!!) }!!
        assertTrue(chat.title.startsWith("Scheduled: Morning news"))
        assertEquals(1, FakeModel.requests.size)
    }

    @Test
    fun aRunAndroidStopsIsRecordedAsStopped() {
        val held = Gate()
        FakeModel.script { held.pass(); FakeModel.text("Never sent.") }
        val (id, work) = createTask("Evening news")
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(work)
        held.awaitReached()

        // As when Android stops a worker for its time limit.
        driver.stopRunningWorkWithReason(work, WorkInfo.STOP_REASON_TIMEOUT)
        waitUntil("the stop to be recorded") { outcome(id) != null }
        assertEquals(TaskRunOutcome.STOPPED, outcome(id))
        val summary = runBlocking { tasks.get(id) }!!.lastSummary.orEmpty()
        assertTrue(summary, summary.startsWith("Stopped by Android (time limit for background work)"))
        held.open()
    }
}
