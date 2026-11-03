package dev.locus.core.data.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** The daily check's WorkManager schedule, behind an interface so [Updates] runs in JVM tests. */
interface UpdateWork {
    fun schedule()
    fun cancel()
}

/** When the daily check runs: about once a day, only on an unmetered network, not on a low battery. */
object UpdateSchedule {
    const val WORK_NAME = "update-check"
    const val INTERVAL_HOURS = 24L

    /** A few hours of slack, so WorkManager can fold the check into a window when the phone is awake anyway. */
    const val FLEX_HOURS = 4L

    data class Spec(val intervalHours: Long, val flexHours: Long, val network: NetworkType, val batteryNotLow: Boolean)

    val spec = Spec(INTERVAL_HOURS, FLEX_HOURS, NetworkType.UNMETERED, batteryNotLow = true)

    fun constraints(s: Spec = spec): Constraints = Constraints.Builder()
        .setRequiredNetworkType(s.network)
        .setRequiresBatteryNotLow(s.batteryNotLow)
        .build()

    fun request(s: Spec = spec): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<UpdateCheckWorker>(s.intervalHours, TimeUnit.HOURS, s.flexHours, TimeUnit.HOURS)
            .setConstraints(constraints(s))
            .build()
}

internal class WorkManagerUpdateWork(private val context: Context) : UpdateWork {
    // UPDATE keeps the enqueue time, so scheduling at every start doesn't push the next check back
    // (https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy).
    override fun schedule() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UpdateSchedule.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, UpdateSchedule.request())
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UpdateSchedule.WORK_NAME)
    }
}
