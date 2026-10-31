package dev.locus.core.data.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Minimal phone actions through standard Android intents. Each one opens the phone's own app
 * (Calendar, Clock, Mail, Messages, Phone, Maps…) with the details filled in, and the user confirms
 * there. Locus never reads or operates other apps.
 */
fun deviceTools(context: Context): List<Tool> {
    val launcher = IntentLauncher(context)
    return listOf(
        CalendarEvent(launcher), Alarm(launcher), Timer(launcher), Email(launcher), Sms(launcher),
        Dial(launcher), Directions(launcher), OpenUrl(launcher), OpenApp(launcher, context), Share(launcher),
    )
}

internal class IntentLauncher(private val context: Context) {
    suspend fun launch(intent: Intent, what: String): ToolResult = withContext(Dispatchers.Main) {
        // Android blocks starting other apps' screens while Locus is in the background.
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return@withContext ToolResult.error("Locus is in the background, so it can't open another app now. Ask the user to open Locus and try again.")
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult("Opened $what with the details filled in. The user confirms it there; nothing is saved or sent until they do.")
        } catch (e: ActivityNotFoundException) {
            ToolResult.error("No app on this phone can handle that ($what).")
        }
    }
}

private abstract class DeviceTool(protected val launcher: IntentLauncher) : Tool {
    override val risk = ToolRisk.WRITE
    // The user confirms in the other app, so asking here too would be a double prompt.
    override val defaultPolicy = ToolPolicy.ALLOW
}

private fun parseTime(value: String?, field: String): Long {
    val text = value?.trim() ?: throw ToolException("Missing $field.")
    return try {
        LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        throw ToolException("$field must be local date-time like 2026-11-02T15:30.")
    }
}

private class CalendarEvent(launcher: IntentLauncher) : DeviceTool(launcher) {
    override val spec = ToolSpec(
        "device_calendar_event",
        "Open the phone's calendar with a new event filled in for the user to save. Times are local, ISO format (2026-11-02T15:30).",
        ObjectSchema()
            .string("title", "Event title.")
            .string("start", "Start, e.g. 2026-11-02T15:30.")
            .string("end", "End; defaults to one hour after start.", required = false)
            .string("location", "Where.", required = false)
            .string("notes", "Description.", required = false)
            .boolean("all_day", "All-day event.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Calendar: ${args.str("title")} at ${args.str("start")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val start = parseTime(args.str("start"), "start")
        val end = args.str("end")?.let { parseTime(it, "end") } ?: (start + 3_600_000)
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, args.str("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, args.bool("all_day") == true)
        args.str("location")?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        args.str("notes")?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        return launcher.launch(intent, "the calendar")
    }
}

private class Alarm(launcher: IntentLauncher) : DeviceTool(launcher) {
    override val spec = ToolSpec(
        "device_alarm",
        "Open the Clock app to set an alarm.",
        ObjectSchema()
            .integer("hour", "0–23.", required = true)
            .integer("minute", "0–59.", required = true)
            .string("label", "Alarm label.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Alarm at ${args.int("hour")}:${"%02d".format(args.int("minute") ?: 0)}"

    override suspend fun execute(args: JsonObject): ToolResult = launcher.launch(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, (args.int("hour") ?: 0).coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, (args.int("minute") ?: 0).coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label").orEmpty())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
        "the clock",
    )
}

private class Timer(launcher: IntentLauncher) : DeviceTool(launcher) {
    override val spec = ToolSpec(
        "device_timer",
        "Open the Clock app with a timer.",
        ObjectSchema().integer("seconds", "Length in seconds.", required = true).string("label", "Label.", required = false).build(),
    )

    override fun describe(args: JsonObject) = "Timer for ${args.int("seconds")}s"

    override suspend fun execute(args: JsonObject): ToolResult = launcher.launch(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, (args.int("seconds") ?: 60).coerceIn(1, 86_400))
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label").orEmpty())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
        "the clock",
    )
}

private class Email(launcher: IntentLauncher) : DeviceTool(launcher) {
    override val spec = ToolSpec(
        "device_email",
        "Open the user's email app with a draft. The user reviews and sends it.",
        ObjectSchema()
            .string("to", "Recipient address(es), comma-separated.")
            .string("subject", "Subject.", required = false)
            .string("body", "Body text.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Email draft to ${args.str("to")}"

    override suspend fun execute(args: JsonObject): ToolResult = launcher.launch(
        Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
            .putExtra(Intent.EXTRA_EMAIL, args.str("to").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
            .putExtra(Intent.EXTRA_SUBJECT, args.str("subject").orEmpty())
            .putExtra(Intent.EXTRA_TEXT, args.str("body").orEmpty()),
        "the email app",
    )
}

private class Sms(launcher: IntentLauncher) : DeviceTool(launcher) {
    override val spec = ToolSpec(
        "device_sms",
        "Open the messaging app with a text message draft. The user reviews and sends it.",
        ObjectSchema().string("to", "Phone number.").string("body", "Message.").build(),
    )

    override fun describe(args: JsonObject) = "SMS draft to ${args.str("to")}"

    override suspend fun execute(args: JsonObject): ToolResult = launcher.launch(
        Intent(Intent.ACTION_SENDTO, ("smsto:" + Uri.encode(args.str("to").orEmpty())).toUri()).putExtra("sms_body", args.str("body").orEmpty()),
        "messages",
    )
}

private class Dial(launcher: IntentLauncher) : DeviceTool(launcher) {
    override val spec = ToolSpec(
        "device_dial",
        "Open the phone dialer with a number entered. The user presses call.",
        ObjectSchema().string("number", "Phone number.").build(),
    )

    override fun describe(args: JsonObject) = "Dialer: ${args.str("number")}"

    override suspend fun execute(args: JsonObject): ToolResult =
        launcher.launch(Intent(Intent.ACTION_DIAL, ("tel:" + Uri.encode(args.str("number").orEmpty())).toUri()), "the dialer")
}

private class Directions(launcher: IntentLauncher) : DeviceTool(launcher) {
    override val spec = ToolSpec(
        "device_directions",
        "Open the maps app at a place or address.",
        ObjectSchema().string("place", "Address or place name.").build(),
    )

    override fun describe(args: JsonObject) = "Map: ${args.str("place")}"

    override suspend fun execute(args: JsonObject): ToolResult =
        launcher.launch(Intent(Intent.ACTION_VIEW, ("geo:0,0?q=" + Uri.encode(args.str("place").orEmpty())).toUri()), "maps")
}

private class OpenUrl(launcher: IntentLauncher) : DeviceTool(launcher) {
    override val spec = ToolSpec(
        "device_open_url",
        "Open a link in the user's own browser or the app that handles it (for the user to look at; use browser_* to browse yourself).",
        ObjectSchema().string("url", "http(s) URL.").build(),
    )

    override fun describe(args: JsonObject) = "Open ${args.str("url")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val url = args.str("url").orEmpty()
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw ToolException("Only http(s) links.")
        return launcher.launch(Intent(Intent.ACTION_VIEW, url.toUri()), url)
    }
}

private class OpenApp(launcher: IntentLauncher, private val context: Context) : DeviceTool(launcher) {
    override val spec = ToolSpec(
        "device_open_app",
        "Open an installed app by name, e.g. \"Spotify\". Locus can't see or operate it afterwards.",
        ObjectSchema().string("name", "App name.").build(),
    )

    override fun describe(args: JsonObject) = "Open ${args.str("name")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val pm = context.packageManager
        val query = args.str("name").orEmpty().trim().lowercase()
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == query }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(query) }
            ?: throw ToolException("No installed app called “${args.str("name")}”.")
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: throw ToolException("That app can't be opened directly.")
        return launcher.launch(intent, match.loadLabel(pm).toString())
    }
}

private class Share(launcher: IntentLauncher) : DeviceTool(launcher) {
    override val spec = ToolSpec(
        "device_share",
        "Open the share sheet with some text so the user can send it to any app.",
        ObjectSchema().string("text", "Text to share.").build(),
    )

    override fun describe(args: JsonObject) = "Share text"

    override suspend fun execute(args: JsonObject): ToolResult = launcher.launch(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, args.str("text").orEmpty()), null),
        "the share sheet",
    )
}
