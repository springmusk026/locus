package dev.locus.core.data.widget

import dev.locus.core.agent.SecretScrubber
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.repository.AnalyticsRepository
import dev.locus.core.data.repository.CostEstimate
import dev.locus.core.data.repository.UsageDay
import dev.locus.core.data.repository.UsageToday
import dev.locus.core.data.repository.dayStarts
import dev.locus.core.data.tasks.DailySlot
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.data.tasks.TaskTiming
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** How the widget's status reads at a glance; the widget picks the dot's colour from it. */
enum class WidgetTone { IDLE, WORKING, WAITING }

/**
 * What the status line says, as data: the widget words it from its strings in the app's language
 * (docs/design/android-bridge.md, "The widget"). Only counts and Locus's own labels, never a tool's
 * arguments, an approval's summary, a question's text or anything a model wrote.
 */
sealed interface WidgetSays {
    /** "1 approval waiting", "2 approvals, 1 question waiting". */
    data class Waiting(val approvals: Int, val questions: Int) : WidgetSays

    /** "Paused at the task budget". */
    data object BudgetPaused : WidgetSays

    /** "Working", or "Working: running a command" for the active [tool] (worded by its fixed activity phrase). */
    data class Working(val tool: String? = null) : WidgetSays

    /** "Working: 2 helpers". */
    data class Helpers(val count: Int) : WidgetSays

    /** "Running a scheduled task". */
    data object Scheduled : WidgetSays

    /** "Idle". */
    data object Idle : WidgetSays
}

/**
 * The home-screen widget's status line: what it [says] and its [tone]. [chatId] is the chat a tap opens:
 * the one waiting for the user, else the one working. A tap only opens Locus; nothing is ever answered
 * from the widget.
 */
data class WidgetStatus(val says: WidgetSays, val tone: WidgetTone, val chatId: String? = null) {
    companion object {
        val IDLE = WidgetStatus(WidgetSays.Idle, WidgetTone.IDLE)
    }
}

/** How the latest scheduled run ended, as the widget says it. */
enum class WidgetRunResult { DONE, FAILED, STOPPED }

/**
 * The latest scheduled run on the widget: its [title] (cleaned; null when it has none left), how it
 * ended, its answer's [text] (cleaned, several lines; null when it failed, stopped or said nothing), and
 * when it ran. [hidden] with Lock Locus on: no title and no text, only that a task ran and when.
 */
data class WidgetRun(val title: String?, val result: WidgetRunResult, val text: String?, val at: Long, val hidden: Boolean = false)

/** The next scheduled run while Locus is idle: its [title] (null with Lock Locus on, or when it has none left) and when. */
data class WidgetNext(val title: String?, val at: Long)

/**
 * Everything the status widget shows: the status line, the latest scheduled run and, while idle, the next
 * one. [day] is the start of the local day the model was made on, so the widget is redrawn at midnight
 * and its "7:02" never silently means yesterday.
 */
data class WidgetModel(val status: WidgetStatus = WidgetStatus.IDLE, val run: WidgetRun? = null, val next: WidgetNext? = null, val day: Long = 0)

/**
 * The widget's model. The next run shows only while idle (otherwise the status says more). With Lock Locus
 * on ([appLocked]) the run keeps only its outcome and time and the next run only its time: an answer is a
 * model's words, and a task's title is the user's own words ("Check the bank balance"), and the home screen
 * is visible to anyone holding the phone (docs/design/app-lock.md).
 */
fun widgetModel(status: WidgetStatus, run: WidgetRun?, next: WidgetNext?, appLocked: Boolean, day: Long = 0): WidgetModel = WidgetModel(
    status = status,
    run = if (appLocked) run?.copy(title = null, text = null, hidden = true) else run,
    next = next?.takeIf { status.tone == WidgetTone.IDLE }?.let { if (appLocked) it.copy(title = null) else it },
    day = day,
)

/** The parts of the latest scheduled run the widget shows, before cleaning. */
data class LatestTaskRun(val title: String, val summary: String?, val outcome: TaskRunOutcome?, val at: Long)

/**
 * The widget's status from every chat's [RunState]. Pure, so the choice is unit-tested.
 *
 * Only what Locus itself knows goes in: counts, and a tool's name for its fixed activity phrase. Never a
 * tool's arguments, an approval's summary, a question's text or anything a model wrote, since the
 * home screen is visible to anyone holding the phone.
 */
fun widgetStatus(states: Map<String, RunState>): WidgetStatus {
    val user = states.filterValues { !it.scheduled }
    val approvals = user.values.flatMap { it.approvals }
    val questions = user.values.flatMap { it.questions }
    if (approvals.isNotEmpty() || questions.isNotEmpty()) {
        val chat = approvals.firstOrNull()?.conversationId ?: questions.firstOrNull()?.chatId
        return WidgetStatus(WidgetSays.Waiting(approvals.size, questions.size), WidgetTone.WAITING, chat)
    }
    val paused = user.entries.firstOrNull { it.value.budgetPause != null }?.key
    val running = user.entries.firstOrNull { it.value.running }
    val helpers = user.values.sumOf { it.helpers }
    return when {
        paused != null -> WidgetStatus(WidgetSays.BudgetPaused, WidgetTone.WAITING, paused)
        running != null -> WidgetStatus(WidgetSays.Working(running.value.activeTool), WidgetTone.WORKING, running.key)
        helpers > 0 -> WidgetStatus(WidgetSays.Helpers(helpers), WidgetTone.WORKING, user.entries.firstOrNull { it.value.helpers > 0 }?.key)
        states.values.any { it.scheduled && it.running } -> WidgetStatus(WidgetSays.Scheduled, WidgetTone.WORKING)
        else -> WidgetStatus.IDLE
    }
}

/** How the usage widget's big figure reads. */
sealed interface UsageFigure {
    /** "$0.42"; [floor] ("$1.50+") when some requests had no price, so the amount is at least this. */
    data class Spend(val usd: Double, val floor: Boolean = false) : UsageFigure

    /** "1.2M tokens", when no request had a price. */
    data class Tokens(val tokens: Long) : UsageFigure

    /** "No usage yet". */
    data object Nothing : UsageFigure
}

/** The usage widget's label line: "Today", "Today · cost unknown", "Today · prices off". */
enum class UsageNote { TODAY, COST_UNKNOWN, PRICES_OFF }

/**
 * The running task's use of its budget: [tokens] of [tokenLimit], [usd] (a floor when [usdFloor]) of
 * [usdLimit]. [paused] while it waits at the budget.
 */
data class TaskBudgetFigures(
    val paused: Boolean,
    val tokens: Long,
    val tokenLimit: Long? = null,
    val usd: Double? = null,
    val usdFloor: Boolean = false,
    val usdLimit: Double? = null,
)

/**
 * The usage widget (docs/design/android-bridge.md, "The usage widget"), as figures the widget words in
 * the app's language: one big [figure] (today's spend, or its tokens when no request had a price) under
 * its [note]; [tokens] under the spend (null when the figure already is the tokens); today's [requests];
 * the last seven days as [bars] (0..1, oldest first, today last; empty when the week had no use) with
 * their total [week] in the same measure; and the running [task]'s budget. Numbers and Locus's own words
 * only: no chat's name, no model's words, so it shows the same with Lock Locus on.
 */
data class UsageWidgetModel(
    val figure: UsageFigure,
    val note: UsageNote = UsageNote.TODAY,
    val tokens: Long? = null,
    val requests: Int = 0,
    val bars: List<Float> = emptyList(),
    val week: UsageFigure = UsageFigure.Nothing,
    val task: TaskBudgetFigures? = null,
)

/** The usage widget's figures from [today] (Insights' figures) and the running task's budget ([taskBudget]). Pure. */
fun usageWidgetModel(today: UsageToday, task: TaskBudgetFigures?): UsageWidgetModel {
    val cost = today.cost
    val note = when {
        today.requests == 0 || cost.priced > 0 -> UsageNote.TODAY
        today.pricesOn -> UsageNote.COST_UNKNOWN
        else -> UsageNote.PRICES_OFF
    }
    val figure = when {
        today.requests == 0 -> UsageFigure.Nothing
        // "+" when some requests had no price: the amount is a floor ("<$0.01" already says "at most").
        cost.priced > 0 -> UsageFigure.Spend(cost.usd, floor = cost.partial && cost.usd >= CENT)
        else -> UsageFigure.Tokens(today.tokens)
    }
    val (bars, week) = weekBars(today.week, today.weekCost)
    return UsageWidgetModel(figure, note, today.tokens.takeIf { figure is UsageFigure.Spend }, today.requests, bars, week, task)
}

private const val CENT = 0.01

/**
 * The week's bars (each day against the busiest, 0..1) and their total. In dollars when every day that
 * had requests had at least one priced (a day of unknown cost drawn as $0 would mislead), else in tokens,
 * which are always known. No bars for a week without any use.
 */
internal fun weekBars(week: List<UsageDay>, weekCost: CostEstimate): Pair<List<Float>, UsageFigure> {
    if (week.none { it.requests > 0 }) return emptyList<Float>() to UsageFigure.Nothing
    val inDollars = week.all { it.requests == 0 || it.cost.priced > 0 }
    val values = week.map { if (inDollars) it.cost.usd else it.tokens.toDouble() }
    val top = values.max().takeIf { it > 0 } ?: 1.0
    val total = if (inDollars) UsageFigure.Spend(weekCost.usd, floor = weekCost.partial && weekCost.usd >= CENT) else UsageFigure.Tokens(week.sumOf { it.tokens })
    return values.map { (it / top).toFloat() } to total
}

/**
 * The running task with a budget, the chat waiting at its budget first, else the first one running. Null
 * when no task with a budget runs. Time isn't included: it would change the widget every second. Scheduled
 * runs count too, as their budget is the user's money as much as a chat's. A cost limit that can't apply
 * (the model's prices are unknown) has no limit here.
 */
fun taskBudget(states: Map<String, RunState>): TaskBudgetFigures? {
    val state = states.values.filter { it.budget != null && (it.running || it.budgetPause != null) }
        .sortedByDescending { it.budgetPause != null }
        .firstOrNull()
    return state?.budget?.let { meter ->
        TaskBudgetFigures(
            paused = state.budgetPause != null,
            tokens = meter.tokens,
            tokenLimit = meter.tokenLimit,
            usd = meter.costUsd,
            usdFloor = meter.costPartial,
            usdLimit = meter.costLimit?.takeIf { meter.costUnavailable == null },
        )
    }
}

/** The most recently run scheduled task, or null before any task has run. */
fun latestTaskRun(tasks: List<ScheduledTask>): LatestTaskRun? =
    tasks.filter { it.lastRunAt != null }.maxByOrNull { it.lastRunAt!! }
        ?.let { LatestTaskRun(it.title, it.lastSummary, it.lastOutcome, it.lastRunAt!!) }

/**
 * The widget's latest run for [run]. The summary is a model's answer, possibly built from web pages or
 * email, so it goes through [WidgetText.cleanLines] with [scrub] (the vault's values and credential
 * patterns); a failed or stopped run shows only that.
 */
fun widgetRun(run: LatestTaskRun, scrub: (String) -> String): WidgetRun {
    val title = WidgetText.clean(run.title, scrub, WidgetText.TITLE_MAX).ifEmpty { null }
    val result = when (run.outcome) {
        TaskRunOutcome.FAILED -> WidgetRunResult.FAILED
        TaskRunOutcome.STOPPED -> WidgetRunResult.STOPPED
        else -> WidgetRunResult.DONE
    }
    val text = run.summary?.takeIf { result == WidgetRunResult.DONE }
        ?.let { WidgetText.cleanLines(it, scrub, WidgetText.TEXT_MAX, WidgetText.TEXT_LINES) }?.ifEmpty { null }
    return WidgetRun(title, result, text, run.at)
}

/**
 * The next scheduled run of a task that runs at a time of day: the soonest after [now] among enabled ones,
 * from each one's [slot] (its time of day and the run enqueued last). A planned run already past (it is
 * running, or Android holds it) gives way to the one after. Tasks on a plain period aren't counted: Android
 * runs those anywhere in each period, so no time would be honest; nor are tasks that events start.
 */
fun nextTaskRun(tasks: List<ScheduledTask>, slot: (String) -> DailySlot?, now: Long, zone: ZoneId): WidgetNext? {
    val at = Instant.ofEpochMilli(now)
    return tasks.asSequence()
        .filter { it.enabled && !it.eventTriggered && it.intervalMinutes > 0 && it.intervalMinutes % TaskTiming.DAY_MINUTES == 0L }
        .mapNotNull { task ->
            val s = slot(task.id) ?: return@mapNotNull null
            val days = (task.intervalMinutes / TaskTiming.DAY_MINUTES).toInt()
            val planned = s.planned?.let(Instant::ofEpochMilli)
            val due = when {
                planned == null -> TaskTiming.first(s.minuteOfDay, at, zone)
                planned.isAfter(at) -> planned
                else -> TaskTiming.next(s.minuteOfDay, days, planned, at, zone)
            }
            WidgetNext(task.title, due.toEpochMilli())
        }
        .minByOrNull { it.at }
}

/**
 * Cleans text from a model or a user for a home-screen widget, where anyone holding the phone can read
 * it and nothing from a web page should look like Locus talking. Pure; tested on the JVM.
 */
object WidgetText {
    /** One line, in characters: one line on a 3-cell-wide widget, near enough. */
    const val LINE_MAX = 90
    const val TITLE_MAX = 32

    /** A run's answer on the widget: at most this many characters, over at most [TEXT_LINES] lines; the widget shows what fits. */
    const val TEXT_MAX = 360
    const val TEXT_LINES = 8
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
        val line = lines(text, scrub).firstOrNull() ?: return ""
        return truncate(line, max)
    }

    /**
     * [text] made safe as [clean] makes it, keeping up to [maxLines] non-empty lines (joined with line
     * breaks) and cut to [max] characters in all, ending in "…" when cut. Empty when nothing is left.
     */
    fun cleanLines(text: String, scrub: (String) -> String, max: Int, maxLines: Int): String {
        if (max <= 0 || maxLines <= 0) return ""
        val kept = lines(text, scrub).take(maxLines + 1).toList()
        val joined = kept.take(maxLines).joinToString("\n")
        val more = kept.size > maxLines
        return when {
            joined.length > max -> truncate(joined, max)
            more && joined.length + ELLIPSIS.length > max -> truncate(joined, max)
            more -> joined + ELLIPSIS
            else -> joined
        }
    }

    /** The cleaned, non-empty lines of [text]: scrubbed, links reduced, hidden characters and Markdown removed. */
    private fun lines(text: String, scrub: (String) -> String): Sequence<String> {
        var t = scrub(text)
        t = MARKDOWN_IMAGE.replace(t) { it.groupValues[1] }
        t = MARKDOWN_LINK.replace(t) { it.groupValues[1] }
        t = URL.replace(t, "[link]")
        t = stripInvisible(t)
        return t.lineSequence()
            .map { LINE_MARKUP.replace(it, "") }
            .map { INLINE_MARKUP.replace(it, "") }
            .map { SPACES.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Hidden characters out ([dev.locus.core.model.HiddenText]: tags, bidi controls, zero-width text, runs
     * of selectors; emoji and script joiners stay), then what a one-line widget can't show: control,
     * private-use, unassigned and lone surrogate characters. Line breaks and tabs become breaks and
     * spaces so lines still split.
     */
    internal fun stripInvisible(text: String): String = buildString(text.length) {
        val visible = dev.locus.core.model.HiddenText.strip(text)
        var i = 0
        while (i < visible.length) {
            val cp = visible.codePointAt(i)
            val n = Character.charCount(cp)
            when {
                cp == '\n'.code -> append('\n')
                cp == '\t'.code || cp == '\r'.code -> append(' ')
                else -> when (Character.getType(cp).toByte()) {
                    Character.CONTROL, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE -> Unit
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
 * status notification), the tasks table and each task's planned run. No polling: it emits only when one of
 * them changes (and once at local midnight) and the widget would differ. The vault is read for scrubbing
 * only when the latest or next task run changes. With Lock Locus on, no answer and no task title is shown
 * on the home screen ([widgetModel], docs/design/app-lock.md); the status is counts and fixed phrases only.
 */
@Singleton
class WidgetFeed @Inject constructor(
    runner: AgentRunner,
    tasks: TaskRepository,
    private val secrets: SecretStore,
    @param:ApplicationScope scope: CoroutineScope,
    appLock: AppLock,
    analytics: AnalyticsRepository,
) {
    /** The tasks' part of the widget, before scrubbing. */
    private data class TaskParts(val latest: LatestTaskRun?, val next: WidgetNext?, val day: Long)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val taskParts = dayStarts(ZoneId.systemDefault()).flatMapLatest { day ->
        tasks.tasks.map { list ->
            val zone = ZoneId.systemDefault()
            TaskParts(latestTaskRun(list), nextTaskRun(list, tasks::dailySlot, System.currentTimeMillis(), zone), day)
        }
    }
        .distinctUntilChanged()
        .mapLatest { p -> scrubbedTasks(p.latest, p.next, secrets::valuesForScrubbing).let { (run, next) -> Triple(run, next, p.day) } }
        .flowOn(Dispatchers.IO)

    val state: SharedFlow<WidgetModel> = combine(
        runner.states.map(::widgetStatus).distinctUntilChanged(),
        taskParts,
        // Until the lock's settings are read, as if it were on: nothing of a task's shown by mistake.
        appLock.status.map { it?.enabled ?: true }.distinctUntilChanged(),
    ) { status, (run, next, day), locked -> widgetModel(status, run, next, locked, day) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_MS), replay = 1)

    /**
     * The usage widget's figures: today's and the week's from Insights ([AnalyticsRepository.today]) and the
     * running task's budget from the run states. Emits only when they change. Not affected by Lock Locus:
     * amounts and token counts name no chat and quote no one (docs/design/app-lock.md).
     */
    val usage: SharedFlow<UsageWidgetModel> = combine(
        analytics.today(),
        runner.states.map(::taskBudget).distinctUntilChanged(),
        ::usageWidgetModel,
    )
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_MS), replay = 1)

    private companion object {
        const val SHARE_STOP_MS = 5_000L
    }
}

/**
 * The latest run ([widgetRun]) and the next one's title, scrubbed of the vault's values as well as secret
 * patterns. When the vault can't be read, the widget shows no run and no title rather than ones scrubbed by
 * patterns alone (the next run's time still shows). Cancellation (a newer state arriving in `mapLatest`) is
 * rethrown, so a cancelled read never produces a line. The vault isn't read when there's nothing to scrub.
 */
suspend fun scrubbedTasks(latest: LatestTaskRun?, next: WidgetNext?, vaultValues: suspend () -> Collection<String>): Pair<WidgetRun?, WidgetNext?> {
    if (latest == null && next == null) return null to null
    val scrub: ((String) -> String)? = runCatchingCancellable { vaultValues() }.getOrNull()?.let { values -> SecretScrubber(values).let { s -> { text: String -> s.scrub(text) } } }
    val run = scrub?.let { latest?.let { r -> widgetRun(r, it) } }
    val title = scrub?.let { next?.title?.let { t -> WidgetText.clean(t, it, WidgetText.TITLE_MAX) } }?.ifEmpty { null }
    return run to next?.copy(title = title)
}
