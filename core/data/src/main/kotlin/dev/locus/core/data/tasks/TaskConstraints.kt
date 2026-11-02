package dev.locus.core.data.tasks

import androidx.work.Constraints
import androidx.work.NetworkType
import dev.locus.core.model.TaskConditions

/**
 * A scheduled task's run conditions as WorkManager constraints. Every run needs a network (the model
 * is usually remote); "on Wi-Fi" narrows that to an unmetered one. WorkManager holds the run until all
 * of them are met, and stops a running one that loses them, to retry later (docs/design/battery-thermal.md).
 */
object TaskConstraints {
    /** The pure part, unit-tested: which network, charging and battery constraints [conditions] means. */
    data class Spec(val network: NetworkType, val requiresCharging: Boolean, val requiresBatteryNotLow: Boolean)

    fun spec(conditions: TaskConditions): Spec = Spec(
        network = if (conditions.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
        requiresCharging = conditions.requiresCharging,
        // While charging the battery is filling anyway; WorkManager would still wait for "not low", which
        // is what the user asked for, so keep it as set.
        requiresBatteryNotLow = conditions.batteryNotLow,
    )

    fun of(conditions: TaskConditions): Constraints = spec(conditions).let { s ->
        Constraints.Builder()
            .setRequiredNetworkType(s.network)
            .setRequiresCharging(s.requiresCharging)
            .setRequiresBatteryNotLow(s.requiresBatteryNotLow)
            .build()
    }

    /** "Run now": the user asked for it, so only a network is needed. */
    fun runNow(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
