package dev.locus.feature.tasks

import dev.locus.core.model.QuietHours
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerSource
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test
import kotlin.test.assertEquals

/** The task editor's draft as the saved state holds it, and the run times "Starting at" lists. */
class TaskSheetStateTest {

    @Test
    fun `a draft comes back from the saved state field for field`() {
        val drafts = listOf(
            TaskDraft(),
            TaskDraft.morningDigest(TaskConditions(requiresCharging = true, unmeteredOnly = true, batteryNotLow = false), "Morning digest"),
            TaskDraft(
                title = "Backup alerts", prompt = "Say what failed.\nAnd why.", intervalMinutes = 90, mode = StartMode.EVENT,
                trigger = TaskTrigger(TriggerSource.NTFY, topicUrl = "https://ntfy.sh/homelab", contains = "backup", minPriority = 4),
                apps = "com.example.a, com.example.b", quietOn = true, quietFrom = "23:00", quietTo = "06:30",
                token = "tk_secret", hasToken = true, clearToken = false, firstRunAt = 7 * 60 + 30,
            ),
        )
        drafts.forEach { draft ->
            val saved = TaskDraftState.toList(draft)
            assertEquals(TaskDraftState.SIZE, saved.size)
            assertEquals(draft, TaskDraftState.fromList(saved))
        }
        // The open sheet too, with what it holds.
        val edit = SheetTarget.Edit("t1", "Backup alerts", drafts[2])
        val restored = with(SheetTargetSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(edit) }!!
        assertEquals(edit, SheetTargetSaver.restore(restored))
        val blank = with(SheetTargetSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(SheetTarget.New()) }!!
        assertEquals(SheetTarget.New(), SheetTargetSaver.restore(blank))
    }

    @Test
    fun `starting at lists the next runs from the chosen time, or from now`() {
        val zone = ZoneId.of("Asia/Kathmandu")
        val now = ZonedDateTime.of(2026, 11, 26, 9, 15, 0, 0, zone)
        // 07:30 has passed today, so every day at 07:30 starts tomorrow.
        assertEquals(
            listOf(27, 28, 29).map { ZonedDateTime.of(2026, 11, it, 7, 30, 0, 0, zone) },
            nextRuns(7 * 60 + 30, 24 * 60, now),
        )
        // Hourly from 18:00 today.
        assertEquals(
            listOf(18, 19, 20).map { ZonedDateTime.of(2026, 11, 26, it, 0, 0, 0, zone) },
            nextRuns(18 * 60, 60, now),
        )
        // No start time: right after saving, then once a period.
        assertEquals(listOf(now, now.plusHours(6), now.plusHours(12)), nextRuns(null, 6 * 60, now))
        assertEquals(QuietHours.clock(10 * 60), "10:00")
        assertEquals(10 * 60, nextWholeHour(now.toInstant().toEpochMilli(), zone))
    }
}
