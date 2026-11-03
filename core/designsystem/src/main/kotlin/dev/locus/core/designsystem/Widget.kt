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

    /** The latest task result under the status (between bodySmall and bodyMedium, like [MonoCaption]). */
    val lineText = 13.sp
}
