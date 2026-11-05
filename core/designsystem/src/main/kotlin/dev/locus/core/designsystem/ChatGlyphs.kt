package dev.locus.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Glyphs that say what a thing is where [LocusIcons] had only a sparkle (which means nothing in
 * particular): reasoning and a chart. Drawn like [LocusIcons]: a 24-unit grid, 1.8 strokes.
 */
object ChatGlyphs {
    private fun glyph(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black), // design-exception: vector mask, Icon tints it
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    /** A light bulb: the model's reasoning, and how hard it thinks. */
    val Thought = glyph("thought") {
        moveTo(9f, 15.5f)
        curveTo(9f, 13.8f, 6f, 12.4f, 6f, 9f)
        arcTo(6f, 6f, 0f, false, true, 18f, 9f)
        curveTo(18f, 12.4f, 15f, 13.8f, 15f, 15.5f)
        close()
        moveTo(9.5f, 18.5f); lineTo(14.5f, 18.5f)
        moveTo(10.5f, 21f); lineTo(13.5f, 21f)
    }

    /** Three bars on a baseline: figures over time (Insights). */
    val Chart = glyph("chart") {
        moveTo(4f, 20f); lineTo(20f, 20f)
        moveTo(7f, 16f); lineTo(7f, 11f)
        moveTo(12f, 16f); lineTo(12f, 6f)
        moveTo(17f, 16f); lineTo(17f, 13f)
    }
}

/**
 * [StatusLine] for a place with room for more than one line (a featured card, a screen's status at a
 * large font): the sentence wraps to [maxLines] instead of being cut to "Waiting for your…".
 */
@Composable
fun WrappingStatusLine(text: String, tone: Tone, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone, size = 7.dp)
        Text(
            text,
            Modifier.padding(start = if (tone == Tone.Live) Spacing.xxs else Spacing.sm),
            style = MaterialTheme.typography.labelLarge,
            color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurfaceVariant else tone.onContainer(),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ChatGlyphsPreview() {
    LocusTheme {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Icon(ChatGlyphs.Thought, contentDescription = "Thought", modifier = Modifier.size(IconSize.large))
                Icon(ChatGlyphs.Chart, contentDescription = "Chart", modifier = Modifier.size(IconSize.large))
            }
            WrappingStatusLine("Waiting for your approval to send the email to the whole team", Tone.Caution)
        }
    }
}
