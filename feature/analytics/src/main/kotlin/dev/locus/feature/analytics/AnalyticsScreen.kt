package dev.locus.feature.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.data.repository.AnalyticsPeriod
import dev.locus.core.data.repository.AnalyticsReport
import dev.locus.core.data.repository.ToolStats
import dev.locus.core.data.repository.UsageBucket
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.ModelLogo
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.accentFor
import dev.locus.core.designsystem.compactCount
import dev.locus.core.designsystem.entrance
import dev.locus.core.designsystem.relativeTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AnalyticsRoute(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AnalyticsScreen(state, onBack = onBack, onPeriod = viewModel::setPeriod, onOpenConversation = onOpenConversation)
}

private val Mono = FontFamily.Monospace

@Composable
internal fun AnalyticsScreen(
    state: AnalyticsUiState,
    onBack: () -> Unit,
    onPeriod: (AnalyticsPeriod) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val report = state.report
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                "Insights",
                onBack = onBack,
                status = { Text("Counted on this phone from your chats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.sm, bottom = padding.calculateBottomPadding() + Spacing.xxl),
        ) {
            item(key = "period") {
                Segmented(
                    options = listOf("7 days", "30 days", "All time"),
                    selected = state.period.ordinal,
                    onSelect = { onPeriod(AnalyticsPeriod.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when {
                report == null -> Unit
                report.isEmpty -> item(key = "empty") {
                    EmptyState(
                        title = "Nothing to count yet",
                        body = "Token use, reply times and tool calls show up here once you've chatted with Locus in this period.",
                        icon = LocusIcons.Activity,
                        modifier = Modifier.height(360.dp),
                    )
                }
                else -> {
                    item(key = "hero") { TokensCard(report, Modifier.padding(top = Spacing.md).entrance(0)) }
                    item(key = "speed") { SpeedRow(report, Modifier.padding(top = Spacing.md).entrance(1)) }
                    if (report.models.isNotEmpty()) {
                        item(key = "models-label") { SectionLabel("By model", Modifier.padding(start = 0.dp)) }
                        item(key = "models") { ModelsCard(report, Modifier.entrance(2)) }
                    }
                    if (report.tools.calls > 0) {
                        item(key = "tools-label") { SectionLabel("Tools", Modifier.padding(start = 0.dp)) }
                        item(key = "tools") { ToolsCard(report, Modifier.entrance(3)) }
                        if (report.tools.slowest.isNotEmpty()) {
                            item(key = "slow-label") { SectionLabel("Slowest tools", Modifier.padding(start = 0.dp)) }
                            item(key = "slow-note") { SectionNote("Timed on calls that ran without asking you.", Modifier.padding(start = Spacing.xs)) }
                            item(key = "slow") { SlowestTools(report.tools, Modifier.entrance(4)) }
                        }
                    }
                    if (report.chats.isNotEmpty()) {
                        item(key = "chats-label") { SectionLabel("Busiest chats", Modifier.padding(start = 0.dp)) }
                        item(key = "chats") { BusiestChats(report, onOpenConversation, Modifier.entrance(5)) }
                    }
                    if (report.tasks.isNotEmpty()) {
                        item(key = "tasks-label") { SectionLabel("Scheduled tasks", Modifier.padding(start = 0.dp)) }
                        item(key = "tasks") { TasksCard(report, Modifier.entrance(6)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

/** The one hero: total tokens, the in/out split and cache share, and tokens per day. */
@Composable
private fun TokensCard(report: AnalyticsReport, modifier: Modifier = Modifier) {
    val t = report.tokens
    val brand = MaterialTheme.colorScheme.primary
    val inputColor = brand.copy(alpha = 0.38f)
    var selected by remember(report.period, report.buckets.size) { mutableStateOf<Int?>(null) }
    LocusCard(modifier, glow = brand) {
        Text("Tokens", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            compactCount(t.total),
            style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Legend(inputColor, "in ${compactCount(t.input)}")
            Legend(brand, "out ${compactCount(t.output)}")
            t.cachedShare?.let { Text("${(it * 100).roundToInt()}% cached", style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val buckets = report.buckets
        BarChart(
            values = buckets.map { listOf(it.tokens.input.toFloat(), it.tokens.output.toFloat()) },
            colors = listOf(inputColor, brand),
            selected = selected,
            onSelect = { selected = it },
            description = "Tokens per ${if (report.bucketDays == 1) "day" else "week"}, ${compactCount(t.total)} in total",
            modifier = Modifier.padding(top = Spacing.lg),
        )
        ChartCaption(report, selected) { b -> "${compactCount(b.tokens.total)} tokens · ${b.requests} ${if (b.requests == 1) "request" else "requests"}" }
    }
}

/** Requests and how long replies take, as three quiet figures. */
@Composable
private fun SpeedRow(report: AnalyticsReport, modifier: Modifier = Modifier) {
    LocusCard(modifier) {
        Row {
            Figure("Requests", compactCount(report.requests.toLong()), Modifier.weight(1f))
            Figure("Median reply", report.replyMedianMs?.let(::duration) ?: "–", Modifier.weight(1f))
            Figure("Slowest 5%", report.replyP95Ms?.let(::duration) ?: "–", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModelsCard(report: AnalyticsReport, modifier: Modifier = Modifier) {
    val max = report.models.maxOf { it.tokens }.coerceAtLeast(1)
    CardGroup(modifier) {
        report.models.forEachIndexed { i, m ->
            if (i > 0) GroupDivider(inset = Spacing.lg + 32.dp + Spacing.md)
            ListRow(
                title = m.model.ifBlank { m.providerName },
                leading = {
                    if (m.model.isNotBlank()) ModelLogo(m.model, size = 32.dp) else ProviderBadge(m.providerName, m.providerUrl, m.onDevice, size = 32.dp)
                },
                meta = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (m.model.isNotBlank()) Text("via ${m.providerName}", style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ShareBar(m.tokens.toFloat() / max)
                    }
                },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(compactCount(m.tokens), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono))
                        Text("${m.requests} req", style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
    }
}

@Composable
private fun ToolsCard(report: AnalyticsReport, modifier: Modifier = Modifier) {
    val tools = report.tools
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val critical = Locus.colors.critical
    var selected by remember(report.period, report.buckets.size) { mutableStateOf<Int?>(null) }
    LocusCard(modifier) {
        Row {
            Figure("Calls", compactCount(tools.calls.toLong()), Modifier.weight(1f))
            Figure("Succeeded", tools.successRate?.let { "${(it * 100).roundToInt()}%" } ?: "–", Modifier.weight(1f))
            Figure("Failed", tools.failures.toString(), Modifier.weight(1f), tone = if (tools.failures > 0) Tone.Critical else Tone.Neutral)
        }
        BarChart(
            values = report.buckets.map { listOf((it.toolCalls - it.toolFailures).toFloat(), it.toolFailures.toFloat()) },
            colors = listOf(neutral, critical),
            selected = selected,
            onSelect = { selected = it },
            description = "Tool calls per ${if (report.bucketDays == 1) "day" else "week"}: ${tools.calls}, ${tools.failures} failed",
            modifier = Modifier.padding(top = Spacing.lg),
            height = 72.dp,
        )
        ChartCaption(report, selected) { b -> "${b.toolCalls} calls" + if (b.toolFailures > 0) " · ${b.toolFailures} failed" else "" }
        DecisionSplit(tools, Modifier.padding(top = Spacing.lg))
    }
}

/** Who said yes: ran on its own, you approved, or blocked (by you or a policy). */
@Composable
private fun DecisionSplit(tools: ToolStats, modifier: Modifier = Modifier) {
    val auto = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val approved = MaterialTheme.colorScheme.primary
    val denied = Locus.colors.caution
    val parts = listOf(tools.autoAllowed to auto, tools.userApproved to approved, tools.denied to denied).filter { it.first > 0 }
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Approvals", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.forEach { (n, color) -> Box(Modifier.weight(n.toFloat() / total).fillMaxSize().background(color)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Legend(auto, "auto ${tools.autoAllowed}")
            Legend(approved, "you approved ${tools.userApproved}")
            Legend(denied, "blocked ${tools.denied}")
        }
    }
}

@Composable
private fun SlowestTools(tools: ToolStats, modifier: Modifier = Modifier) {
    CardGroup(modifier) {
        tools.slowest.forEachIndexed { i, t ->
            if (i > 0) GroupDivider(inset = Spacing.lg + 28.dp + Spacing.md)
            val (accent, icon) = accentFor(t.name)
            ListRow(
                title = t.name,
                leading = { AccentTile(accent, icon) },
                meta = {
                    Text(
                        "${t.calls} calls" + if (t.failures > 0) " · ${t.failures} failed" else "",
                        style = MonoCaption,
                        color = if (t.failures > 0) Locus.colors.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(duration(t.p95Ms), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono))
                        Text("avg ${duration(t.avgMs)}", style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
    }
}

@Composable
private fun BusiestChats(report: AnalyticsReport, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    CardGroup(modifier) {
        report.chats.forEachIndexed { i, c ->
            if (i > 0) GroupDivider()
            ListRow(
                title = c.title,
                onClick = { onOpen(c.conversationId) },
                meta = {
                    Text(
                        "${compactCount(c.tokens)} tokens · ${c.requests} req · ${c.toolCalls} tools",
                        style = MonoCaption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun TasksCard(report: AnalyticsReport, modifier: Modifier = Modifier) {
    CardGroup(modifier) {
        report.tasks.forEachIndexed { i, t ->
            if (i > 0) GroupDivider()
            ListRow(
                title = t.title,
                meta = {
                    Text(
                        listOfNotNull("${t.runs} ${if (t.runs == 1) "run" else "runs"}", t.lastRunAt?.let { "last ${relativeTime(it).lowercase()}" }).joinToString(" · "),
                        style = MonoCaption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                },
                trailing = {
                    when (t.enabled) {
                        false -> StatusPill("Paused", Tone.Neutral)
                        null -> StatusPill("Removed", Tone.Neutral)
                        true -> Unit
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Parts
// ---------------------------------------------------------------------------------------------

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = Mono),
            color = if (tone == Tone.Critical) Locus.colors.critical else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, Modifier.padding(start = 6.dp), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun ShareBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Locus.colors.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)))
    }
}

/** Under a chart: its date range, or the tapped bar's date and value. */
@Composable
private fun ChartCaption(report: AnalyticsReport, selected: Int?, describe: (UsageBucket) -> String) {
    val buckets = report.buckets
    Row(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        val bucket = selected?.let { buckets.getOrNull(it) }
        if (bucket != null) {
            Text(
                (if (report.bucketDays == 7) "Week of " else "") + date(bucket.start),
                style = MonoCaption,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(describe(bucket), style = MonoCaption, color = MaterialTheme.colorScheme.onSurface)
        } else {
            Text(date(buckets.first().start), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("Today", style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Stacked bars drawn on a Canvas: one bar per bucket, [colors] bottom to top. Tap a bar to read it;
 * tap it again to clear. Empty buckets keep a hairline stub so the time axis stays visible.
 */
@Composable
private fun BarChart(
    values: List<List<Float>>,
    colors: List<Color>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 96.dp,
) {
    val track = Locus.colors.track
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val max = values.maxOfOrNull { it.sum() }?.takeIf { it > 0f } ?: 1f
    Canvas(
        modifier.fillMaxWidth().height(height)
            .semantics { contentDescription = description }
            .pointerInput(values.size, selected) {
                detectTapGestures { offset ->
                    val i = (offset.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
                    onSelect(if (i == selected) null else i)
                }
            },
    ) {
        val slot = size.width / values.size
        val gap = (slot * 0.28f).coerceIn(1.dp.toPx(), 6.dp.toPx())
        val barWidth = (slot - gap).coerceAtLeast(1f)
        val radius = CornerRadius((barWidth / 2).coerceAtMost(3.dp.toPx()))
        values.forEachIndexed { i, stack ->
            val x = i * slot + gap / 2
            if (i == selected) drawRoundRect(highlight, Offset(i * slot, 0f), Size(slot, size.height), CornerRadius(4.dp.toPx()))
            val total = stack.sum()
            if (total <= 0f) {
                drawRect(track, Offset(x, size.height - 1.dp.toPx()), Size(barWidth, 1.dp.toPx()))
                return@forEachIndexed
            }
            var bottom = size.height
            stack.forEachIndexed { s, v ->
                if (v <= 0f) return@forEachIndexed
                val h = (v / max * size.height).coerceAtLeast(1.5f)
                val top = bottom - h
                val isTop = stack.drop(s + 1).all { it <= 0f }
                if (isTop) {
                    // Round only the top of the bar: a rounded rect plus a square patch over its lower corners.
                    drawRoundRect(colors[s], Offset(x, top), Size(barWidth, h), radius)
                    if (h > radius.y) drawRect(colors[s], Offset(x, top + radius.y), Size(barWidth, h - radius.y))
                } else {
                    drawRect(colors[s], Offset(x, top), Size(barWidth, h))
                }
                bottom = top
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------------

// Built per call so a locale change while the app runs is picked up.
private fun date(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))

/** 420 → "420ms", 3_400 → "3.4s", 126_000 → "2.1m". */
internal fun duration(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(Locale.US, ms / 1_000.0)
    else -> "%.1fm".format(Locale.US, ms / 60_000.0)
}
