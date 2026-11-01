package dev.locus.core.data.repository

import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.database.AuditDao
import dev.locus.core.database.AuditStatRow
import dev.locus.core.database.ConversationDao
import dev.locus.core.database.ConversationEntity
import dev.locus.core.database.MessageDao
import dev.locus.core.database.MessageStatRow
import dev.locus.core.database.UsageEventDao
import dev.locus.core.database.UsageEventEntity
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TASK_BUDGET_ENTRY
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.UsageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class AnalyticsPeriod(val days: Int?) { Week(7), Month(30), All(null) }

/** Tokens over a span. [input] already includes cached input (every adapter reports it that way). */
data class TokenTotals(
    val input: Long = 0,
    /** Reasoning included. */
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    /** The part of [output] providers reported as reasoning (thinking). */
    val reasoning: Long = 0,
) {
    val total: Long get() = input + output

    /** Share of input tokens served from the provider's prompt cache, or null with no input. */
    val cachedShare: Float? get() = if (input > 0) cacheRead.toFloat() / input else null

    operator fun plus(u: TokenUsage) = TokenTotals(input + u.input, output + u.output, cacheRead + u.cacheRead, cacheWrite + u.cacheWrite, reasoning + u.reasoning)
}

/** One bar of the charts: a day, or a week when the span is long. */
data class UsageBucket(
    val start: Long,
    val tokens: TokenTotals = TokenTotals(),
    val requests: Int = 0,
    val toolCalls: Int = 0,
    val toolFailures: Int = 0,
)

/**
 * Usage per provider and model: the one stored with each reply and side request (database v9), or for
 * older replies, the one their chat uses now.
 */
data class ModelUsage(
    val providerId: String?,
    val providerName: String,
    val providerUrl: String,
    val model: String,
    val onDevice: Boolean,
    val tokens: Long,
    val requests: Int,
)

/** Tokens and requests for one [UsageSource]: "Where tokens went". */
data class SourceUsage(val source: UsageSource, val tokens: Long, val requests: Int)

data class ToolTiming(val name: String, val calls: Int, val failures: Int, val avgMs: Long, val p95Ms: Long)

data class ToolStats(
    val calls: Int = 0,
    /** Ran and returned an error, or the model sent invalid input. Denials are not failures. */
    val failures: Int = 0,
    val autoAllowed: Int = 0,
    val userApproved: Int = 0,
    val denied: Int = 0,
    val invalid: Int = 0,
    /** Slowest first, timed on calls that ran without asking (approved calls include the wait for you). */
    val slowest: List<ToolTiming> = emptyList(),
) {
    val successRate: Float? get() = (calls - denied).takeIf { it > 0 }?.let { 1f - failures.toFloat() / it }
}

data class ChatUsage(val conversationId: String, val title: String, val tokens: Long, val requests: Int, val toolCalls: Int)

data class TaskRuns(val title: String, val runs: Int, val lastRunAt: Long?, val enabled: Boolean?)

data class AnalyticsReport(
    val period: AnalyticsPeriod,
    val from: Long,
    val to: Long,
    /** 1 for daily bars, 7 for weekly. */
    val bucketDays: Int,
    val buckets: List<UsageBucket>,
    val tokens: TokenTotals,
    val requests: Int,
    /**
     * How long replies took: the stored time from request to finished turn (v9), or for older replies
     * the time since the message they answer, a proxy.
     */
    val replyMedianMs: Long?,
    val replyP95Ms: Long?,
    /** Time to the first streamed output, from replies that stored it (v9); null when none did. */
    val firstTokenMedianMs: Long? = null,
    val firstTokenP95Ms: Long? = null,
    val models: List<ModelUsage>,
    /** Chat replies, helpers and side requests, most tokens first; only sources that were used. */
    val sources: List<SourceUsage> = emptyList(),
    val tools: ToolStats,
    val chats: List<ChatUsage>,
    val tasks: List<TaskRuns>,
    val taskRuns: Int,
) {
    val isEmpty: Boolean get() = requests == 0 && tools.calls == 0 && taskRuns == 0
}

/** Read-only usage figures computed from the local database: tokens, requests, tools and tasks. */
@Singleton
class AnalyticsRepository @Inject constructor(
    private val messages: MessageDao,
    private val audit: AuditDao,
    private val conversations: ConversationDao,
    private val settings: SettingsRepository,
    private val tasks: TaskRepository,
    private val usageEvents: UsageEventDao,
) {
    /** Live report for [period]; re-computed (at most twice a second) as chats and tool calls land. */
    @OptIn(FlowPreview::class)
    fun report(period: AnalyticsPeriod, zone: ZoneId = ZoneId.systemDefault()): Flow<AnalyticsReport> {
        val now = System.currentTimeMillis()
        val since = period.days?.let { LocalDate.now(zone).minusDays(it - 1L).atStartOfDay(zone).toInstant().toEpochMilli() } ?: 0L
        return combine(
            combine(messages.observeStats(since), usageEvents.observeSince(since), ::Pair),
            audit.observeStats(since),
            conversations.observeAll(),
            settings.settings,
            tasks.tasks,
        ) { (m, e), a, c, s, t -> buildReport(period, since, now, m, a, c, s, t, zone, e) }
            .debounce(500)
            .flowOn(Dispatchers.Default)
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val SCHEDULED_PREFIX = "Scheduled: "
        private const val MAX_REPLY_GAP_MS = 15 * 60 * 1000L

        fun buildReport(
            period: AnalyticsPeriod,
            since: Long,
            now: Long,
            messages: List<MessageStatRow>,
            audit: List<AuditStatRow>,
            conversations: List<ConversationEntity>,
            settings: AppSettings,
            tasks: List<ScheduledTask>,
            zone: ZoneId,
            events: List<UsageEventEntity> = emptyList(),
        ): AnalyticsReport {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val scheduled = conversations.filter { it.title.startsWith(SCHEDULED_PREFIX) && it.createdAt >= since }
            // The first day with anything in it, for "All".
            val firstDay = period.days?.let { today.minusDays(it - 1L) }
                ?: listOfNotNull(messages.minOfOrNull { it.createdAt }, events.minOfOrNull { it.createdAt }, audit.minOfOrNull { it.startedAt }, scheduled.minOfOrNull { it.createdAt })
                    .minOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today
            val spanDays = (today.toEpochDay() - firstDay.toEpochDay() + 1).toInt().coerceAtLeast(1)
            val bucketDays = if (spanDays > 60) 7 else 1
            // Buckets end today; weekly buckets are aligned so the last one ends today.
            val count = (spanDays + bucketDays - 1) / bucketDays
            val starts = (0 until count).map { i -> today.minusDays(((count - 1 - i) * bucketDays + bucketDays - 1).toLong()) }
            val bucketStartMillis = starts.map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
            fun bucketOf(t: Long): Int {
                val i = bucketStartMillis.indexOfLast { it <= t }
                return i.coerceAtLeast(0)
            }

            val bucketTokens = Array(count) { TokenTotals() }
            val bucketRequests = IntArray(count)
            val bucketTools = IntArray(count)
            val bucketFailures = IntArray(count)

            val byConversation = conversations.associateBy { it.id }
            val chatTokens = HashMap<String, Long>()
            val chatRequests = HashMap<String, Int>()
            val chatTools = HashMap<String, Int>()
            fun exactProvider(id: String?) = settings.providers.firstOrNull { it.id == id }
            // Keyed by (provider, model).
            val providerTokens = HashMap<Pair<String?, String>, Long>()
            val providerRequests = HashMap<Pair<String?, String>, Int>()
            val sourceTokens = HashMap<UsageSource, Long>()
            val sourceRequests = HashMap<UsageSource, Int>()
            /**
             * The stored provider and model; for rows from before v9, what the chat would run on now (its
             * provider, or the default one it falls back to, and its override, else the provider's model).
             * Either way the id is one a provider had, so a missing one below means it was removed.
             */
            fun modelKey(providerId: String?, model: String?, c: ConversationEntity?): Pair<String?, String> =
                if (providerId != null || model != null) providerId to (model ?: exactProvider(providerId)?.model.orEmpty())
                else settings.provider(c?.providerId).let { p -> p?.id to (c?.modelOverride ?: p?.model.orEmpty()) }

            var totals = TokenTotals()
            var requests = 0
            /** Counts one model request everywhere it shows up: totals, its bar, its chat, its model and its source. */
            fun count(at: Long, conversationId: String?, model: Pair<String?, String>, source: UsageSource, usageJson: String?) {
                requests++
                val b = bucketOf(at)
                bucketRequests[b]++
                conversationId?.let { chatRequests.merge(it, 1, Int::plus) }
                providerRequests.merge(model, 1, Int::plus)
                sourceRequests.merge(source, 1, Int::plus)
                val usage = usageJson?.let { runCatching { json.decodeFromString(TokenUsage.serializer(), it) }.getOrNull() } ?: return
                totals += usage
                bucketTokens[b] = bucketTokens[b] + usage
                conversationId?.let { chatTokens.merge(it, usage.context.toLong(), Long::plus) }
                providerTokens.merge(model, usage.context.toLong(), Long::plus)
                sourceTokens.merge(source, usage.context.toLong(), Long::plus)
            }

            val replyTimes = ArrayList<Long>()
            val firstTokenTimes = ArrayList<Long>()
            val lastAt = HashMap<String, Long>()
            for (row in messages) {
                val previous = lastAt.put(row.conversationId, row.createdAt)
                if (row.role != "ASSISTANT") continue
                val chat = byConversation[row.conversationId]
                val source = if (chat?.parentConversationId != null) UsageSource.HELPER else UsageSource.CHAT
                count(row.createdAt, row.conversationId, modelKey(row.providerId, row.model, chat), source, row.usageJson)
                val totalMs = row.totalMs
                if (totalMs != null) {
                    replyTimes += totalMs
                    row.firstTokenMs?.let(firstTokenTimes::add)
                } else {
                    // Before v9: timed from the message it answers (its parent), so a regenerated reply isn't timed from another branch.
                    (row.parentCreatedAt ?: previous)?.let { from -> (row.createdAt - from).takeIf { it in 0..MAX_REPLY_GAP_MS }?.let(replyTimes::add) }
                }
            }
            for (e in events) {
                count(e.createdAt, e.conversationId, modelKey(e.providerId, e.model, null), UsageSource.parse(e.source), e.usageJson)
            }

            var failures = 0
            var auto = 0
            var approved = 0
            var denied = 0
            var invalid = 0
            val durations = HashMap<String, MutableList<Long>>()
            val toolCalls = HashMap<String, Int>()
            val toolFailures = HashMap<String, Int>()
            // A task's budget entries (a pause, its answer, the total) are in the log but aren't tool calls.
            val toolRows = audit.filter { it.toolName != TASK_BUDGET_ENTRY }
            for (row in toolRows) {
                val decision = runCatching { ApprovalDecision.valueOf(row.decision) }.getOrNull()
                val failed = decision == ApprovalDecision.INVALID_INPUT ||
                    (row.isError && (decision == ApprovalDecision.AUTO_ALLOWED || decision == ApprovalDecision.USER_APPROVED || decision == ApprovalDecision.REVIEWER_ALLOWED))
                when (decision) {
                    ApprovalDecision.AUTO_ALLOWED, ApprovalDecision.REVIEWER_ALLOWED -> auto++
                    ApprovalDecision.USER_APPROVED -> approved++
                    ApprovalDecision.USER_DENIED, ApprovalDecision.POLICY_DENIED, ApprovalDecision.REVIEWER_DENIED -> denied++
                    ApprovalDecision.INVALID_INPUT -> invalid++
                    null -> Unit
                }
                if (failed) failures++
                val b = bucketOf(row.startedAt)
                bucketTools[b]++
                if (failed) bucketFailures[b]++
                chatTools.merge(row.conversationId, 1, Int::plus)
                toolCalls.merge(row.toolName, 1, Int::plus)
                if (failed) toolFailures.merge(row.toolName, 1, Int::plus)
                if (decision == ApprovalDecision.AUTO_ALLOWED) {
                    durations.getOrPut(row.toolName) { ArrayList() } += (row.finishedAt - row.startedAt).coerceAtLeast(0)
                }
            }
            val slowest = durations.map { (name, list) ->
                ToolTiming(name, toolCalls[name] ?: list.size, toolFailures[name] ?: 0, list.average().toLong(), percentile(list, 0.95))
            }.sortedByDescending { it.p95Ms }.take(5)

            val models = (providerRequests.keys + providerTokens.keys).map { id ->
                val p = exactProvider(id.first)
                ModelUsage(
                    providerId = id.first,
                    // A provider removed since: the stored model still names what answered.
                    providerName = p?.name ?: if (id.second.isBlank()) "Removed model" else "a removed provider",
                    providerUrl = p?.baseUrl.orEmpty(),
                    model = id.second,
                    onDevice = p?.format == ApiFormat.ON_DEVICE,
                    tokens = providerTokens[id] ?: 0,
                    requests = providerRequests[id] ?: 0,
                )
            }.sortedWith(compareByDescending<ModelUsage> { it.tokens }.thenByDescending { it.requests })

            // Helper (sub-agent) chats are hidden; their work counts toward the chat that started them.
            fun owner(id: String): String = byConversation[id]?.parentConversationId?.takeIf { it in byConversation } ?: id
            val chats = (chatRequests.keys + chatTools.keys).groupBy(::owner).mapNotNull { (id, ids) ->
                val c = byConversation[id]?.takeIf { !it.hidden } ?: return@mapNotNull null
                ChatUsage(id, c.title, ids.sumOf { chatTokens[it] ?: 0 }, ids.sumOf { chatRequests[it] ?: 0 }, ids.sumOf { chatTools[it] ?: 0 })
            }.sortedWith(compareByDescending<ChatUsage> { it.tokens }.thenByDescending { it.requests + it.toolCalls }).take(5)

            val runsByTitle = scheduled.groupBy { it.title.removePrefix(SCHEDULED_PREFIX).substringBeforeLast(" · ") }
            val taskRows = (tasks.map { it.title } + runsByTitle.keys).distinct().map { title ->
                val task = tasks.firstOrNull { it.title == title }
                TaskRuns(title, runsByTitle[title]?.size ?: 0, task?.lastRunAt ?: runsByTitle[title]?.maxOf { it.createdAt }, task?.enabled)
            }.sortedWith(compareByDescending<TaskRuns> { it.runs }.thenByDescending { it.lastRunAt ?: 0 })

            return AnalyticsReport(
                period = period,
                from = bucketStartMillis.first(),
                to = now,
                bucketDays = bucketDays,
                buckets = (0 until count).map { i ->
                    UsageBucket(bucketStartMillis[i], bucketTokens[i], bucketRequests[i], bucketTools[i], bucketFailures[i])
                },
                tokens = totals,
                requests = requests,
                replyMedianMs = replyTimes.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.5) },
                replyP95Ms = replyTimes.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
                firstTokenMedianMs = firstTokenTimes.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.5) },
                firstTokenP95Ms = firstTokenTimes.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
                models = models,
                sources = sourceRequests.keys.map { SourceUsage(it, sourceTokens[it] ?: 0, sourceRequests[it] ?: 0) }
                    .sortedWith(compareByDescending<SourceUsage> { it.tokens }.thenByDescending { it.requests }),
                tools = ToolStats(toolRows.size,failures, auto, approved, denied, invalid, slowest),
                chats = chats,
                tasks = taskRows,
                taskRuns = scheduled.size,
            )
        }

        /** Nearest-rank percentile. */
        fun percentile(values: List<Long>, p: Double): Long {
            if (values.isEmpty()) return 0
            val sorted = values.sorted()
            val rank = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
