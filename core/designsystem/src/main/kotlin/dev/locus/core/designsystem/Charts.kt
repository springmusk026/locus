package dev.locus.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Small charts for a card: stacked bars over time, a split bar, and the legend that names their colours.

/** How tall a [BarChart] is. */
enum class ChartSize(val height: Dp) {
    /** The chart a card is about: tokens per day. */
    Regular(96.dp),

    /** A chart under a card's figures: spend or tool calls per day. */
    Compact(72.dp),
}

/**
 * Stacked bars, one per bucket (a day or a week), [colors] bottom to top for each bucket's [values].
 * Tap a bar to select it (the caller shows what it holds) and tap it again to clear. An empty bucket
 * keeps a hairline stub so the time axis stays visible. TalkBack reads [description].
 *
 * Colours come from the theme or a [Tone]; a quieter series in the same hue uses [Emphasis.series].
 */
@Composable
fun BarChart(
    values: List<List<Float>>,
    colors: List<Color>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    size: ChartSize = ChartSize.Regular,
) {
    val track = Locus.colors.track
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.subtleFill)
    val max = values.maxOfOrNull { it.sum() }?.takeIf { it > 0f } ?: 1f
    Canvas(
        modifier.fillMaxWidth().height(size.height)
            .semantics { contentDescription = description }
            .pointerInput(values.size, selected) {
                detectTapGestures { offset ->
                    if (values.isEmpty()) return@detectTapGestures
                    val i = (offset.x / (this.size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
                    onSelect(if (i == selected) null else i)
                }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val slot = this.size.width / values.size
        val gap = (slot * BarGapShare).coerceIn(Stroke.regular.toPx(), BarMaxGap.toPx())
        val barWidth = (slot - gap).coerceAtLeast(1f)
        val radius = CornerRadius((barWidth / 2).coerceAtMost(BarRadius.toPx()))
        values.forEachIndexed { i, stack ->
            val x = i * slot + gap / 2
            if (i == selected) drawRoundRect(highlight, Offset(i * slot, 0f), Size(slot, this.size.height), CornerRadius(Spacing.xs.toPx()))
            val total = stack.sum()
            if (total <= 0f) {
                drawRect(track, Offset(x, this.size.height - Stroke.regular.toPx()), Size(barWidth, Stroke.regular.toPx()))
                return@forEachIndexed
            }
            var bottom = this.size.height
            stack.forEachIndexed { s, v ->
                if (v <= 0f) return@forEachIndexed
                val h = (v / max * this.size.height).coerceAtLeast(1.5f)
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

/** The share of a bar's slot left as a gap, between [Stroke.regular] and [BarMaxGap]. */
private const val BarGapShare = 0.28f
private val BarMaxGap = 6.dp

/** The most a bar's top is rounded; thin bars round to half their width. */
private val BarRadius = 3.dp

/**
 * One bar split into parts by count (approvals: auto, approved, blocked), each in its own colour, with
 * a small gap between them. Parts with nothing in them are left out.
 */
@Composable
fun SplitBar(parts: List<Pair<Int, Color>>, modifier: Modifier = Modifier, size: MeterSize = MeterSize.Large) {
    val shown = parts.filter { it.first > 0 }
    val total = shown.sumOf { it.first }.coerceAtLeast(1)
    Row(
        modifier.fillMaxWidth().height(size.height).clip(Radius.pill).background(if (shown.isEmpty()) Locus.colors.track else Color.Transparent),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        shown.forEach { (n, color) -> Box(Modifier.weight(n.toFloat() / total).fillMaxSize().background(color)) }
    }
}

/** A chart's key: a dot in the series' colour and a mono label ("in 12k"). */
@Composable
fun ChartLegend(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(IconSize.dot).background(color, CircleShape))
        SupportingText(text, Modifier.padding(start = Spacing.xs), style = LocusType.mono, maxLines = 1)
    }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun ChartsPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                val brand = MaterialTheme.colorScheme.primary
                val quiet = brand.copy(alpha = Emphasis.series)
                var selected by remember { mutableStateOf<Int?>(3) }
                LocusCard {
                    BarChart(
                        values = listOf(listOf(4f, 2f), listOf(0f, 0f), listOf(6f, 3f), listOf(3f, 5f), listOf(8f, 2f), listOf(1f, 1f), listOf(5f, 4f)),
                        colors = listOf(quiet, brand),
                        selected = selected,
                        onSelect = { selected = it },
                        description = "Tokens per day",
                    )
                    Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ChartLegend(quiet, "in 24k")
                        ChartLegend(brand, "out 17k")
                    }
                }
                LocusCard {
                    BarChart(listOf(listOf(2f, 1f), listOf(3f, 0f), listOf(1f, 2f)), listOf(MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.series), Locus.colors.critical), null, {}, "Tool calls", size = ChartSize.Compact)
                    SplitBar(listOf(12 to MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.series), 5 to brand, 1 to Locus.colors.caution), Modifier.padding(top = Spacing.md))
                }
            }
        }
    }
}
