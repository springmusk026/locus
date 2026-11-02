package dev.locus.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A [Meter]'s thickness. Four sizes cover the hand-made bars the audit found (2, 3, 4, 6 and 8dp). */
enum class MeterSize(val height: Dp) {
    /** A page-load line under a top bar. */
    Hairline(2.dp),

    /** Usage inside a row or card: a budget, a share of the total. */
    Small(4.dp),

    /** A meter that is the point of its card: context used, a long task's progress. */
    Medium(6.dp),

    /** A chart bar. */
    Large(8.dp),
}

/**
 * A progress or usage bar. [fraction] is 0..1 (clamped); null means "working, amount unknown" and
 * shows an indeterminate bar.
 *
 * [tone] colours the fill; null (the default) colours it by level, like the context ring: brand,
 * caution from 80%, critical from 95%. Pass [Tone.Critical] for a failed run, [Tone.Live] for progress.
 *
 * TalkBack reads it as a progress bar with [contentDescription] ("Context used") and the percentage.
 */
@Composable
fun Meter(
    fraction: Float?,
    modifier: Modifier = Modifier,
    tone: Tone? = null,
    size: MeterSize = MeterSize.Small,
    contentDescription: String? = null,
) {
    val track = Locus.colors.track
    if (fraction == null) {
        val color = (tone ?: Tone.Brand).color()
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth().height(size.height).clip(Radius.pill)
                .then(if (contentDescription != null) Modifier.clearAndSetSemantics { this.contentDescription = contentDescription; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate } else Modifier),
            color = color,
            trackColor = track,
            strokeCap = StrokeCap.Round,
        )
        return
    }
    val value = meterFraction(fraction)
    val color = tone?.color() ?: levelColor(value)
    val shown by animateFloatAsState(value, Motion.slow(), label = "meter")
    Box(
        modifier
            .fillMaxWidth()
            .height(size.height)
            .clip(Radius.pill)
            .background(track)
            .clearAndSetSemantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                stateDescription = meterPercent(value)
            },
    ) {
        if (shown > 0f) Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(Radius.pill).background(color))
    }
}

/** [value] clamped to 0..1; NaN and infinities become 0 (a division by a zero total). */
fun meterFraction(value: Float): Float = if (value.isNaN() || value.isInfinite()) 0f else value.coerceIn(0f, 1f)

/** [used] out of [total] as a [Meter] fraction; 0 when [total] isn't positive. */
fun meterFraction(used: Long, total: Long): Float = if (total <= 0) 0f else meterFraction(used.toFloat() / total)

/** What TalkBack says for a meter's value: "42 percent". */
fun meterPercent(fraction: Float): String = "${(meterFraction(fraction) * 100).roundToInt()} percent"
