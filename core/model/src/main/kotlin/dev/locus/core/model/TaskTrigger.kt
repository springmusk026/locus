package dev.locus.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a task's events come from (docs/design/event-triggers.md). Each is something Locus can watch
 * without an accessibility service and without reading another app's data.
 */
@Serializable
enum class TriggerSource(val label: String, val noun: String) {
    /** An entry in the phone's calendar is about to start (`CalendarContract.Instances`, READ_CALENDAR). */
    CALENDAR("Calendar entry", "calendar entry"),

    /** A message on an ntfy topic (ntfy.sh or the user's own server), which anything that can POST a URL can send to. */
    NTFY("ntfy message", "ntfy message"),

    /** New mail in the agent's own mailbox (Settings → Integrations → Email), checked over IMAP. */
    MAIL("New mail", "mail"),

    /** Another app (Tasker, Automate, a script) sent Locus's `dev.locus.action.EVENT` intent. */
    APP("Another app", "event from another app"),
}

/** Hours in which a trigger doesn't start runs: [startMinute] to [endMinute] (minutes after midnight), across midnight when start > end. */
@Serializable
data class QuietHours(val startMinute: Int = 22 * 60, val endMinute: Int = 7 * 60) {
    /** Whether [minuteOfDay] (0 until 1440) falls inside. Equal ends mean no quiet time. */
    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    /** "22:00–07:00". */
    val label: String get() = "${clock(startMinute)}–${clock(endMinute)}"

    companion object {
        fun clock(minute: Int): String = "%02d:%02d".format(minute / 60 % 24, minute % 60)

        /** "7", "07", "7:30", "07:30" or "0730". */
        private val TIME = Regex("^(\\d{1,2})(?::?(\\d{2}))?$")

        /** "7:30", "07:30" or "730" as minutes after midnight; null when it isn't a time. */
        fun parse(text: String): Int? {
            val m = TIME.matchEntire(text.trim()) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            return if (h in 0..23 && min in 0..59) h * 60 + min else null
        }
    }
}

/**
 * What starts an event-triggered task: a [source], a filter on what it sends, a rate limit and quiet
 * hours. Fields a source doesn't use are ignored. The ntfy access token isn't here: it is kept in the
 * vault (see docs/design/event-triggers.md), so it is never shown, exported in the clear or sent to a model.
 */
@Serializable
data class TaskTrigger(
    val source: TriggerSource,
    /** Words that must all appear (any case) in the event's text: a calendar entry's title, an ntfy message, a mail's subject. Empty matches everything. */
    val contains: String = "",
    /** Calendar: only entries in the calendar with this name (any case); empty for every calendar. */
    val calendar: String = "",
    /** Calendar: how long before an entry starts the task runs. */
    val minutesBefore: Int = 10,
    /** ntfy: the topic's address, `https://ntfy.sh/my-topic` or one on the user's own server. */
    val topicUrl: String = "",
    /** ntfy: the lowest message priority that counts (1 min … 5 max; 1 is every message). */
    val minPriority: Int = 1,
    /** Mail: text the sender's name or address must contain; empty for anyone. */
    val from: String = "",
    /** Another app: the event name it sends in `dev.locus.extra.EVENT`; empty takes any. */
    val eventName: String = "",
    /** Another app: the packages that may fire this trigger. Android must name the caller for it to match. */
    val allowedApps: List<String> = emptyList(),
    /** At most one run every this many minutes; events in between are recorded as skipped. */
    val minGapMinutes: Int = DEFAULT_GAP,
    /** When set, events in these hours are recorded but start nothing. */
    val quietHours: QuietHours? = null,
) {
    /** What the trigger waits for, in a few words: "When an ntfy message arrives on ntfy.sh/alerts". */
    val summary: String
        get() = when (source) {
            TriggerSource.CALENDAR -> buildString {
                append(if (minutesBefore <= 0) "When a calendar entry starts" else "$minutesBefore min before a calendar entry")
                if (contains.isNotBlank()) append(" with “${contains.trim()}”")
                if (calendar.isNotBlank()) append(" in ${calendar.trim()}")
            }
            TriggerSource.NTFY -> buildString {
                append("When an ntfy message arrives")
                topicShort?.let { append(" on $it") }
                if (contains.isNotBlank()) append(" with “${contains.trim()}”")
            }
            TriggerSource.MAIL -> buildString {
                append("When mail arrives")
                if (from.isNotBlank()) append(" from “${from.trim()}”")
                if (contains.isNotBlank()) append(" about “${contains.trim()}”")
            }
            TriggerSource.APP -> buildString {
                append("When another app sends")
                append(if (eventName.isNotBlank()) " “${eventName.trim()}”" else " an event")
            }
        }

    /** The topic without its scheme: "ntfy.sh/alerts". */
    val topicShort: String? get() = topicUrl.trim().removePrefix("https://").removePrefix("http://").trimEnd('/').takeIf { it.isNotBlank() }

    /** Whether the source needs Locus to check it in the background (everything but another app, which sends its events in). */
    val polled: Boolean get() = source != TriggerSource.APP

    /** Why this trigger can't be saved, or null when it can. */
    val problem: String?
        get() = when {
            source == TriggerSource.NTFY && !NTFY_TOPIC.matches(topicUrl.trim().trimEnd('/')) ->
                "Enter the topic's address, like https://ntfy.sh/a-long-secret-name."
            source == TriggerSource.CALENDAR && minutesBefore !in 0..MAX_MINUTES_BEFORE -> "Pick how long before an entry starts."
            source == TriggerSource.APP && allowedApps.any { !PACKAGE.matches(it) } -> "“${allowedApps.first { !PACKAGE.matches(it) }}” isn't a package name."
            minGapMinutes !in MIN_GAP..MAX_GAP -> "The limit must be between $MIN_GAP minute and ${MAX_GAP / 60} hours."
            else -> null
        }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_GAP = 15
        const val MIN_GAP = 1
        const val MAX_GAP = 24 * 60
        const val MAX_MINUTES_BEFORE = 24 * 60

        /** The choices the task sheet offers for [minGapMinutes]. */
        val GAPS: List<Int> = listOf(5, 15, 60, 360)

        /** The choices for [minutesBefore]. */
        val LEADS: List<Int> = listOf(0, 10, 30, 60)

        private val NTFY_TOPIC = Regex("^https?://[^/\\s]+(/[^/\\s]+)*/[-_A-Za-z0-9]{1,64}$")
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** A stored trigger, or null for a task on a schedule (or a value a newer version wrote that this one can't read). */
        fun fromJson(value: String?): TaskTrigger? = value?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        /** "a,b c" as package names, in order, without duplicates. */
        fun parseApps(text: String): List<String> = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

/** What happened to an event: whether it started a run, and if not, why. Stored by name. */
enum class TriggerDecision(val label: String) {
    /** Matched and started a run. */
    FIRED("Ran"),

    /** "Send a test event" matched and started a run. */
    TEST("Test: ran"),

    /** Didn't match the filter. */
    NO_MATCH("Didn't match"),

    /** Within the rate limit after the previous run. */
    RATE_LIMITED("Rate-limited"),

    /** In the trigger's quiet hours. */
    QUIET_HOURS("Quiet hours"),

    /** The task is paused. */
    DISABLED("Task paused"),

    /** Another run of the task was still going. */
    BUSY("Already running"),

    /** The app that sent it isn't on the trigger's list (or Android didn't name it). */
    NOT_ALLOWED("App not allowed"),

    /** Locus couldn't start the run. */
    FAILED("Couldn't run"),
    ;

    /** Whether the event started (or tried to start) a run. */
    val ran: Boolean get() = this == FIRED || this == TEST
}

/** How a trigger's source last answered (docs/design/event-triggers.md, "States"). */
enum class TriggerHealth {
    /** Not checked yet. */
    WAITING,

    /** The last check worked. */
    OK,

    /** Locus lacks a permission it needs (the calendar). */
    NO_PERMISSION,

    /** Something the source needs isn't set up (the mailbox, the topic). */
    NOT_SET_UP,

    /** The server couldn't be reached. */
    OFFLINE,

    /** The server answered with an error (a wrong token, a missing topic). */
    ERROR,
}

/** One event a trigger received, for the task's history. */
data class TriggerEventRecord(
    val id: String,
    val taskId: String,
    val source: TriggerSource,
    val receivedAt: Long,
    /** A short line saying what the event was ("ntfy: Backup failed"); never a secret. */
    val title: String,
    val decision: TriggerDecision,
    /** Why it was skipped, or how its run ended. */
    val detail: String?,
    /** The chat its run happened in. */
    val conversationId: String?,
    /** How the run ended, once it has. */
    val outcome: TaskRunOutcome?,
)
