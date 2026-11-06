package dev.locus.core.testing

import dev.locus.core.data.device.ReminderAlarms
import dev.locus.core.data.device.ReminderNotifier
import dev.locus.core.model.Reminder

/** AlarmManager for reminders, in memory: the alarm set for each reminder id, and whether exact alarms are allowed. */
class FakeReminderAlarms(var exact: Boolean = false) : ReminderAlarms {
    val scheduled = linkedMapOf<String, Long>()

    override fun exactAllowed(): Boolean = exact

    override fun schedule(reminder: Reminder) {
        scheduled[reminder.id] = reminder.at
    }

    override fun cancel(id: String) {
        scheduled.remove(id)
    }
}

/** Reminder notifications, recorded: what is showing, and whether it said "late". */
class FakeReminderNotifier(var enabled: Boolean = true) : ReminderNotifier {
    val showing = linkedMapOf<String, Boolean>()
    val shown = mutableListOf<Reminder>()

    override fun canNotify(): Boolean = enabled

    override fun show(reminder: Reminder, late: Boolean) {
        showing[reminder.id] = late
        shown += reminder
    }

    override fun dismiss(id: String) {
        showing.remove(id)
    }
}
