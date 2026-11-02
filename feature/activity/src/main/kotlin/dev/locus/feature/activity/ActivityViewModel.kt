package dev.locus.feature.activity

import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.ActivityRepository
import dev.locus.core.data.repository.AuditCounts
import dev.locus.core.data.repository.AuditQuery
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.designsystem.Accent
import dev.locus.core.designsystem.accentFor
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.TASK_BUDGET_ENTRY
import dev.locus.core.model.toolActivity
import dev.locus.core.model.toolLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

enum class TimeRange(val label: String) { Today("Today"), Week("7 days"), All("All") }

/** The kinds of tool, by their accent, with the names the filter chips use. */
enum class KindFilter(val label: String, val accent: Accent) {
    Files("Files", Accent.Files),
    Web("Web", Accent.Web),
    Linux("Linux & SSH", Accent.Linux),
    Memory("Memory", Accent.Memory),
    Phone("Phone", Accent.Device),
    Connectors("Connectors", Accent.Connector),
    Agent("Helpers & plans", Accent.Agent),
}

/** Outcomes worth finding. Automatic successes are the norm and have no filter of their own. */
enum class StatusFilter(val label: String) {
    Failed("Failed"),
    Approved("You approved"),
    Declined("Declined"),
    Blocked("Blocked"),
    Reviewer("Reviewer"),
}

data class ActivityFilters(
    val query: String = "",
    val kind: KindFilter? = null,
    val status: StatusFilter? = null,
    val range: TimeRange = TimeRange.All,
    /** One task's entries, from its receipt: its chat and helpers, its time span and decisions. */
    val task: dev.locus.core.model.TaskActivityFilter? = null,
) {
    val isDefault: Boolean get() = query.isBlank() && kind == null && status == null && range == TimeRange.All && task == null
}

data class ActivityUiState(
    val loading: Boolean = true,
    val filters: ActivityFilters = ActivityFilters(),
    /** The chat this view is limited to, or null for everything. */
    val conversationId: String? = null,
    val scopeTitle: String? = null,
    /** Whether the scope came from inside this screen ("View all in this chat"), so Back widens it again. */
    val scopedHere: Boolean = false,
    val days: List<DaySection> = emptyList(),
    val hasMore: Boolean = false,
    /** Calls ever recorded in this scope, ignoring filters, to tell "nothing yet" from "no matches". */
    val totalInScope: Int = 0,
    val today: AuditCounts = AuditCounts(0, 0),
    val expanded: Set<String> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val activity: ActivityRepository,
    conversations: ConversationRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(ActivityFilters())
    private val scope = MutableStateFlow<Scope>(Scope(null, here = false))
    private val limit = MutableStateFlow(PAGE)
    private val expanded = MutableStateFlow<Set<String>>(emptySet())
    private var started = false

    private data class Scope(val conversationId: String?, val here: Boolean)

    /** Sets the chat this screen opened on (from the route); later calls are ignored. */
    fun start(conversationId: String?, task: dev.locus.core.model.TaskActivityFilter? = null) {
        if (started) return
        started = true
        scope.value = Scope(task?.conversationId ?: conversationId, here = false)
        if (task != null) filters.update { it.copy(task = task) }
    }

    /** Widens a task receipt's view back to the whole chat. */
    fun clearTask() = changeFilters { it.copy(task = null) }

    fun setQuery(q: String) = changeFilters { it.copy(query = q) }
    fun setKind(k: KindFilter?) = changeFilters { it.copy(kind = k) }
    fun setStatus(s: StatusFilter?) = changeFilters { it.copy(status = s) }
    fun setRange(r: TimeRange) = changeFilters { it.copy(range = r) }
    fun clearFilters() = changeFilters { ActivityFilters() }

    fun toggle(runKey: String) = expanded.update { if (runKey in it) it - runKey else it + runKey }

    fun loadMore() = limit.update { it + PAGE }

    /** "View all in this chat": the same screen, limited to one conversation. */
    fun showChat(conversationId: String) {
        scope.value = Scope(conversationId, here = true)
        limit.value = PAGE
    }

    /** Back from a chat view opened inside this screen. */
    fun showEverything() {
        scope.value = Scope(null, here = false)
        limit.value = PAGE
    }

    private fun changeFilters(f: (ActivityFilters) -> ActivityFilters) {
        filters.update(f)
        limit.value = PAGE
    }

    private val chats: Flow<Map<String, RunChat>> = combine(conversations.allWithHelpers, settings.settings) { list, s ->
        val titles = list.associate { it.id to it.title }
        list.associate { c ->
            val p = s.provider(c.providerId)
            val parent = c.parentConversationId
            c.id to RunChat(
                // A helper's run is named after its job and the chat it worked for.
                title = if (parent == null) c.title else "${(c.agentType ?: "helper").replaceFirstChar { it.uppercase() }} helper · ${titles[parent] ?: "Deleted chat"}",
                exists = parent == null || parent in titles,
                openId = parent ?: c.id,
                provider = p?.let { RunProvider(it.name, it.baseUrl, it.format == ApiFormat.ON_DEVICE, c.modelOverride ?: it.model) },
            )
        }
    }

    // Typing shouldn't requery on every keystroke; chips and range apply at once.
    private val debouncedFilters: Flow<ActivityFilters> = filters
        .debounce { if (it.query.isNotBlank()) 250L else 0L }
        .distinctUntilChanged()

    private val window: Flow<Window> = combine(debouncedFilters, scope, limit, activity.observeToolNames()) { f, s, lim, names ->
        Triple(query(f, s.conversationId, names), lim, f)
    }.distinctUntilChanged().flatMapLatest { (q, lim, f) ->
        // One extra row tells whether there's more to load.
        activity.observe(q, lim + 1).map { Window(it.take(lim), it.size > lim, f) }
    }

    private data class Window(val entries: List<dev.locus.core.model.AuditEntry>, val hasMore: Boolean, val filters: ActivityFilters)

    private val counts: Flow<Pair<Int, AuditCounts>> = scope.flatMapLatest { s ->
        combine(activity.observeCounts(0L, s.conversationId), activity.observeCounts(startOfDay(0), s.conversationId)) { all, today -> all.total to today }
    }

    private val sections: Flow<Pair<Window, List<DaySection>>> = combine(window, chats) { w, c ->
        val runs = clusterRuns(w.entries, { id -> c[id] ?: RunChat("Deleted chat", exists = false) })
        w to runs.groupBy { dayLabel(it.latest.startedAt) }.map { (day, list) -> DaySection(day, list) }
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<ActivityUiState> = combine(sections, counts, scope, expanded, chats.onStart { emit(emptyMap()) }) { (w, days), (total, today), s, open, c ->
        ActivityUiState(
            loading = false,
            conversationId = s.conversationId,
            scopeTitle = s.conversationId?.let { c[it]?.title ?: "Deleted chat" },
            scopedHere = s.here,
            days = days,
            hasMore = w.hasMore,
            totalInScope = total,
            today = today,
            expanded = open,
        )
    }.combine(filters) { state, f -> state.copy(filters = f) } // the search field follows typing at once
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    private companion object {
        const val PAGE = 300

        fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun query(f: ActivityFilters, conversationId: String?, toolNames: List<String>): AuditQuery {
            val text = f.query.trim().takeIf { it.isNotEmpty() }
            val task = f.task
            if (task != null) {
                // A task receipt's view: its chat and helpers in its time span, with the decisions it counted.
                return AuditQuery(
                    since = task.since,
                    until = task.until,
                    conversationId = conversationId ?: task.conversationId,
                    withHelpers = true,
                    decisions = task.decisions.ifEmpty { null },
                    text = text,
                    textTools = text?.let { t -> toolNames.filter { it.contains(t, ignoreCase = true) || toolActivity(it).contains(t, ignoreCase = true) }.toSet() }.orEmpty(),
                )
            }
            val (decisions, onlyErrors) = when (f.status) {
                null -> null to false
                StatusFilter.Failed -> setOf(ApprovalDecision.AUTO_ALLOWED, ApprovalDecision.USER_APPROVED, ApprovalDecision.REVIEWER_ALLOWED, ApprovalDecision.INVALID_INPUT) to true
                StatusFilter.Approved -> setOf(ApprovalDecision.USER_APPROVED) to false
                StatusFilter.Declined -> setOf(ApprovalDecision.USER_DENIED) to false
                StatusFilter.Blocked -> setOf(ApprovalDecision.POLICY_DENIED) to false
                StatusFilter.Reviewer -> setOf(ApprovalDecision.REVIEWER_ALLOWED, ApprovalDecision.REVIEWER_DENIED) to false
            }
            return AuditQuery(
                since = when (f.range) {
                    TimeRange.Today -> startOfDay(0)
                    TimeRange.Week -> startOfDay(6)
                    TimeRange.All -> 0L
                },
                conversationId = conversationId,
                // A task's budget entries aren't tool calls of any kind.
                tools = f.kind?.let { k -> toolNames.filter { it != TASK_BUDGET_ENTRY && accentFor(it).first == k.accent }.toSet() },
                decisions = decisions,
                onlyErrors = onlyErrors,
                text = text,
                // Labels are worked out in Kotlin, so match them here: "searched" finds web_search.
                textTools = text?.let { t ->
                    toolNames.filter { n ->
                        n.contains(t, ignoreCase = true) ||
                            toolActivity(n).contains(t, ignoreCase = true) ||
                            toolLabel(n, "{}", done = true).contains(t, ignoreCase = true)
                    }.toSet()
                }.orEmpty(),
            )
        }

        fun dayLabel(millis: Long): String = when {
            DateUtils.isToday(millis) -> "Today"
            DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
        }
    }
}
