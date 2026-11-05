package dev.locus.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The composer's parts: the floating surface it sits on, the sizes of its bar, and the count badge on a
// bar button. Only the composer uses these; they live here so its geometry has one home.

/** The composer's sizes. Its controls are drawn smaller than [Sizes.touchTarget] but keep that target. */
object ComposerSizes {
    /** A bar button's or pill's visible height (send, stop, the tool buttons); the touch target stays 48dp. */
    val control = 36.dp

    /** The text field's minimum height: one line of body text with its padding. */
    val field = 44.dp

    /** A picked photo's thumbnail in the attachment strip. */
    val thumbnail = 40.dp

    /** The widest a file attachment's name gets in the strip before it's cut short. */
    val attachmentName = 140.dp

    /** The widest a bar pill's label (a model name) gets before it's cut short, at the default font size. */
    val pillLabel = 104.dp

    /**
     * [pillLabel] at the current font scale: it grows with the font up to [PILL_LABEL_MAX_SCALE] times, so at
     * 200% a model name keeps a readable start ("Qwen3.5 0.…") rather than three letters, while the pill
     * still leaves room for the rest of the bar. The label stays on one line and ends in an ellipsis.
     */
    val pillLabelMax: Dp
        @Composable @ReadOnlyComposable get() = pillLabel * LocalDensity.current.fontScale.coerceIn(1f, PILL_LABEL_MAX_SCALE)

    /** The count badge on a bar button ([CountBadge]). */
    val badge = 15.dp

    /** The live dot on a bar button whose panel is running. */
    val liveDot = 6.dp

    /** How far the composer's shadow lifts it off the chat scrolling underneath. */
    val elevation = 8.dp

    /** How much wider than [pillLabel] a pill's label may grow with a large font. */
    const val PILL_LABEL_MAX_SCALE = 1.5f
}

/** The composer's shadow: soft black in both themes (the one colour literal the design system allows a screen). */
private val ComposerShadow = Color.Black.copy(alpha = 0.25f) // design-exception: a shadow is black in both themes

/**
 * The surface something floating over content sits on (the composer): the card colour, a hairline edge
 * and a soft shadow that lifts it off what scrolls underneath. [shape] defaults to the sheet radius.
 */
fun Modifier.floatingSurface(shape: Shape? = null): Modifier = composed {
    val s = shape ?: MaterialTheme.shapes.extraLarge
    shadow(ComposerSizes.elevation, s, ambientColor = ComposerShadow, spotColor = ComposerShadow)
        .background(Locus.colors.card, s)
        .border(Stroke.hairline, Locus.colors.hairline, s)
}

/** The small number on a bar button's corner ("2" tools switched off), "9+" past nine. Say what it counts on the button. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(ComposerSizes.badge).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 9) "9+" else count.toString(), color = MaterialTheme.colorScheme.onPrimary, style = BadgeText)
    }
}

/** The badge's digits: small enough for two to fit in [ComposerSizes.badge]. */
private val BadgeText = TextStyle(fontSize = 9.sp, lineHeight = 9.sp)

@LocusPreviews
@Composable
internal fun ComposerPartsPreview() {
    LocusTheme {
        Column(Modifier.background(MaterialTheme.colorScheme.background).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Column(Modifier.fillMaxWidth().floatingSurface().padding(Spacing.lg)) {
                SupportingText("Ask Locus anything", style = LocusType.body)
                Row(Modifier.padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(ComposerSizes.control), contentAlignment = Alignment.Center) {
                        Icon(LocusIcons.Tool, contentDescription = "Tools", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.medium))
                        CountBadge(2, Modifier.align(Alignment.TopEnd))
                    }
                    CountBadge(12)
                }
            }
        }
    }
}
