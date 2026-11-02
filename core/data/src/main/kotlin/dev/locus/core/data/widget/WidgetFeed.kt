package dev.locus.core.data.widget

import dev.locus.core.agent.SecretScrubber
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.toolActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** How the widget's status reads at a glance; the widget picks the dot's colour from it. */
enum class WidgetTone { IDLE, WORKING, WAITING }

/**
 * The home-screen widget's status line (docs/design/android-bridge.md): "Working: running a command",
 * "1 approval waiting", "Idle". [chatId] is the chat a tap opens: the one waiting for the user, else the
 * one working. A tap only opens Locus; nothing is ever answered from the widget.
 */
data class WidgetStatus(val text: String, val tone: WidgetTone, val chatId: String? = null) {
    companion object {
        val IDLE = WidgetStatus("Idle", WidgetTone.IDLE)
    }
}

/** Everything the widget shows: the status line and the latest scheduled-task result (one line, cleaned), if any. */
data class WidgetModel(val status: WidgetStatus = WidgetStatus.IDLE, val task: String? = null)

/** The parts of the latest scheduled run the widget shows, before cleaning. */
data class LatestTaskRun(val title: String, val summary: String?, val outcome: TaskRunOutcome?, val at: Long)

/**
 * The widget's status from every chat's [RunState]. Pure, so the wording is unit-tested.
 *
 * Only labels Locus writes go in: counts, and [toolActivity]'s fixed phrases for a tool's name. Never a
 * tool's arguments, an approval's summary, a question's text or anything a model wrote, since the
 * home screen is visible to anyone holding the phone.
 */
fun widgetStatus(states: Map<String, RunState>): WidgetStatus {
    val user = states.filterValues { !it.scheduled }
    val approvals = user.values.flatMap { it.approvals }
    val questions = user.values.flatMap { it.questions }
    val waiting = buildList {
        if (approvals.isNotEmpty()) add(count(approvals.size, "approval"))
        if (questions.isNotEmpty()) add(count(questions.size, "question"))
    }
    if (waiting.isNotEmpty()) {
        val chat = approvals.firstOrNull()?.conversationId ?: questions.firstOrNull()?.chatId
        return WidgetStatus(waiting.joinToString(", ") + " waiting", WidgetTone.WAITING, chat)
    }
    user.entries.firstOrNull { it.value.budgetPause != null }?.let { (chat, _) ->
        return WidgetStatus("Paused at the task budget", WidgetTone.WAITING, chat)
    }
    val running = user.entries.firstOrNull { it.value.running }
    val helpers = user.values.sumOf { it.helpers }
    if (running != null) {
        val tool = running.value.activeTool
        val text = if (tool != null) "Working: " + toolActivity(tool).replaceFirstChar { it.lowercaseChar() } else "Working"
        return WidgetStatus(text, WidgetTone.WORKING, running.key)
    }
    if (helpers > 0) {
        val chat = user.entries.firstOrNull { it.value.helpers > 0 }?.key
        return WidgetStatus("Working: " + count(helpers, "helper"), WidgetTone.WORKING, chat)
    }
    if (states.values.any { it.scheduled && it.running }) return WidgetStatus("Running a scheduled task", WidgetTone.WORKING)
    return WidgetStatus.IDLE
}

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/** The most recently run scheduled task, or null before any task has run. */
fun latestTaskRun(tasks: List<ScheduledTask>): LatestTaskRun? =
    tasks.filter { it.lastRunAt != null }.maxByOrNull { it.lastRunAt!! }
        ?.let { LatestTaskRun(it.title, it.lastSummary, it.lastOutcome, it.lastRunAt!!) }

/**
 * The widget's task line for [run]: "Morning news: Three stories about…", "Backups: failed". The summary
 * is a model's answer, possibly built from web pages or email, so it goes through [WidgetText.clean]
 * with [scrub] (the vault's values and credential patterns); a failed or stopped run shows only that.
 */
fun taskLine(run: LatestTaskRun, scrub: (String) -> String): String? {
    val title = WidgetText.clean(run.title, scrub, WidgetText.TITLE_MAX).ifEmpty { "Scheduled task" }
    val what = when (run.outcome) {
        TaskRunOutcome.FAILED -> "failed"
        TaskRunOutcome.STOPPED -> "stopped"
        else -> run.summary?.let { WidgetText.clean(it, scrub, WidgetText.LINE_MAX - title.length - 2) }.orEmpty().ifEmpty { "done" }
    }
    return "$title: $what"
}

/**
 * Cleans text from a model or a user for a home-screen widget, where anyone holding the phone can read
 * it and nothing from a web page should look like Locus talking. Pure; tested on the JVM.
 */
object WidgetText {
    /** The whole task line, in characters: one line on a 3-cell-wide widget, near enough. */
    const val LINE_MAX = 90
    const val TITLE_MAX = 32
    private const val ELLIPSIS = "…"

    private val MARKDOWN_IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val URL = Regex("""(?i)\b(?:[a-z][a-z0-9+.\-]*://|www\.|mailto:|javascript:|data:)\S+""")
    private val LINE_MARKUP = Regex("""^\s*(?:#{1,6}\s+|>\s*|[-+*]\s+|\d+[.)]\s+)+""")
    private val INLINE_MARKUP = Regex("""[`*~|]+|__""")
    private val SPACES = Regex("""\s+""")

    /**
     * [text] made safe to show: credentials scrubbed first (on the whole text, so a split token can't
     * slip past), links reduced to their label and bare URLs to "[link]", Markdown and invisible or
     * direction-changing characters removed, the first non-empty line kept, and the result cut to [max]
     * characters (never inside a surrogate pair), ending in "…" when cut. Empty when nothing is left.
     */
    fun clean(text: String, scrub: (String) -> String, max: Int): String {
        if (max <= 0) return ""
        var t = scrub(text)
        t = MARKDOWN_IMAGE.replace(t) { it.groupValues[1] }
        t = MARKDOWN_LINK.replace(t) { it.groupValues[1] }
        t = URL.replace(t, "[link]")
        t = stripInvisible(t)
        val line = t.lineSequence()
            .map { LINE_MARKUP.replace(it, "") }
            .map { INLINE_MARKUP.replace(it, "") }
            .map { SPACES.replace(it, " ").trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return ""
        return truncate(line, max)
    }

    /**
     * Drops control, format (zero-width, bidi overrides and isolates, BOM), private-use, unassigned and
     * lone surrogate characters; keeps line breaks and tabs as spaces so lines still split.
     */
    internal fun stripInvisible(text: String): String = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            when {
                cp == '\n'.code -> append('\n')
                cp == '\t'.code || cp == '\r'.code -> append(' ')
                else -> when (Character.getType(cp).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE -> Unit
                    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> append('\n')
                    else -> appendCodePoint(cp)
                }
            }
            i += n
        }
    }

    /** [line] cut to [max] characters with "…", at a space when one is near the end, never splitting a surrogate pair. */
    internal fun truncate(line: String, max: Int): String {
        if (line.length <= max) return line
        var end = (max - ELLIPSIS.length).coerceAtLeast(0)
        if (end > 0 && Character.isHighSurrogate(line[end - 1])) end--
        val space = line.lastIndexOf(' ', end)
        if (space >= end - 12 && space > 0) end = space
        return line.substring(0, end).trimEnd() + ELLIPSIS
    }
}

/**
 * What the home-screen widget shows, as it changes: from [AgentRunner.states] (the same source as the
 * status notification) and the tasks table. No polling: it emits only when either changes and the
 * widget's text would differ. The vault is read for scrubbing only when the latest task run changes.
 */
@Singleton
class WidgetFeed @Inject constructor(
    runner: AgentRunner,
    tasks: TaskRepository,
    private val secrets: SecretStore,
    @param:ApplicationScope scope: CoroutineScope,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: SharedFlow<WidgetModel> = combine(
        runner.states.map(::widgetStatus).distinctUntilChanged(),
        tasks.tasks.map(::latestTaskRun).distinctUntilChanged().mapLatest { run -> run?.let { taskLine(it, scrubber()::scrub) } },
    ) { status, task -> WidgetModel(status, task) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private suspend fun scrubber() = SecretScrubber(runCatching { secrets.valuesForScrubbing() }.getOrDefault(emptyList()))
}
