package dev.locus.core.data.notifications

import dev.locus.core.data.EnglishResources
import dev.locus.core.data.R
import dev.locus.core.data.device.reminderLine
import dev.locus.core.data.device.reminderSkeleton
import dev.locus.core.data.integrations.telegramRemoteText
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.agent.ApprovalOrigin
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.data.agent.approvalTitle
import dev.locus.core.data.agent.questionTitle
import dev.locus.core.linux.EngineText
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.toolLabel
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * The notifications' words, rendered from the English strings, read exactly as the English core/data wrote
 * before they followed the app's language (docs/design/localization.md, "Notifications"). Android's settings
 * show the channel names and descriptions; channel ids don't change.
 */
class NotificationEnglishTest {
    private fun en(id: Int, vararg args: Any) = EnglishResources.render(EngineText.Res(id, *args))
    private fun EngineText.en() = EnglishResources.render(this)

    @Test
    fun `channel names and descriptions read as before`() {
        assertEquals("Agent activity", en(R.string.data_notif_channel_status))
        assertEquals("Approval requests", en(R.string.data_notif_channel_approvals))
        assertEquals("Asks before the agent changes files, runs commands or contacts other services.", en(R.string.data_notif_channel_approvals_description))
        assertEquals("Questions", en(R.string.data_notif_channel_questions))
        assertEquals("Questions the agent asks you while it works, answerable from the notification.", en(R.string.data_notif_channel_questions_description))
        assertEquals("Waiting to continue", en(R.string.data_notif_channel_continue))
        assertEquals("When Android won't let a task continue in the background, tap to pick it up.", en(R.string.data_notif_channel_continue_description))
        assertEquals("Finished research", en(R.string.data_notif_channel_finished))
        assertEquals("When a research run you started finishes while Locus is in the background.", en(R.string.data_notif_channel_finished_description))
        assertEquals("Scheduled task results", en(R.string.data_notif_channel_tasks))
        assertEquals("Reminders", en(R.string.data_notif_channel_reminders))
        assertEquals("Reminders you asked Locus to set", en(R.string.data_notif_channel_reminders_description))
        assertEquals("Telegram remote mode", en(R.string.data_notif_channel_telegram))
        assertEquals("Shown while messages to your Telegram bot can start tasks.", en(R.string.data_notif_channel_telegram_description))
        assertEquals("App updates", en(R.string.data_notif_channel_updates))
        assertEquals("A newer Locus is on GitHub (only when the update check is on)", en(R.string.data_notif_channel_updates_description))
    }

    @Test
    fun `buttons and the reply field read as before`() {
        assertEquals(
            listOf("Deny", "Allow", "Stop", "Continue", "Reply", "Your answer"),
            listOf(
                R.string.data_notif_action_deny, R.string.data_notif_action_allow, R.string.data_notif_action_stop,
                R.string.data_notif_action_continue, R.string.data_notif_action_reply, R.string.data_notif_reply_hint,
            ).map { en(it) },
        )
    }

    @Test
    fun `approval and question titles read as before`() {
        val request = ApprovalRequest("a", "c1", "run_command", ToolRisk.EXECUTE, "Run ls", """{"command":"ls"}""")
        val label = { r: ApprovalRequest -> toolLabel(r.toolName, r.argumentsJson, done = false) }
        assertEquals("Run `ls`?", approvalTitle(request, label).en())
        assertEquals("Researcher #2: Run `ls`?", approvalTitle(request.copy(origin = ApprovalOrigin("h", "Researcher #2")), label).en())
        val question = UserQuestion("a", "q1", "Which branch?", emptyList(), multi = false, allowOther = true)
        assertEquals("Locus asks", questionTitle(question).en())
        assertEquals("Researcher #2 asks", questionTitle(question.copy(origin = ApprovalOrigin("h", "Researcher #2"))).en())
    }

    @Test
    fun `background, research, task, Telegram and update lines read as before`() {
        assertEquals("Helper finished", en(R.string.data_notif_continue_helper))
        assertEquals("Your message is waiting", en(R.string.data_notif_continue_queued))
        assertEquals("Tap to continue.", en(R.string.data_notif_continue_text))
        assertEquals("Research finished", en(R.string.data_notif_research_done))
        assertEquals("Research stopped at its budget", en(R.string.data_notif_research_budget))
        assertEquals("Research didn't finish", en(R.string.data_notif_research_failed))
        assertEquals("Weather: failed", en(R.string.data_notif_task_failed, "Weather"))
        assertEquals("Weather: stopped", en(R.string.data_notif_task_stopped, "Weather"))
        assertEquals("Telegram remote mode is on", en(R.string.data_notif_telegram_title))
        assertEquals("Until 18:00. Messages to your bot start tasks (read-only).", telegramRemoteText("18:00", widened = false).en())
        assertEquals("Until 18:00. Messages to your bot start tasks.", telegramRemoteText("18:00", widened = true).en())
        assertEquals("Messages to your bot start tasks (read-only).", telegramRemoteText(null, widened = false).en())
        assertEquals("Turn off", en(R.string.data_notif_telegram_off))
        assertEquals("Update available: 0.3.0", en(R.string.data_notif_update_title, "0.3.0"))
        assertEquals("See what's new in Settings → About & privacy", en(R.string.data_notif_update_text))
    }

    @Test
    fun `a reminder's line reads as before, its time in the phone's way`() {
        assertEquals("Reminder from Locus · 17:00", reminderLine(late = false, time = "17:00").en())
        assertEquals("Late: it was due at 17:00 (the phone was off or asleep)", reminderLine(late = true, time = "17:00").en())
        val zone = ZoneId.of("Europe/Lisbon")
        fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 11, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
        // Today: the time alone; another day: weekday, day and month too. 24-hour or not as the phone says.
        assertEquals("Hm", reminderSkeleton(at(27, 17), at(27, 9), zone, is24Hour = true))
        assertEquals("hm", reminderSkeleton(at(27, 17), at(27, 9), zone, is24Hour = false))
        assertEquals("EEEdMMMHm", reminderSkeleton(at(28, 17), at(27, 9), zone, is24Hour = true))
    }
}
