package dev.locus.core.data.tasks

import androidx.work.NetworkType
import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.model.TaskConditions
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A scheduled task's run conditions as WorkManager constraints (docs/design/battery-thermal.md). */
class TaskConstraintsTest {
    @Test fun `the default is a network connection while the battery isn't low`() {
        val s = TaskConstraints.spec(TaskConditions())
        assertEquals(NetworkType.CONNECTED, s.network)
        assertTrue(s.requiresBatteryNotLow)
        assertFalse(s.requiresCharging)
    }

    @Test fun `on Wi-Fi means an unmetered network`() {
        assertEquals(NetworkType.UNMETERED, TaskConstraints.spec(TaskConditions(unmeteredOnly = true)).network)
    }

    @Test fun `charging maps to requiresCharging, and none set leaves only the network`() {
        assertTrue(TaskConstraints.spec(TaskConditions(requiresCharging = true)).requiresCharging)
        val none = TaskConstraints.spec(TaskConditions.LEGACY)
        assertEquals(TaskConstraints.Spec(NetworkType.CONNECTED, requiresCharging = false, requiresBatteryNotLow = false), none)
    }

    @Test fun `every combination round-trips through the spec`() {
        for (charging in listOf(false, true)) for (wifi in listOf(false, true)) for (battery in listOf(false, true)) {
            val s = TaskConstraints.spec(TaskConditions(charging, wifi, battery))
            assertEquals(charging, s.requiresCharging)
            assertEquals(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED, s.network)
            assertEquals(battery, s.requiresBatteryNotLow)
        }
    }

    @Test fun `a task stored before the options existed has none of them`() = runTest {
        val g = TestGraph(backgroundScope)
        g.database.scheduledTaskDao.upsert(ScheduledTaskEntity("t", "Old", "p", 60, true, 0, null, null, null))
        assertEquals(TaskConditions.LEGACY, g.tasks.tasks.first().single().conditions)
    }
}
