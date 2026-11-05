package dev.locus.core.data.tasks.triggers

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.Ntfy
import dev.locus.core.agent.integrations.checkNewMail
import dev.locus.core.agent.integrations.mailboxReadable
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerHealth
import dev.locus.core.model.TriggerSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** What one check of a source found. */
sealed interface Poll {
    /** New events (maybe none), and where the source now stands. [checkAgainAt] asks for an extra check then (a calendar entry due before the next periodic one). */
    data class Events(val events: List<IncomingEvent>, val state: TriggerState, val checkAgainAt: Long? = null) : Poll

    /** The source couldn't be checked; the task shows [health] and [message]. */
    data class Problem(val health: TriggerHealth, val message: String) : Poll
}

/** A source Locus checks in the background (calendar, ntfy, mail). Another app's events are pushed in instead. */
fun interface EventSource {
    suspend fun poll(taskId: String, trigger: TaskTrigger, state: TriggerState, now: Long): Poll
}

/** A calendar occurrence, from `CalendarContract.Instances`. */
data class CalendarInstance(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val description: String?,
    val location: String?,
    val calendar: String?,
    val allDay: Boolean,
)

/** The phone's calendar, behind an interface so the calendar source is tested on the JVM. */
interface CalendarReader {
    fun hasPermission(): Boolean

    /** Occurrences overlapping [from, to] (recurring events expanded). Blocking. */
    fun instances(from: Long, to: Long): List<CalendarInstance>

    /** The names of the calendars on the phone, for the filter. */
    fun calendars(): List<String>
}

/**
 * Calendar entries about to start. Each check reads the occurrences around now; an entry that matches
 * the filter is due [TaskTrigger.minutesBefore] before it begins and fires once (its "eventId:begin"
 * key is kept). One due before the next periodic check asks for a check at its time, so a 10-minute
 * warning isn't left to the 15-minute period. All-day entries are left out: they have no start time
 * worth warning about.
 */
class CalendarEvents(private val reader: CalendarReader) : EventSource {
    override suspend fun poll(taskId: String, trigger: TaskTrigger, state: TriggerState, now: Long): Poll {
        if (!reader.hasPermission()) {
            return Poll.Problem(TriggerHealth.NO_PERMISSION, "Locus can't read the calendar, so this task can't see entries coming up.")
        }
        val lead = trigger.minutesBefore * MINUTE
        val instances = try {
            reader.instances(now - LATE, now + lead + LOOKAHEAD)
        } catch (e: SecurityException) {
            return Poll.Problem(TriggerHealth.NO_PERMISSION, "Locus can't read the calendar, so this task can't see entries coming up.")
        }
        val handled = state.handled.toMutableSet()
        val due = mutableListOf<IncomingEvent>()
        var next: Long? = null
        for (i in instances.sortedBy { it.begin }) {
            if (i.allDay) continue
            val key = "${i.eventId}:${i.begin}"
            if (key in handled) continue
            val fireAt = i.begin - lead
            // Too late: the entry began more than LATE ago (Android held the check off for that long).
            if (now > i.begin + LATE) continue
            val event = event(i, now)
            if (TriggerRules.mismatch(trigger, event) != null) continue
            if (fireAt <= now) {
                due += event
                handled += key
            } else {
                next = minOf(next ?: fireAt, fireAt)
            }
        }
        // Keys for entries long past are dropped, so the state stays small.
        val kept = handled.filter { k -> k.substringAfter(':').toLongOrNull()?.let { it > now - KEEP } ?: false }
        return Poll.Events(due, state.copy(handled = kept), next)
    }

    private fun event(i: CalendarInstance, now: Long): IncomingEvent {
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val minutes = (i.begin - now) / MINUTE
        val details = buildString {
            appendLine("Source: calendar entry${i.calendar?.let { " in “$it”" }.orEmpty()}")
            appendLine("Title: ${i.title.ifBlank { "(no title)" }}")
            appendLine("Starts: ${time.format(Date(i.begin))} (" + when {
                minutes > 0 -> "in $minutes min"
                minutes == 0L -> "now"
                else -> "${-minutes} min ago"
            } + ")")
            appendLine("Ends: ${time.format(Date(i.end))}")
            i.location?.takeIf { it.isNotBlank() }?.let { appendLine("Location: ${it.take(300)}") }
            i.description?.takeIf { it.isNotBlank() }?.let { appendLine("Description: ${it.take(2_000)}") }
        }.trim()
        return IncomingEvent(
            source = TriggerSource.CALENDAR,
            key = "${i.eventId}:${i.begin}",
            at = now,
            title = i.title.ifBlank { "(no title)" },
            text = listOfNotNull(i.title, i.location, i.description).joinToString("\n"),
            details = details,
            calendar = i.calendar,
        )
    }

    companion object {
        private const val MINUTE = 60_000L

        /** An entry that began up to this long ago still fires (the check was held off by Doze). */
        const val LATE = 30 * MINUTE

        /** How far past the warning time a check looks, to plan an extra check before the next periodic one. */
        const val LOOKAHEAD = 30 * MINUTE

        private const val KEEP = 2 * 24 * 60 * MINUTE
    }
}

/**
 * Messages on an ntfy topic, polled (`GET <topic>/json?poll=1&since=<last id>`, docs.ntfy.sh/subscribe/api):
 * no open connection, no foreground service. The first check asks for messages since the trigger was set
 * (its cursor is that time in seconds); later ones since the last message id. Only `message` events
 * count. The access token, if any, comes from the vault and goes only to the topic's own server.
 */
class NtfyEvents(
    private val http: HttpClient,
    /** The topic's token from the vault, by task id. */
    private val token: suspend (String) -> String?,
    /** The user's reach-me ntfy topic (where task results go), to refuse a loop. */
    private val reachMe: SecretResolver,
) : EventSource {
    override suspend fun poll(taskId: String, trigger: TaskTrigger, state: TriggerState, now: Long): Poll {
        val url = trigger.topicUrl.trim().trimEnd('/')
        if (trigger.problem != null) return Poll.Problem(TriggerHealth.NOT_SET_UP, "Set the ntfy topic's address in the task.")
        // Results of tasks go to the reach-me topic; a task started by it would start itself again with every result.
        val results = reachMe.resolve(Integrations.ntfy.secret)?.let(Ntfy::parse)?.url?.trimEnd('/')
        if (results != null && results.equals(url, ignoreCase = true)) {
            return Poll.Problem(TriggerHealth.ERROR, "This is the topic Locus sends task results to, so every result would start the task again. Use another topic.")
        }
        val since = state.cursor ?: (now / 1000).toString()
        val body = try {
            val response = http.get("$url/json") {
                parameter("poll", "1")
                parameter("since", since)
                token(taskId)?.let { header("Authorization", "Bearer $it") }
            }
            when (val code = response.status.value) {
                in 200..299 -> response.bodyAsText()
                401, 403 -> return Poll.Problem(TriggerHealth.ERROR, "The ntfy server refused Locus ($code). Check the topic's access token in the task.")
                404 -> return Poll.Problem(TriggerHealth.ERROR, "The ntfy server has no such topic (404). Check the address.")
                429 -> return Poll.Problem(TriggerHealth.OFFLINE, "The ntfy server's rate limit was reached (429); Locus tries again at the next check.")
                else -> return Poll.Problem(TriggerHealth.ERROR, "The ntfy server answered $code.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Poll.Problem(TriggerHealth.OFFLINE, "Couldn't reach ${trigger.topicShort?.substringBefore('/')}: ${e.message?.take(120) ?: "no connection"}.")
        } catch (e: Exception) {
            return Poll.Problem(TriggerHealth.OFFLINE, "Couldn't reach the ntfy server: ${e.message?.take(120) ?: e::class.java.simpleName}.")
        }
        val messages = parse(body)
        val cursor = messages.lastOrNull()?.key ?: since
        return Poll.Events(messages.takeLast(MAX_PER_CHECK), state.copy(cursor = cursor))
    }

    companion object {
        const val MAX_PER_CHECK = 20

        private val json = Json { ignoreUnknownKeys = true }

        /** ntfy's newline-delimited JSON as events: `message` events only, in order; anything unreadable is skipped. */
        fun parse(body: String): List<IncomingEvent> = body.lineSequence().mapNotNull { line ->
            val obj = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            if (obj["event"]?.jsonPrimitive?.contentOrNull != "message") return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { ID.matches(it) } ?: return@mapNotNull null
            val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty().take(4_000)
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.take(250)
            val priority = obj["priority"]?.jsonPrimitive?.intOrNull ?: TriggerRules.NTFY_DEFAULT_PRIORITY
            val tags = runCatching { obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.take(40) } }.getOrNull().orEmpty().take(10)
            val time = (obj["time"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000
            val topic = obj["topic"]?.jsonPrimitive?.contentOrNull?.take(64)
            IncomingEvent(
                source = TriggerSource.NTFY,
                key = id,
                at = time,
                title = title?.takeIf { it.isNotBlank() } ?: message.lineSequence().firstOrNull().orEmpty().take(120),
                text = listOfNotNull(title, message, tags.joinToString(" ")).joinToString("\n"),
                details = buildString {
                    appendLine("Source: ntfy message${topic?.let { " on topic “$it”" }.orEmpty()}")
                    if (time > 0) appendLine("Sent: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))}")
                    title?.takeIf { it.isNotBlank() }?.let { appendLine("Title: $it") }
                    appendLine("Priority: $priority")
                    if (tags.isNotEmpty()) appendLine("Tags: ${tags.joinToString(", ")}")
                    appendLine("Message: $message")
                }.trim(),
                priority = priority,
            )
        }.toList()

        /** ntfy message ids are short alphanumeric strings; anything else is ignored rather than used as a cursor. */
        private val ID = Regex("^[A-Za-z0-9]{1,32}$")
    }
}

/**
 * New mail in the agent's own mailbox (Settings → Integrations → Email), by IMAP UID: one short session
 * per check, read-only, headers only ([checkNewMail]). IMAP IDLE would be quicker but needs a connection
 * kept open, which means a foreground service; see docs/design/event-triggers.md.
 */
class MailEvents(private val secrets: SecretResolver) : EventSource {
    override suspend fun poll(taskId: String, trigger: TaskTrigger, state: TriggerState, now: Long): Poll {
        if (!mailboxReadable(secrets)) {
            return Poll.Problem(TriggerHealth.NOT_SET_UP, "Set up the agent's mailbox with an IMAP server in Settings → Integrations → Email.")
        }
        val (validity, after) = state.cursor?.split(':')?.takeIf { it.size == 2 }?.let { it[0].toLongOrNull() to it[1].toLongOrNull() } ?: (null to null)
        val check = try {
            checkNewMail(secrets, validity, after)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            return Poll.Problem(if ("rejected" in (e.message ?: "")) TriggerHealth.ERROR else TriggerHealth.OFFLINE, e.message ?: "The mail server couldn't be checked.")
        } catch (e: Exception) {
            return Poll.Problem(TriggerHealth.OFFLINE, "The mail server couldn't be checked: ${e.message?.take(120) ?: e::class.java.simpleName}.")
        }
        val events = check.mail.map { m ->
            IncomingEvent(
                source = TriggerSource.MAIL,
                key = m.id,
                at = m.date ?: now,
                title = m.subject.ifBlank { "(no subject)" },
                text = m.subject,
                details = buildString {
                    appendLine("Source: new mail in the agent's mailbox")
                    appendLine("Id: ${m.id} (email_read opens it)")
                    appendLine("From: ${m.from.take(200)}")
                    m.date?.let { appendLine("Received: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}") }
                    appendLine("Subject: ${m.subject.ifBlank { "(no subject)" }}")
                }.trim(),
                from = m.from,
            )
        }
        return Poll.Events(events, state.copy(cursor = "${check.uidValidity}:${check.lastUid}"))
    }
}
