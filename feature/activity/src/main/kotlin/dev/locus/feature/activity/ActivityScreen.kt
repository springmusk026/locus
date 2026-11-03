package dev.locus.feature.activity

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.SearchField
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.accentFor
import dev.locus.core.designsystem.cardSegment
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.durationLabel
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.toolLabel
import java.text.DateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Runs longer than this show their latest calls first-come, with a row to show the rest. */
private const val RUN_PREVIEW = 40

/**
 * Everything the agent did, grouped into runs (bursts of work in one chat) under day headers, with
 * search and filters. [conversationId] limits it to one chat, e.g. when opened from that chat.
 */
@Composable
fun ActivityRoute(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit = {},
    conversationId: String? = null,
    /** Opened from a task receipt: just that task's matching entries, until the user widens it. */
    task: dev.locus.core.model.TaskActivityFilter? = null,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    remember(viewModel) { viewModel.start(conversationId, task); true }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<Pair<AuditEntry, Run>?>(null) }
    // Runs whose whole timeline is showing, past the first [RUN_PREVIEW] calls.
    var fullRuns by remember { mutableStateOf(emptySet<String>()) }
    BackHandler(enabled = state.scopedHere) { viewModel.showEverything() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                state.scopeTitle ?: "Activity",
                onBack = if (state.scopedHere) viewModel::showEverything else onBack,
                status = { TopStatus(state) },
                actions = {
                    val id = state.conversationId
                    val exists = state.days.firstOrNull()?.runs?.firstOrNull()?.chat?.exists ?: (state.scopeTitle != "Deleted chat")
                    if (id != null && exists) {
                        IconButton(onClick = { onOpenConversation(id) }) { Icon(LocusIcons.Chats, contentDescription = "Open chat") }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        if (state.totalInScope == 0 && state.filters.isDefault) {
            EmptyState(
                if (state.conversationId == null) "Nothing yet" else "No actions in this chat",
                "Every tool the agent uses is recorded here: what it ran, whether you approved it, and what happened.",
                Modifier.padding(padding),
                icon = LocusIcons.Activity,
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
        ) {
            item(key = "filters", contentType = "filters") {
                Filters(state.filters, inChat = state.conversationId != null, viewModel)
            }
            if (state.days.isEmpty()) {
                item(key = "none", contentType = "none") { NoResults(state.filters, viewModel::clearFilters) }
            }
            state.days.forEach { day ->
                item(key = "day:${day.label}", contentType = "day") { SectionLabel(day.label) }
                day.runs.forEachIndexed { i, run ->
                    runItems(
                        run = run,
                        first = i == 0,
                        expanded = run.key in state.expanded,
                        full = run.key in fullRuns,
                        inChat = state.conversationId != null,
                        onToggle = { viewModel.toggle(run.key) },
                        onShowAll = { fullRuns = fullRuns + run.key },
                        onOpen = { detail = it to run },
                        onViewChat = { viewModel.showChat(run.conversationId) },
                        onOpenChat = { onOpenConversation(run.chat.openId ?: run.conversationId) },
                    )
                }
            }
            if (state.hasMore) {
                item(key = "more", contentType = "more") {
                    Box(Modifier.fillMaxWidth().padding(top = Spacing.lg), contentAlignment = Alignment.Center) {
                        TextButton(onClick = viewModel::loadMore) { Text("Load older activity") }
                    }
                }
            }
        }
    }

    detail?.let { (entry, run) ->
        DetailSheet(
            entry,
            run.chat,
            onDismiss = { detail = null },
            onOpenChat = if (run.chat.exists) ({ detail = null; onOpenConversation(run.chat.openId ?: run.conversationId) }) else null,
        )
    }
}

@Composable
private fun TopStatus(state: ActivityUiState) {
    if (state.conversationId != null) {
        if (state.totalInScope > 0) StatusLine(plural(state.totalInScope, "action") + " in this chat", Tone.Neutral)
        return
    }
    val today = state.today
    if (today.total > 0) {
        StatusLine(
            plural(today.total, "action") + " today" + if (today.failed > 0) " · ${today.failed} failed" else "",
            // A failed call is routine for an agent (a file that wasn't there yet); the chats below mark them.
            if (today.failed > 0) Tone.Caution else Tone.Neutral,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Search and filters
// ---------------------------------------------------------------------------------------------

@Composable
private fun Filters(filters: ActivityFilters, inChat: Boolean, viewModel: ActivityViewModel) {
    Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SearchField(filters.query, viewModel::setQuery, if (inChat) "Search this chat's actions" else "Search actions and chats")
        val task = filters.task
        if (task != null) {
            // From a task receipt: that task's entries only. Tapping the chip shows the whole chat again.
            ChipRow {
                LocusChip("This task · ${task.label}", variant = ChipVariant.Filter, selected = true, onClick = viewModel::clearTask)
            }
            return@Column
        }
        Segmented(
            TimeRange.entries.map { it.label },
            selected = filters.range.ordinal,
            onSelect = { viewModel.setRange(TimeRange.entries[it]) },
            modifier = Modifier.fillMaxWidth(),
            compact = true,
        )
        ChipRow {
            KindFilter.entries.forEach { k ->
                LocusChip(
                    k.label,
                    variant = ChipVariant.Filter,
                    selected = filters.kind == k,
                    dotColor = k.accent.color(),
                    onClick = { viewModel.setKind(if (filters.kind == k) null else k) },
                )
            }
        }
        ChipRow {
            StatusFilter.entries.forEach { s ->
                val tone = statusTone(s)
                LocusChip(
                    s.label,
                    variant = ChipVariant.Filter,
                    selected = filters.status == s,
                    dotColor = tone.takeIf { it != Tone.Neutral }?.color(),
                    onClick = { viewModel.setStatus(if (filters.status == s) null else s) },
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun NoResults(filters: ActivityFilters, onClear: () -> Unit) {
    EmptyState(
        if (filters.query.isBlank() && filters.kind == null && filters.status == null) "Nothing in this period" else "No matching actions",
        "Try a wider time range or fewer filters.",
        // Inside the list, under the filters: as tall as its content, with room around it, not the whole screen.
        Modifier.padding(vertical = Spacing.xxl),
        icon = LocusIcons.Search,
    ) { TextButton(onClick = onClear) { Text("Clear filters") } }
}

// ---------------------------------------------------------------------------------------------
// Runs
// ---------------------------------------------------------------------------------------------

/**
 * One run as lazy items: the header is always there; expanded, each call is its own item, so a
 * run of hundreds of calls stays lazy. All items draw slices of one card.
 */
private fun LazyListScope.runItems(
    run: Run,
    first: Boolean,
    expanded: Boolean,
    full: Boolean,
    inChat: Boolean,
    onToggle: () -> Unit,
    onShowAll: () -> Unit,
    onOpen: (AuditEntry) -> Unit,
    onViewChat: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val shown = if (!expanded) emptyList() else if (full || run.entries.size <= RUN_PREVIEW) run.entries else run.entries.takeLast(RUN_PREVIEW)
    val hidden = if (expanded) run.entries.size - shown.size else 0
    // Header, an optional "earlier" row, the calls, and the footer.
    val count = if (expanded) 1 + (if (hidden > 0) 1 else 0) + shown.size + 1 else 1
    var index = 0
    val gap = if (first) 0.dp else Spacing.sm
    item(key = run.key, contentType = "run") {
        RunHeader(run, expanded, inChat, Modifier.padding(top = gap).cardSegment(0, count), onToggle)
    }
    if (!expanded) return
    index++
    if (hidden > 0) {
        val i = index++
        item(key = "${run.key}:earlier", contentType = "earlier") {
            Text(
                "Show ${plural(hidden, "earlier action")}",
                Modifier.cardSegment(i, count).fillMaxWidth().clickable(onClick = onShowAll).padding(horizontal = Spacing.lg, vertical = Spacing.md),
                style = LocusType.label,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    shown.forEach { e ->
        val i = index++
        item(key = "${run.key}:${e.id}", contentType = "call") { CallRow(e, Modifier.cardSegment(i, count)) { onOpen(e) } }
    }
    val last = index
    item(key = "${run.key}:footer", contentType = "footer") {
        Row(
            Modifier.cardSegment(last, count).fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            if (!inChat) TextButton(onClick = onViewChat) { Text("View all in this chat") }
            if (run.chat.exists) TextButton(onClick = onOpenChat) { Text("Open chat") }
        }
    }
}

/** A run at a glance: which chat, when, how much, what kinds of tools, and what it did last. */
@Composable
private fun RunHeader(run: Run, expanded: Boolean, inChat: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val context = LocalContext.current
    val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    Column(modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val p = run.chat.provider
            if (p != null) ProviderBadge(p.name, p.baseUrl, p.onDevice, size = IconSize.tileSmall, model = p.model) else IconTile(LocusIcons.Chats, size = IconSize.tileSmall)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(
                    if (inChat) timeRange(context, run.start, run.end) else run.chat.title,
                    style = LocusType.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!inChat) SupportingText(timeRange(context, run.start, run.end), style = LocusType.mono)
            }
            Icon(LocusIcons.ChevronRight, contentDescription = if (expanded) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.medium).rotate(turn))
        }
        Row(Modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            run.accents.take(4).forEach { a ->
                AccentTile(a, accentIcon(a), size = IconSize.medium)
                Spacer(Modifier.width(Spacing.xs))
            }
            val critical = Locus.colors.critical
            val counts = buildAnnotatedString {
                append(plural(run.actions, "action"))
                if (run.failed > 0) {
                    append(" · ")
                    withStyle(SpanStyle(color = critical)) { append("${run.failed} failed") }
                }
                if (run.asked > 0) append(" · " + plural(run.asked, "approval"))
            }
            SupportingText(counts, Modifier.padding(start = Spacing.xs), maxLines = 1)
        }
        if (!expanded) {
            val e = run.latest
            SupportingText(
                "Last: " + toolLabel(e.toolName, e.argumentsJson, done = !e.isError && !e.refused),
                Modifier.padding(top = Spacing.xs),
                style = LocusType.rowSupporting,
                maxLines = 1,
            )
        }
    }
}

// design-exception: fixed columns keep the timeline's times and durations aligned from row to row
private val TimeColumnWidth = 64.dp
private val DurationColumnWidth = 44.dp // design-exception: see TimeColumnWidth

/** One call in a run's timeline: time, kind, what it did, and only the outcomes worth noticing. */
@Composable
private fun CallRow(entry: AuditEntry, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SupportingText(timeOf(entry.startedAt), Modifier.width(TimeColumnWidth), style = LocusType.mono, maxLines = 1)
        val (accent, icon) = accentFor(entry.toolName)
        AccentTile(accent, icon, size = IconSize.medium)
        Text(
            toolLabel(entry.toolName, entry.argumentsJson, done = !entry.isError && !entry.refused),
            Modifier.weight(1f).padding(horizontal = Spacing.sm),
            style = LocusType.rowSupporting,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        decisionPill(entry)?.let { (label, tone) -> LocusChip(label, Modifier.padding(end = Spacing.sm), tone = tone) }
        SupportingText(durationLabel(entry.finishedAt - entry.startedAt), Modifier.widthIn(min = DurationColumnWidth), style = LocusType.mono, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Details
// ---------------------------------------------------------------------------------------------

@Composable
private fun DetailSheet(entry: AuditEntry, chat: RunChat, onDismiss: () -> Unit, onOpenChat: (() -> Unit)?) {
    val when_ = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(entry.startedAt))
    LocusSheet(
        title = toolLabel(entry.toolName, entry.argumentsJson, done = !entry.isError && !entry.refused),
        onDismiss = onDismiss,
        subtitle = "$when_ · ${durationLabel(entry.finishedAt - entry.startedAt).trim()}",
        action = if (onOpenChat != null) "Open chat" else null,
        onAction = onOpenChat,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (accent, icon) = accentFor(entry.toolName)
            AccentTile(accent, icon, size = IconSize.large)
            SupportingText(entry.toolName, Modifier.weight(1f).padding(horizontal = Spacing.sm), style = LocusType.mono)
            LocusChip(decisionText(entry), tone = decisionPill(entry)?.second ?: Tone.Neutral)
        }
        SupportingText("In “${chat.title}”", style = LocusType.rowSupporting)
        CodeBlock("Arguments", pretty(entry.argumentsJson))
        CodeBlock(if (entry.failed) "Error" else "Result", pretty(entry.resultPreview).ifBlank { "(empty)" })
    }
}

@Composable
private fun CodeBlock(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SupportingText(label, style = LocusType.cardTitle)
        SelectionContainer {
            Text(
                text,
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.subtleFill), MaterialTheme.shapes.small)
                    .padding(Spacing.sm),
                style = LocusType.code,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------------

/** Indented JSON when [raw] is JSON, otherwise [raw] as it is. */
private fun pretty(raw: String): String {
    val t = raw.trim()
    if (!(t.startsWith("{") || t.startsWith("["))) return raw
    return runCatching {
        when (val v = JSONTokener(t).nextValue()) {
            is JSONObject -> v.toString(2)
            is JSONArray -> v.toString(2)
            else -> raw
        }.replace("\\/", "/") // org.json escapes slashes, which makes paths and URLs hard to read
    }.getOrDefault(raw)
}

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** "2:31 – 2:44 PM", localised; a single time when the run took under a minute. */
private fun timeRange(context: Context, start: Long, end: Long): String =
    if (end - start < 60_000) timeOf(start)
    else DateUtils.formatDateRange(context, start, end, DateUtils.FORMAT_SHOW_TIME)

private fun plural(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"


/** Automatic successes are the norm and get no pill; everything else says what happened. */
private fun decisionPill(entry: AuditEntry): Pair<String, Tone>? = when (entry.decision) {
    ApprovalDecision.AUTO_ALLOWED -> if (entry.isError) "Failed" to Tone.Critical else null
    ApprovalDecision.USER_APPROVED -> if (entry.isError) "Failed" to Tone.Critical else "Approved" to Tone.Brand
    ApprovalDecision.REVIEWER_ALLOWED -> if (entry.isError) "Failed" to Tone.Critical else "Reviewer allowed" to Tone.Neutral
    ApprovalDecision.REMEMBERED_ALLOWED -> if (entry.isError) "Failed" to Tone.Critical else "Remembered approval" to Tone.Neutral
    ApprovalDecision.USER_DENIED -> "Declined" to Tone.Caution
    ApprovalDecision.POLICY_DENIED -> "Blocked" to Tone.Caution
    ApprovalDecision.REVIEWER_DENIED -> "Reviewer blocked" to Tone.Caution
    ApprovalDecision.INVALID_INPUT -> "Invalid" to Tone.Critical
}

/** The full decision for the detail sheet, including the quiet automatic case. */
private fun decisionText(entry: AuditEntry): String = decisionPill(entry)?.first ?: "Ran automatically"

private fun statusTone(s: StatusFilter): Tone = when (s) {
    StatusFilter.Failed -> Tone.Critical
    StatusFilter.Approved -> Tone.Brand
    StatusFilter.Declined, StatusFilter.Blocked -> Tone.Caution
    StatusFilter.Reviewer -> Tone.Neutral
}

private fun accentIcon(a: dev.locus.core.designsystem.Accent) = when (a) {
    dev.locus.core.designsystem.Accent.Files -> LocusIcons.File
    dev.locus.core.designsystem.Accent.Web -> LocusIcons.Globe
    dev.locus.core.designsystem.Accent.Linux -> LocusIcons.Terminal
    dev.locus.core.designsystem.Accent.Memory -> LocusIcons.Library
    dev.locus.core.designsystem.Accent.Device -> LocusIcons.Phone
    dev.locus.core.designsystem.Accent.Connector -> LocusIcons.Plug
    dev.locus.core.designsystem.Accent.Agent -> LocusIcons.Sparkle
}
