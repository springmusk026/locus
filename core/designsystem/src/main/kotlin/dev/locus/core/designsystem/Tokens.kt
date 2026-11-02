package dev.locus.core.designsystem

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Tokens beyond Spacing and Radius (Theme.kt). Values come from what the code already used most; the
// counts are from the design audit (docs/design/design-system.md has the table and the reasoning).

/**
 * Icon and tile sizes. A plain `Icon` is [large] (24) by default; pass a size only to go smaller.
 * The audit found 16 (23 uses), 18 (22), 20 (13), 14 (8), 36 (8) and 32 (7): 14 and 18 fold into
 * [small] and [medium].
 */
object IconSize {
    /** Inline with body or label text: a chevron in a dense row, a chip's icon. */
    val small = 16.dp

    /** A trailing icon in a row, a status icon, an inline spinner. */
    val medium = 20.dp

    /** The default `Icon` size, and every icon in an `IconButton`. */
    val large = 24.dp

    /** The small coloured tile in a step or tool row ([AccentTile]). */
    val tileSmall = 28.dp

    /** A list row's leading tile ([IconTile], [ListRow]). */
    val tile = 36.dp

    /** The big tile above an empty or error state. */
    val hero = 56.dp
}

/** Line widths. 0.5.dp was written out 12 times outside the design system; 1.5.dp three times. */
object Stroke {
    /** Card edges, dividers, chip outlines. */
    val hairline = 0.5.dp

    /** An outline that means something: selection, attention, a focused field. */
    val regular = 1.dp

    /** Spinners and progress rings. */
    val bold = 2.dp
}

/**
 * Alpha levels, so a tint is a decision rather than a number. These replace the 20 different
 * `copy(alpha = …)` values (0.035 to 0.85) found in feature code.
 */
object Emphasis {
    /** De-emphasised content that is still readable: a secondary icon, a faded line. */
    const val muted = 0.6f

    /** Content of a control that can't be used right now (Material's own value). */
    const val disabled = 0.38f

    /** A neutral fill behind an icon or a track: `onSurface` at this alpha. 0.04 to 0.06 all become this. */
    const val subtleFill = 0.06f

    /** A divider or edge drawn in a tone's colour rather than [StatusColors.hairline]. */
    const val hairline = 0.08f

    /** A tone's container ([Tone.container]): chips, tinted rows. */
    const val toneContainer = 0.14f

    /** The neutral tone's container, a little lighter so grey chips don't look dirty. */
    const val neutralContainer = 0.10f

    /** A tone's outline on a card that needs attention ([Banner]). */
    const val toneOutline = 0.35f
}

/**
 * Motion. One spring for things that move, and three durations for things that fade or fill. Compose
 * scales all of them by the system's animation speed, so "Remove animations" turns them off.
 */
object Motion {
    /** The one spring: settles quickly without bounce. Sliding selections, sheets, reordering. */
    fun <T> spring(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 420f)

    /** Presses, toggles and small state changes. */
    const val QUICK_MS = 220

    /** Content appearing: entrances, expanding rows, crossfades. */
    const val STANDARD_MS = 320

    /** A value filling up: meters, rings. Slow enough to read the change. */
    const val SLOW_MS = 600

    /** Between items that enter one after another. */
    const val STAGGER_MS = 40

    /** The live dot's halo, the only motion that loops, and only while something runs. */
    const val PULSE_MS = 1400

    val Easing: Easing = FastOutSlowInEasing

    fun <T> quick(): TweenSpec<T> = tween(QUICK_MS, easing = Easing)
    fun <T> standard(): TweenSpec<T> = tween(STANDARD_MS, easing = Easing)
    fun <T> slow(): TweenSpec<T> = tween(SLOW_MS, easing = Easing)
}

/**
 * Named type roles, so a screen says what text is rather than which Material slot it borrows. Colour
 * is separate: [SupportingText] pairs the supporting roles with the muted colour.
 */
object LocusType {
    /** A screen's or sheet's title. */
    val screenTitle: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleLarge

    /** An empty or error state's title. */
    val stateTitle: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleMedium

    /** A card's or banner's title, and a section label. */
    val cardTitle: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleSmall

    /** A list row's title. */
    val rowTitle: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge

    /** The line under a row's title, and body text in cards and sheets. */
    val rowSupporting: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

    /** Notes, captions, help under a control. */
    val supporting: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall

    /** Buttons, links, a status line. */
    val label: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLarge

    /** Chips and pills. */
    val chip: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelMedium

    /** Times, sizes, paths and ids. */
    val mono: TextStyle get() = MonoCaption

    /** Code and command output. */
    val code: TextStyle get() = CodeTextStyle
}

/**
 * Muted text that supports something else: a note, a caption, a path. Replaces the hand-written pair
 * of a style and `onSurfaceVariant` (98 copies). [style] is [LocusType.supporting] by default; use
 * [LocusType.rowSupporting] for body-sized help or [LocusType.mono] for paths and times.
 */
@Composable
fun SupportingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocusType.supporting,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    Text(
        text,
        modifier,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

/** Minimum sizes. Anything tappable is at least [touchTarget] in both directions. */
object Sizes {
    /** Material's minimum touch target. Small visuals (a chip, a link) keep it with padding. */
    val touchTarget = 48.dp

    /** A [ListRow]'s minimum height, so every list has the same rhythm. */
    val row = 56.dp
}
