package dev.locus.core.data.device

import dev.locus.core.data.agent.NotificationIntents
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the reminder and agent notifications register and say, checked from the module's own files so it runs on
 * the plain JVM (as EnglishResources does): the receiver listens for time changes, the buttons are translated,
 * and notifications' intents don't share a PendingIntent when their hash request codes collide.
 */
class ReminderBroadcastsTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `the reminder receiver is told when the time zone or the clock changes`() {
        val receiver = manifest.substringAfter("android:name=\".device.ReminderReceiver\"").substringBefore("</receiver>")
        for (action in listOf("android.intent.action.TIMEZONE_CHANGED", "android.intent.action.TIME_SET", "android.intent.action.BOOT_COMPLETED")) {
            assertTrue("android:name=\"$action\"" in receiver, "$action is registered")
        }
        // And it sets the alarms again for them.
        val source = File("src/main/kotlin/dev/locus/core/data/device/AndroidReminders.kt").readText()
        val handled = source.substringAfter("Intent.ACTION_BOOT_COMPLETED").substringBefore("reminders.rescheduleAll()")
        assertTrue("Intent.ACTION_TIMEZONE_CHANGED" in handled && "Intent.ACTION_TIME_CHANGED" in handled)
    }

    @Test
    fun `the notification buttons are translated, not English text in the code`() {
        val source = File("src/main/kotlin/dev/locus/core/data/device/AndroidReminders.kt").readText()
        assertTrue("\"Snooze 10 min\"" !in source && "\"Done\"" !in source)
        val english = value("values", "data_reminder_snooze")
        assertEquals("Snooze 10 min", english)
        for (folder in listOf("values-es", "values-pt", "values-b+zh+Hans")) {
            for (name in listOf("data_reminder_snooze", "data_reminder_done")) {
                assertNotEquals(value("values", name), value(folder, name), "$name in $folder")
            }
        }
    }

    @Test
    fun `agent notification buttons with colliding request codes have different intents`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertEquals(("Aa" + true).hashCode(), ("BB" + true).hashCode())
        assertNotEquals(NotificationIntents.uri("approval", "Aa", "true"), NotificationIntents.uri("approval", "BB", "true"))
        assertNotEquals(NotificationIntents.uri("approval", "Aa", "true"), NotificationIntents.uri("approval", "Aa", "false"))
        assertNotEquals(NotificationIntents.uri("question", "x", "1"), NotificationIntents.uri("reply", "x"))
        // An id with a slash or space can't make two different ids look alike.
        assertNotEquals(NotificationIntents.uri("approval", "a/b", "c"), NotificationIntents.uri("approval", "a", "b/c"))
    }

    private fun value(folder: String, name: String): String =
        Regex("<string name=\"$name\">(.*?)</string>").find(File("src/main/res/$folder/strings.xml").readText())?.groupValues?.get(1)
            ?: error("$name missing in $folder")
}
