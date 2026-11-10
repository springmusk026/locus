package dev.locus.feature.analytics

import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.data.repository.AnalyticsPeriod
import dev.locus.core.data.repository.AnalyticsReport
import dev.locus.core.data.repository.CostEstimate
import dev.locus.core.data.repository.CostReport
import dev.locus.core.data.repository.ModelCost
import dev.locus.core.data.repository.ModelList
import dev.locus.core.data.repository.ModelUsage
import dev.locus.core.data.repository.ToolStats
import dev.locus.core.data.repository.UsageBucket
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.BarChart
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChartLegend
import dev.locus.core.designsystem.ChartSize
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.SplitBar
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.onContainer
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.model.SettingsDestination
import dev.locus.core.designsystem.durationLabel
import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.model.toolActivity
import dev.locus.core.designsystem.ModelLogo
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.accentFor
import dev.locus.core.designsystem.compactCount
import dev.locus.core.designsystem.entrance
import dev.locus.core.model.UsageSource
import dev.locus.core.model.toolLabelText
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.localizedToolActivity
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
    AnalyticsScreen(state, onBack = onBack, onPeriod = viewModel::setPeriod, onOpenConversation = onOpenConversation, onTurnOnPrices = viewModel::turnOnPrices)
}

@Composable
internal fun AnalyticsScreen(
    state: AnalyticsUiState,
    onBack: () -> Unit,
    onPeriod: (AnalyticsPeriod) -> Unit,
    onOpenConversation: (String) -> Unit,
    onTurnOnPrices: () -> Unit = {},
) {
    val report = state.report
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                stringResource(R.string.analytics_screen_title),
                onBack = onBack,
                subtitle = stringResource(R.string.analytics_screen_subtitle),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.sm, bottom = padding.calculateBottomPadding() + Spacing.xxl),
        ) {
            item(key = "period") {
                Segmented(
                    options = listOf(stringResource(R.string.analytics_period_week), stringResource(R.string.analytics_period_month), stringResource(R.string.analytics_period_all)),
                    selected = state.period.ordinal,
                    onSelect = { onPeriod(AnalyticsPeriod.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when {
                report == null -> item(key = "loading") { LoadingState(Modifier.fillParentMaxHeight(StateHeight)) }
                report.isEmpty -> item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.analytics_empty_title),
                        body = stringResource(R.string.analytics_empty_body),
                        icon = LocusIcons.Activity,
                        modifier = Modifier.fillParentMaxHeight(StateHeight),
                    )
                }
                else -> {
                    item(key = "hero") { TokensCard(report, Modifier.padding(top = Spacing.md).entrance(0)) }
                    item(key = "speed") { SpeedRow(report, Modifier.padding(top = Spacing.md).entrance(1)) }
                    item(key = "cost-label") { SectionLabel(stringResource(R.string.analytics_section_spend)) }
                    item(key = "cost") { CostCard(report, state.catalog != null, state.prices, onTurnOnPrices, Modifier.entrance(1)) }
                    // A breakdown is only worth showing once something has a price.
                    if (report.cost.models.any { it.cost.priced > 0 }) {
                        item(key = "cost-models-label") { SectionLabel(stringResource(R.string.analytics_section_spend_models)) }
                        item(key = "cost-models") { CostRows(report.cost.models.map { m -> costModelTitle(m, report, state.catalog, state.listed) to m.cost }, Modifier.entrance(2)) }
                        item(key = "cost-sources-label") { SectionLabel(stringResource(R.string.analytics_section_spend_sources)) }
                        item(key = "cost-sources") { CostRows(report.cost.sources.map { sourceLabel(it.source) to it.cost }, Modifier.entrance(2)) }
                    }
                    if (report.models.isNotEmpty()) {
                        item(key = "models-label") { SectionLabel(stringResource(R.string.analytics_section_models)) }
                        item(key = "models") { ModelsCard(report, state.catalog, state.listed, Modifier.entrance(2)) }
                    }
                    // Only worth a section once something besides chat replies used tokens.
                    if (report.sources.any { it.source != UsageSource.CHAT }) {
                        item(key = "sources-label") { SectionLabel(stringResource(R.string.analytics_section_sources)) }
                        item(key = "sources") { SourcesCard(report, Modifier.entrance(2)) }
                    }
                    if (report.tools.calls > 0) {
                        item(key = "tools-label") { SectionLabel(stringResource(R.string.analytics_section_tools)) }
                        item(key = "tools") { ToolsCard(report, Modifier.entrance(3)) }
                        if (report.tools.slowest.isNotEmpty()) {
                            item(key = "slow-label") { SectionLabel(stringResource(R.string.analytics_section_slowest)) }
                            item(key = "slow-note") { SectionNote(stringResource(R.string.analytics_section_slowest_note), Modifier.padding(start = Spacing.xs)) }
                            item(key = "slow") { SlowestTools(report.tools, Modifier.entrance(4)) }
                        }
                    }
                    if (report.chats.isNotEmpty()) {
                        item(key = "chats-label") { SectionLabel(stringResource(R.string.analytics_section_chats)) }
                        item(key = "chats") { BusiestChats(report, onOpenConversation, Modifier.entrance(5)) }
                    }
                    if (report.tasks.isNotEmpty()) {
                        item(key = "tasks-label") { SectionLabel(stringResource(R.string.analytics_section_tasks)) }
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
    val inputColor = brand.copy(alpha = Emphasis.series)
    var selected by remember(report.period, report.buckets.size) { mutableStateOf<Int?>(null) }
    LocusCard(modifier, glow = brand) {
        SupportingText(stringResource(R.string.analytics_tokens_title), style = LocusType.label)
        Text(compactCount(t.total), style = LocusType.heroFigure, color = MaterialTheme.colorScheme.onSurface)
        // Wraps at a large font instead of breaking "63% cached" a letter a line.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            ChartLegend(inputColor, stringResource(R.string.analytics_tokens_input, compactCount(t.input)))
            ChartLegend(brand, stringResource(R.string.analytics_tokens_output, compactCount(t.output)))
            t.cachedShare?.let { SupportingText(stringResource(R.string.analytics_tokens_cached, (it * 100).roundToInt()), style = LocusType.mono) }
        }
        // The prompt cache at work: what was read from it and written to it (writes cost more than
        // fresh input, reads far less), so cache hits can be checked on the phone.
        if (t.cacheRead > 0 || t.cacheWrite > 0) {
            SupportingText(
                if (t.cacheWrite1h > 0) {
                    stringResource(R.string.analytics_tokens_cache_1h, compactCount(t.cacheRead), compactCount(t.cacheWrite), compactCount(t.cacheWrite1h))
                } else {
                    stringResource(R.string.analytics_tokens_cache, compactCount(t.cacheRead), compactCount(t.cacheWrite))
                },
                Modifier.padding(top = Spacing.xs),
                style = LocusType.mono,
            )
        }
        // Thinking is part of "out"; shown on its own line so the legend row stays one line on phones.
        if (t.reasoning > 0) {
            SupportingText(stringResource(R.string.analytics_tokens_thinking, compactCount(t.reasoning)), Modifier.padding(top = Spacing.xs), style = LocusType.mono)
        }
        val buckets = report.buckets
        BarChart(
            values = buckets.map { listOf(it.tokens.input.toFloat(), it.tokens.output.toFloat()) },
            colors = listOf(inputColor, brand),
            selected = selected,
            onSelect = { selected = it },
            description = stringResource(if (report.bucketDays == 1) R.string.analytics_tokens_chart_day else R.string.analytics_tokens_chart_week, compactCount(t.total)),
            modifier = Modifier.padding(top = Spacing.lg),
        )
        ChartCaption(report, selected) { b -> pluralStringResource(R.plurals.analytics_tokens_caption, b.requests, compactCount(b.tokens.total), b.requests) }
    }
}

/** Requests and how long replies take, as three quiet figures, and time to the first token when known. */
@Composable
private fun SpeedRow(report: AnalyticsReport, modifier: Modifier = Modifier) {
    LocusCard(modifier) {
        FigureRow {
            Figure(stringResource(R.string.analytics_speed_requests), compactCount(report.requests.toLong()), Modifier.weight(1f))
            Figure(stringResource(R.string.analytics_speed_median), report.replyMedianMs?.let { durationLabel(it) } ?: "–", Modifier.weight(1f))
            Figure(stringResource(R.string.analytics_speed_slowest), report.replyP95Ms?.let { durationLabel(it) } ?: "–", Modifier.weight(1f))
        }
        report.firstTokenMedianMs?.let { median ->
            SupportingText(
                report.firstTokenP95Ms?.let { stringResource(R.string.analytics_speed_first_token_slowest, durationLabel(median), durationLabel(it)) }
                    ?: stringResource(R.string.analytics_speed_first_token, durationLabel(median)),
                Modifier.padding(top = Spacing.sm),
                style = LocusType.mono,
            )
        }
    }
}

/**
 * Estimated spend from the opt-in models.dev prices: today, 7 and 30 days, and per bar of the period.
 * Always marked as an estimate; a span whose requests have no price says "Unknown", never $0.
 */
@Composable
private fun CostCard(report: AnalyticsReport, catalogLoaded: Boolean, prices: PricesSetup, onTurnOnPrices: () -> Unit, modifier: Modifier = Modifier) {
    val cost = report.cost
    val resources = LocalResources.current
    val brand = MaterialTheme.colorScheme.primary
    var selected by remember(report.period, report.buckets.size) { mutableStateOf<Int?>(null) }
    LocusCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SupportingText(stringResource(R.string.analytics_cost_title), Modifier.weight(1f), style = LocusType.label)
            LocusChip(stringResource(R.string.analytics_cost_estimate))
        }
        FigureRow(Modifier.padding(top = Spacing.sm)) {
            // With prices off every span reads the same "–"; the note under them says why.
            val spend = { c: CostEstimate -> if (cost.pricesOn) costLabel(resources, c) else "–" }
            Figure(stringResource(R.string.analytics_cost_today), spend(cost.today), Modifier.weight(1f))
            Figure(stringResource(R.string.analytics_cost_week), spend(cost.week), Modifier.weight(1f))
            Figure(stringResource(R.string.analytics_cost_month), spend(cost.month), Modifier.weight(1f))
        }
        costNote(resources, cost, catalogLoaded, prices)?.let { note ->
            Row(Modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (prices == PricesSetup.Fetching) InlineSpinner(size = IconSize.small)
                Text(
                    note,
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = LocusType.mono,
                    color = if (prices == PricesSetup.Failed) Tone.Critical.onContainer() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Prices are opt-in, so the card offers to turn them on where it says they're off.
        when (costAction(cost, catalogLoaded, prices)) {
            CostAction.TurnOn -> Row(Modifier.padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onTurnOnPrices) { Text(stringResource(R.string.analytics_cost_turn_on)) }
                Spacer(Modifier.weight(1f))
                SettingsLink(SettingsDestination.Models)
            }
            CostAction.TryAgain -> TextButton(onClick = onTurnOnPrices, modifier = Modifier.padding(top = Spacing.xs)) { Text(stringResource(R.string.analytics_cost_try_again)) }
            null -> Unit
        }
        if (cost.pricesOn && cost.buckets.size == report.buckets.size && cost.buckets.any { it.priced > 0 }) {
            BarChart(
                values = cost.buckets.map { listOf(it.usd.toFloat()) },
                colors = listOf(brand),
                selected = selected,
                onSelect = { selected = it },
                description = stringResource(if (report.bucketDays == 1) R.string.analytics_cost_chart_day else R.string.analytics_cost_chart_week, costLabel(resources, cost.period)),
                modifier = Modifier.padding(top = Spacing.lg),
                size = ChartSize.Compact,
            )
            ChartCaption(report, selected) { b -> costLabel(resources, cost.buckets[report.buckets.indexOf(b)]) }
        }
    }
}

/** One row per model or source: its estimated spend in the period, and how many requests had a price. */
@Composable
private fun CostRows(rows: List<Pair<String, CostEstimate>>, modifier: Modifier = Modifier) {
    val max = rows.maxOfOrNull { it.second.usd }?.takeIf { it > 0 } ?: 1.0
    CardGroup(modifier) {
        rows.forEachIndexed { i, (title, c) ->
            if (i > 0) GroupDivider()
            ListRow(
                title = title,
                meta = { if (c.priced > 0) ShareBar((c.usd / max).toFloat()) },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(costLabel(LocalResources.current, c), style = RowFigure)
                        SupportingText(
                            if (c.unpriced > 0 && c.priced > 0) {
                                stringResource(R.string.analytics_cost_row_unpriced, c.unpriced, c.requests)
                            } else {
                                stringResource(R.string.analytics_row_requests_short, c.requests)
                            },
                            style = LocusType.mono,
                        )
                    }
                },
            )
        }
    }
}

/** The model's name as [ModelsCard] shows it. */
private fun costModelTitle(m: ModelCost, report: AnalyticsReport, catalog: ModelCatalogIndex?, listed: Map<String, ModelList>): String =
    report.models.firstOrNull { it.providerId == m.providerId && it.model == m.model }?.let { modelTitle(it, catalog, listed) }
        ?: m.model.takeIf { it.isNotBlank() }?.let(dev.locus.core.model.ModelNames::short) ?: m.providerName

@Composable
private fun ModelsCard(report: AnalyticsReport, catalog: ModelCatalogIndex?, listed: Map<String, ModelList>, modifier: Modifier = Modifier) {
    val max = report.models.maxOf { it.tokens }.coerceAtLeast(1)
    CardGroup(modifier) {
        report.models.forEachIndexed { i, m ->
            if (i > 0) GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = modelTitle(m, catalog, listed),
                leading = {
                    if (m.model.isNotBlank()) ModelLogo(m.model, size = IconSize.tile) else ProviderBadge(m.providerName, m.providerUrl, m.onDevice, size = IconSize.tile)
                },
                meta = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (m.model.isNotBlank()) SupportingText(stringResource(R.string.analytics_models_via, m.providerName), style = LocusType.mono, maxLines = 1)
                        ShareBar(m.tokens.toFloat() / max)
                    }
                },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(compactCount(m.tokens), style = RowFigure)
                        SupportingText(stringResource(R.string.analytics_row_requests_short, m.requests), style = LocusType.mono)
                    }
                },
            )
        }
    }
}

/** Chat replies, helpers and side jobs (titles, summaries, the reviewer…), most tokens first. */
@Composable
private fun SourcesCard(report: AnalyticsReport, modifier: Modifier = Modifier) {
    val max = report.sources.maxOf { it.tokens }.coerceAtLeast(1)
    CardGroup(modifier) {
        report.sources.forEachIndexed { i, s ->
            if (i > 0) GroupDivider()
            ListRow(
                title = sourceLabel(s.source),
                meta = { ShareBar(s.tokens.toFloat() / max) },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(compactCount(s.tokens), style = RowFigure)
                        SupportingText(stringResource(R.string.analytics_row_requests_short, s.requests), style = LocusType.mono)
                    }
                },
            )
        }
    }
}

@Composable
internal fun sourceLabel(source: UsageSource): String = stringResource(
    when (source) {
        UsageSource.CHAT -> R.string.analytics_source_chat
        UsageSource.HELPER -> R.string.analytics_source_helper
        UsageSource.TITLE -> R.string.analytics_source_title
        UsageSource.SUGGESTIONS -> R.string.analytics_source_suggestions
        UsageSource.COMPACTION -> R.string.analytics_source_compaction
        UsageSource.REVIEWER -> R.string.analytics_source_reviewer
        UsageSource.REFLECTION -> R.string.analytics_source_reflection
        UsageSource.SIDE_QUESTION -> R.string.analytics_source_side_question
        UsageSource.OTHER -> R.string.analytics_source_other
    },
)

@Composable
private fun ToolsCard(report: AnalyticsReport, modifier: Modifier = Modifier) {
    val tools = report.tools
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.series)
    val critical = Locus.colors.critical
    var selected by remember(report.period, report.buckets.size) { mutableStateOf<Int?>(null) }
    LocusCard(modifier) {
        Row {
            Figure(stringResource(R.string.analytics_tools_calls), compactCount(tools.calls.toLong()), Modifier.weight(1f))
            Figure(stringResource(R.string.analytics_tools_succeeded), tools.successRate?.let { stringResource(R.string.analytics_tools_success_rate, (it * 100).roundToInt()) } ?: "–", Modifier.weight(1f))
            Figure(stringResource(R.string.analytics_tools_failed), tools.failures.toString(), Modifier.weight(1f), tone = if (tools.failures > 0) Tone.Critical else Tone.Neutral)
        }
        BarChart(
            values = report.buckets.map { listOf((it.toolCalls - it.toolFailures).toFloat(), it.toolFailures.toFloat()) },
            colors = listOf(neutral, critical),
            selected = selected,
            onSelect = { selected = it },
            description = stringResource(if (report.bucketDays == 1) R.string.analytics_tools_chart_day else R.string.analytics_tools_chart_week, tools.calls, tools.failures),
            modifier = Modifier.padding(top = Spacing.lg),
            size = ChartSize.Compact,
        )
        ChartCaption(report, selected) { b ->
            if (b.toolFailures > 0) {
                pluralStringResource(R.plurals.analytics_tools_caption_failed, b.toolCalls, b.toolCalls, b.toolFailures)
            } else {
                pluralStringResource(R.plurals.analytics_tools_caption, b.toolCalls, b.toolCalls)
            }
        }
        DecisionSplit(tools, Modifier.padding(top = Spacing.lg))
    }
}

/** Who said yes: ran on its own, you approved, or blocked (by you or a policy). */
@Composable
private fun DecisionSplit(tools: ToolStats, modifier: Modifier = Modifier) {
    val auto = MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.series)
    val approved = MaterialTheme.colorScheme.primary
    val denied = Locus.colors.caution
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SupportingText(stringResource(R.string.analytics_tools_approvals), style = LocusType.label)
        SplitBar(listOf(tools.autoAllowed to auto, tools.userApproved to approved, tools.denied to denied))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ChartLegend(auto, stringResource(R.string.analytics_tools_auto, tools.autoAllowed))
            ChartLegend(approved, stringResource(R.string.analytics_tools_approved, tools.userApproved))
            ChartLegend(denied, stringResource(R.string.analytics_tools_blocked, tools.denied))
        }
    }
}

@Composable
private fun SlowestTools(tools: ToolStats, modifier: Modifier = Modifier) {
    CardGroup(modifier) {
        tools.slowest.forEachIndexed { i, t ->
            if (i > 0) GroupDivider(inset = Spacing.lg + IconSize.tileSmall + Spacing.md)
            val (accent, icon) = accentFor(t.name)
            val callsText = pluralStringResource(R.plurals.analytics_slow_calls, t.calls, t.name, t.calls)
            val failedText = if (t.failures > 0) stringResource(R.string.analytics_slow_failed, t.failures) else ""
            ListRow(
                title = slowToolTitle(LocalResources.current, t.name),
                leading = { AccentTile(accent, icon) },
                meta = {
                    SupportingText(
                        buildAnnotatedString {
                            append(callsText)
                            if (t.failures > 0) withStyle(SpanStyle(color = Locus.colors.critical)) { append(" · $failedText") }
                        },
                        style = LocusType.mono,
                    )
                },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(durationLabel(t.p95Ms), style = RowFigure)
                        SupportingText(stringResource(R.string.analytics_slow_average, durationLabel(t.avgMs)), style = LocusType.mono)
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
                    SupportingText(
                        stringResource(R.string.analytics_chats_summary, compactCount(c.tokens), c.requests, c.toolCalls),
                        style = LocusType.mono,
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
                    SupportingText(
                        listOfNotNull(
                            pluralStringResource(R.plurals.analytics_tasks_runs, t.runs, t.runs),
                            t.lastRunAt?.let { stringResource(R.string.analytics_tasks_last_run, relativeTime(it).lowercase()) },
                        ).joinToString(" · "),
                        style = LocusType.mono,
                        maxLines = 1,
                    )
                },
                trailing = {
                    when (t.enabled) {
                        false -> LocusChip(stringResource(R.string.analytics_tasks_paused))
                        null -> LocusChip(stringResource(R.string.analytics_tasks_removed))
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

/** Three figures side by side; two to a line at a large font, so a label never breaks mid-word. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FigureRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        maxItemsInEachRow = if (large) 2 else 3,
        content = content,
    )
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    // Label and value are one item for TalkBack: "Requests, 1.2k".
    Column(modifier.semantics(mergeDescendants = true) {}) {
        // Two lines at a large font rather than "Reque…".
        SupportingText(label, style = LocusType.chip, maxLines = 2)
        Text(
            value,
            style = LocusType.figure,
            color = if (tone == Tone.Critical) Locus.colors.critical else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** A share of the biggest row in a breakdown (spend, tokens). Never empty, so a small share still shows. */
@Composable
private fun ShareBar(fraction: Float) {
    Meter(fraction.coerceIn(0.02f, 1f), tone = Tone.Brand, contentDescription = stringResource(R.string.analytics_share_description))
}

/** A row's figure on the right: its value at row-title size, in mono so columns of numbers line up. */
private val RowFigure: TextStyle
    @Composable get() = LocusType.rowTitle.copy(fontFamily = FontFamily.Monospace)

/** How much of the screen the loading and empty states take under the period switch. */
private const val StateHeight = 0.6f

/** Under a chart: its date range, or the tapped bar's date and value. */
@Composable
private fun ChartCaption(report: AnalyticsReport, selected: Int?, describe: @Composable (UsageBucket) -> String) {
    val buckets = report.buckets
    Row(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        val bucket = selected?.let { buckets.getOrNull(it) }
        if (bucket != null) {
            Text(if (report.bucketDays == 7) stringResource(R.string.analytics_chart_week_of, date(bucket.start)) else date(bucket.start), style = LocusType.mono)
            Spacer(Modifier.weight(1f))
            Text(describe(bucket), style = LocusType.mono)
        } else {
            SupportingText(date(buckets.first().start), style = LocusType.mono)
            Spacer(Modifier.weight(1f))
            SupportingText(stringResource(R.string.analytics_chart_today), style = LocusType.mono)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------------

// Built per call so a locale change while the app runs is picked up.
private fun date(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))

/**
 * An estimated spend in words: "≈$1.24", "≈$1.24+" when some requests had no price, "<$0.01",
 * "Unknown" when none had one (never $0), and "–" when nothing ran.
 */
internal fun costLabel(resources: Resources, c: CostEstimate): String = when {
    c.requests == 0 -> "–"
    c.unknown -> resources.getString(R.string.analytics_cost_unknown)
    else -> {
        val amount = when {
            c.usd <= 0.0 -> "$0.00"
            c.usd < 0.01 -> "<$0.01"
            else -> "≈" + String.format(Locale.US, "$%.2f", c.usd)
        }
        amount + if (c.partial) "+" else ""
    }
}

/**
 * Why the spend reads as it does, when it isn't simply priced: prices off, being fetched, not loaded,
 * or some requests unpriced. [catalogLoaded]: the models.dev list is in memory; [setup]: the card's
 * "Turn on prices" action.
 */
internal fun costNote(resources: Resources, cost: CostReport, catalogLoaded: Boolean = true, setup: PricesSetup = PricesSetup.Idle): String? = when {
    setup == PricesSetup.Fetching -> resources.getString(R.string.analytics_cost_note_fetching)
    !cost.pricesOn -> resources.getString(R.string.analytics_cost_note_off)
    setup == PricesSetup.Failed -> resources.getString(R.string.analytics_cost_note_failed)
    !catalogLoaded -> resources.getString(R.string.analytics_cost_note_not_loaded)
    cost.month.unknown -> resources.getString(R.string.analytics_cost_note_no_price)
    cost.month.unpriced > 0 ->
        resources.getQuantityString(R.plurals.analytics_cost_note_unpriced, cost.month.requests, cost.month.unpriced, cost.month.requests)
    cost.month.requests > 0 -> resources.getString(R.string.analytics_cost_note_priced)
    else -> null
}

/** The spend card's action under its note. */
internal enum class CostAction { TurnOn, TryAgain }

/** "Turn on prices" while they're off; "Try again" when they're on but couldn't be loaded; else none. */
internal fun costAction(cost: CostReport, catalogLoaded: Boolean, setup: PricesSetup): CostAction? = when {
    setup == PricesSetup.Fetching -> null
    !cost.pricesOn -> CostAction.TurnOn
    setup == PricesSetup.Failed || !catalogLoaded -> CostAction.TryAgain
    else -> null
}

/**
 * What the tool does, in a few words ("Ask a helper", "Wait for helpers"); its kind of work when the
 * wording needs arguments. In the user's language; the choice is made on core/model's English label.
 */
internal fun slowToolTitle(resources: android.content.res.Resources, name: String): String {
    val label = toolLabelText(name, "{}", done = false)
    return if (label.english.let { '…' in it || it == name }) localizedToolActivity(resources, name) else label.asString(resources)
}

/**
 * The name the model picker shows: the provider's own name for the model, else the models.dev
 * catalogue's, else its id as every screen shortens it ([dev.locus.core.model.ModelNames.short]).
 */
internal fun modelTitle(m: ModelUsage, catalog: ModelCatalogIndex?, listed: Map<String, ModelList>): String {
    if (m.model.isBlank()) return m.providerName
    val fromProvider = m.providerId?.let { (listed[it] as? ModelList.Loaded)?.models?.firstOrNull { r -> r.id == m.model }?.displayName }
    return fromProvider?.takeIf { it.isNotBlank() && it != m.model }
        ?: m.format?.let { catalog?.lookup(m.providerUrl, it, m.model)?.name?.takeIf(String::isNotBlank) }
        ?: dev.locus.core.model.ModelNames.short(m.model)
}

