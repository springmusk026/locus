package dev.locus.core.data.tasks

import android.content.Context

/**
 * What the WorkManager side of tasks needs to remember between runs without a database column: the time of
 * day a daily task runs at, when its next run is planned, and when each trigger's extra check is due. A
 * small preferences file; a restored backup finds it empty, and a task then falls back to its plain period.
 */
internal class TaskTimes(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("task_times", Context.MODE_PRIVATE)

    /** Minutes after local midnight that [taskId] runs at each day, or null for a task on a plain period. */
    fun timeOfDay(taskId: String): Int? = prefs.getInt("at:$taskId", -1).takeIf { it >= 0 }

    /** Epoch milliseconds of [taskId]'s planned next run. */
    fun planned(taskId: String): Long? = prefs.getLong("due:$taskId", -1).takeIf { it >= 0 }

    fun setDaily(taskId: String, minuteOfDay: Int, due: Long) {
        prefs.edit().putInt("at:$taskId", minuteOfDay).putLong("due:$taskId", due).apply()
    }

    fun forgetDaily(taskId: String) {
        prefs.edit().remove("at:$taskId").remove("due:$taskId").apply()
    }

    /** When [taskId]'s waiting extra check is due, epoch milliseconds. */
    fun checkDue(taskId: String): Long? = prefs.getLong("check:$taskId", -1).takeIf { it >= 0 }

    fun setCheckDue(taskId: String, due: Long?) {
        prefs.edit().apply { if (due == null) remove("check:$taskId") else putLong("check:$taskId", due) }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
