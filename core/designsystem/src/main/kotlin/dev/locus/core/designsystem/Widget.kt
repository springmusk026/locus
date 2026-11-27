package dev.locus.core.designsystem

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The home-screen widget's few fixed sizes. Glance draws with RemoteViews, so it can't read
 * [LocusType] or `MaterialTheme.shapes`; its colours come from [LocusLightColors] and [LocusDarkColors],
 * and its sizes from here, so the widget still takes nothing from a literal in feature code.
 */
object WidgetSizes {
    /** The widget's corner, a little rounder than a card to sit with launcher widgets. */
    val corner = 20.dp

    /** Between the widget's edge and its content. */
    val padding = 14.dp

    /** The status dot, a touch larger than [IconSize.dot] so it reads at home-screen distance. */
    val dot = 10.dp

    /** The status line's size (Material's titleMedium; Glance can't take a Compose TextStyle). */
    val statusText = 16.sp

    /** The usage widget's figure, today's spend (Material's titleLarge). */
    val headlineText = 22.sp

    /** The latest task result under the status (between bodySmall and bodyMedium, like [MonoCaption]). */
    val lineText = 13.sp

    // How tall each part is drawn, at font scale 1, so a widget shows only the lines that fit its size
    // (RemoteViews can't measure text for us). A little over the fonts' own line heights, to be safe.

    /** One line of [lineText]. */
    val line = 18.dp

    /** The status line (the dot and [statusText]). */
    val statusLine = 22.dp

    /** The usage widget's figure ([headlineText]). */
    val headlineLine = 30.dp

    /** Glance's Button with its label (no minimum height of its own; 33dp measured on a phone). */
    val button = 40.dp

    /** The usage widget's seven-day strip: its height, a day with no use, and the space between days. */
    val stripHeight = 32.dp
    val barMin = 2.dp
    val barGap = 3.dp

    /** A bar's corner. */
    val barCorner = 2.dp
}
