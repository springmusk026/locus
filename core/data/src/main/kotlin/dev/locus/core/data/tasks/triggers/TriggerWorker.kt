package dev.locus.core.data.tasks.triggers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Checks one task's trigger source (docs/design/event-triggers.md): every 15 minutes, plus extra checks
 * a calendar entry or a save asks for. A check is one HTTP request, one short IMAP session or one
 * calendar query; it never runs the agent itself. Matches are enqueued as [dev.locus.core.data.tasks.TaskWorker]
 * runs, which the phone's heat and the task's conditions can hold.
 */
@HiltWorker
class TriggerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sources: TriggerSources,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        sources.checker.check(taskId)
        // A failed check is recorded on the task and tried again at the next period; no backoff retries.
        return Result.success()
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}
