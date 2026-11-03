package dev.locus.core.data.updates

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The daily update check (docs/design/updates.md). One GET to GitHub; a notification only for a
 * version not seen or skipped before. Always succeeds: a failed check waits for the next day.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updates: Updates,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        updates.checkInBackground()
        return Result.success()
    }
}
