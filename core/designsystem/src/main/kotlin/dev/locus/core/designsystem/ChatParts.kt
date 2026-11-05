package dev.locus.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Pieces the chat transcript repeats: the small action buttons under a reply, the round "jump to
// latest" button, a stack of tool tiles, the user's bubble shape, and the chat's few fixed sizes.

/** Sizes only the chat uses. Everything else comes from [Sizes], [IconSize] and [Spacing]. */
object ChatSizes {
    /** A compact icon button's visual size (copy, regenerate, a branch arrow); its touch target stays 48dp. */
    val compactButton = 32.dp

    /** The round "jump to latest" button over the transcript; its touch target stays 48dp. */
    val floatingButton = 36.dp

    /** The widest a file card in the transcript gets, so a picture isn't stretched on a tablet. */
    val mediaCard = 420.dp

    /** An image the user attached, as it shows in their message. */
    val thumbnail = 160.dp

    /** From this width the live panel sits beside the transcript instead of above it. */
    val wideLayout = 720.dp

    /** How far from the end the transcript still counts as "at the latest message" and keeps following. */
    val followSlack = 64.dp
}

/**
 * The user's message bubble: rounded, with the corner nearest the screen edge tucked in so it points
 * at the sender. Sent and queued messages share it.
 */
val UserBubbleShape: Shape = RoundedCornerShape(
    topStart = Radius.card + Spacing.xs,
    topEnd = Radius.card + Spacing.xs,
    bottomEnd = Spacing.xs + Spacing.xxs,
    bottomStart = Radius.card + Spacing.xs,
)

/**
 * A small icon button: a [IconSize.small] icon in a [ChatSizes.compactButton] visual, with the full
 * 48dp touch target. For quiet actions under a reply (copy, regenerate, a branch arrow) where full-size
 * buttons would crowd the line.
 */
@Composable
fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.minimumInteractiveComponentSize().size(ChatSizes.compactButton)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(IconSize.small), tint = tint)
    }
}

/**
 * A round button floating over content ("Jump to latest"): raised surface, hairline edge, a small
 * shadow, a 48dp touch target. [rotation] turns the icon (the send arrow pointing down).
 */
@Composable
fun FloatingIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(Stroke.hairline, Locus.colors.hairline),
        shadowElevation = FloatingShadow,
        modifier = modifier.minimumInteractiveComponentSize().size(ChatSizes.floatingButton),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(IconSize.medium).rotate(rotation))
        }
    }
}

private val FloatingShadow = 2.dp

/**
 * A few small [AccentTile]s side by side: "these kinds of tools were used". They sit apart rather than
 * overlap, so each icon reads on its own at a glance. Decorative; say what they mean in the text beside them.
 */
@Composable
fun AccentTileStack(tiles: List<Pair<Accent, ImageVector>>, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = IconSize.medium) {
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        tiles.forEach { (accent, icon) -> AccentTile(accent, icon, size = size) }
    }
}

/**
 * A run history at a glance: one small dot per run, oldest first, in its result's tone (live passed,
 * critical failed, caution unclear or timed out, [Tone.Neutral] still running). Past [max] runs the
 * oldest fold into a "+N" count. Earlier runs are faded so the last run's tone is the one that reads:
 * red dots before a green one say "failed, then fixed", not "still failing". Decorative: the text
 * beside it says the result in words.
 */
@Composable
fun RunDots(results: List<Tone>, modifier: Modifier = Modifier, max: Int = 8) {
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
        val hidden = results.size - max
        if (hidden > 0) SupportingText("+$hidden", style = LocusType.mono)
        val shown = results.takeLast(max)
        shown.forEachIndexed { i, tone ->
            val earlier = i < shown.lastIndex
            Box(Modifier.size(RunDot).background(tone.color().copy(alpha = if (earlier) Emphasis.disabled else 1f), CircleShape))
        }
    }
}

private val RunDot = IconSize.dot * 0.75f

/**
 * The band at the bottom of the chat that holds the composer and what's docked to it: a solid
 * background colour with a short fade above it, so the transcript scrolling underneath fades out
 * instead of showing through the gaps between cards. Put it in the screen's bottom bar; the
 * transcript gets its height as bottom padding, so its last item is never hidden behind it.
 */
@Composable
fun BottomDock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(DockFade).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))))
        Column(Modifier.fillMaxWidth().background(bg), content = content)
    }
}

/** How far above the dock the transcript fades out. */
val DockFade = Spacing.lg

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun ChatPartsPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = UserBubbleShape) {
                    Text("Find the slowest test and fix it", Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), style = LocusType.rowTitle)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactIconButton(LocusIcons.Copy, "Copy reply", {})
                    CompactIconButton(LocusIcons.Regenerate, "Regenerate", {})
                    CompactIconButton(LocusIcons.More, "More reply actions", {})
                }
                LocusCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentTileStack(listOf(accentFor("run_command"), accentFor("web_fetch"), accentFor("read_file")))
                        Text("3 steps", Modifier.padding(start = Spacing.sm), style = LocusType.rowSupporting)
                    }
                    Row(Modifier.padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tests · passed on run 5 of 5", Modifier.padding(end = Spacing.sm), style = LocusType.rowSupporting)
                        RunDots(listOf(Tone.Critical, Tone.Critical, Tone.Caution, Tone.Critical, Tone.Live))
                    }
                }
                FloatingIconButton(LocusIcons.Send, "Jump to latest", {}, rotation = 180f)
                BottomDock {
                    Text("Message Locus", Modifier.padding(Spacing.lg).floatingSurface().padding(Spacing.lg), style = LocusType.body)
                }
            }
        }
    }
}
