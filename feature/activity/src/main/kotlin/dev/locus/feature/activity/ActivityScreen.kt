package dev.locus.feature.activity

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.accentFor
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.cardSegment
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.toolLabel
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.DateFormat
import java.util.Date

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
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    remember(viewModel) { viewModel.start(conversationId); true }
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
        if (state.loading) return@Scaffold
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
            if (today.failed > 0) Tone.Critical else Tone.Neutral,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Search and filters
// ---------------------------------------------------------------------------------------------

@Composable
private fun Filters(filters: ActivityFilters, inChat: Boolean, viewModel: ActivityViewModel) {
    Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SearchField(filters.query, if (inChat) "Search this chat's actions" else "Search actions and chats", viewModel::setQuery)
        Segmented(
            TimeRange.entries.map { it.label },
            selected = filters.range.ordinal,
            onSelect = { viewModel.setRange(TimeRange.entries[it]) },
            modifier = Modifier.fillMaxWidth(),
            compact = true,
        )
        ChipRow {
            KindFilter.entries.forEach { k ->
                FilterPill(k.label, selected = filters.kind == k, dot = { k.accent.color() }) {
                    viewModel.setKind(if (filters.kind == k) null else k)
                }
            }
        }
        ChipRow {
            StatusFilter.entries.forEach { s ->
                FilterPill(s.label, selected = filters.status == s, dot = { statusTone(s).takeIf { it != Tone.Neutral }?.color() }) {
                    viewModel.setStatus(if (filters.status == s) null else s)
                }
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

/** A filter chip. The user's choice is in brand colour; the dot says what the chip is about. */
@Composable
private fun FilterPill(label: String, selected: Boolean, dot: @Composable () -> androidx.compose.ui.graphics.Color?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val brand = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(if (selected) brand.copy(alpha = 0.14f) else Locus.colors.card)
            .border(0.5.dp, if (selected) brand.copy(alpha = 0.5f) else Locus.colors.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) {
            Icon(LocusIcons.Check, contentDescription = null, tint = brand, modifier = Modifier.size(14.dp))
        } else {
            dot()?.let { Box(Modifier.size(7.dp).background(it, CircleShape)) }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) brand else MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun SearchField(query: String, placeholder: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth()
            .background(Locus.colors.card, RoundedCornerShape(Radius.sheet))
            .border(0.5.dp, Locus.colors.hairline, RoundedCornerShape(Radius.sheet))
            .padding(start = Spacing.lg, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LocusIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.md, vertical = Spacing.md),
            decorationBox = { inner ->
                if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            },
        )
        if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(LocusIcons.Close, contentDescription = "Clear search") }
    }
}

@Composable
private fun NoResults(filters: ActivityFilters, onClear: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.xxl, start = Spacing.xl, end = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(LocusIcons.Search, size = 48.dp)
        Text(
            if (filters.query.isBlank() && filters.kind == null && filters.status == null) "Nothing in this period" else "No matching actions",
            Modifier.padding(top = Spacing.lg),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Try a wider time range or fewer filters.",
            Modifier.padding(top = Spacing.xs).widthIn(max = 320.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClear, Modifier.padding(top = Spacing.sm)) { Text("Clear filters") }
    }
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
                style = MaterialTheme.typography.labelLarge,
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
            if (p != null) ProviderBadge(p.name, p.baseUrl, p.onDevice, size = 28.dp, model = p.model) else IconTile(LocusIcons.Chats, size = 28.dp)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(
                    if (inChat) timeRange(context, run.start, run.end) else run.chat.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!inChat) Text(timeRange(context, run.start, run.end), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(LocusIcons.ChevronRight, contentDescription = if (expanded) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).rotate(turn))
        }
        Row(Modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            run.accents.take(4).forEach { a ->
                AccentTile(a, accentIcon(a), size = 20.dp)
                Spacer(Modifier.width(4.dp))
            }
            val critical = Locus.colors.critical
            val counts = buildAnnotatedString {
                append(plural(run.entries.size, "action"))
                if (run.failed > 0) {
                    append(" · ")
                    withStyle(SpanStyle(color = critical)) { append("${run.failed} failed") }
                }
                if (run.asked > 0) append(" · " + plural(run.asked, "approval"))
            }
            Text(counts, Modifier.padding(start = Spacing.xs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (!expanded) {
            val e = run.latest
            Text(
                "Last: " + toolLabel(e.toolName, e.argumentsJson, done = !e.isError && !e.refused),
                Modifier.padding(top = Spacing.xs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One call in a run's timeline: time, kind, what it did, and only the outcomes worth noticing. */
@Composable
private fun CallRow(entry: AuditEntry, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(timeOf(entry.startedAt), Modifier.width(64.dp), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        val (accent, icon) = accentFor(entry.toolName)
        AccentTile(accent, icon, size = 20.dp)
        Text(
            toolLabel(entry.toolName, entry.argumentsJson, done = !entry.isError && !entry.refused),
            Modifier.weight(1f).padding(horizontal = Spacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        decisionPill(entry)?.let { (label, tone) -> StatusPill(label, tone, Modifier.padding(end = Spacing.sm)) }
        Text(durationLabel(entry.finishedAt - entry.startedAt), Modifier.widthIn(min = 44.dp), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
            AccentTile(accent, icon, size = 24.dp)
            Text(entry.toolName, Modifier.weight(1f).padding(horizontal = Spacing.sm), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusPill(decisionText(entry), decisionPill(entry)?.second ?: Tone.Neutral)
        }
        Text("In “${chat.title}”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CodeBlock("Arguments", pretty(entry.argumentsJson))
        CodeBlock(if (entry.failed) "Error" else "Result", pretty(entry.resultPreview).ifBlank { "(empty)" })
    }
}

@Composable
private fun CodeBlock(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                text,
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(Radius.chip))
                    .padding(Spacing.sm),
                style = CodeTextStyle,
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

/** Fixed-width so a column of durations lines up: "0.4s", "12s", "3m 05s". */
private fun durationLabel(ms: Long): String = when {
    ms < 0 -> "—"
    ms < 10_000 -> "%.1fs".format(ms / 1000.0)
    ms < 60_000 -> "${ms / 1000}s"
    else -> "%dm %02ds".format(ms / 60_000, (ms / 1000) % 60)
}

/** Automatic successes are the norm and get no pill; everything else says what happened. */
private fun decisionPill(entry: AuditEntry): Pair<String, Tone>? = when (entry.decision) {
    ApprovalDecision.AUTO_ALLOWED -> if (entry.isError) "Failed" to Tone.Critical else null
    ApprovalDecision.USER_APPROVED -> if (entry.isError) "Failed" to Tone.Critical else "Approved" to Tone.Brand
    ApprovalDecision.REVIEWER_ALLOWED -> if (entry.isError) "Failed" to Tone.Critical else "Reviewer allowed" to Tone.Neutral
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
