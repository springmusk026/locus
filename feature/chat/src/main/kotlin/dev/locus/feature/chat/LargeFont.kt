package dev.locus.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity

/**
 * Above this font scale things that sit side by side at the default size stack instead, so a label
 * keeps its words rather than being cut (the same point the design system's Banner moves its actions).
 */
internal const val LARGE_FONT_SCALE = 1.3f

/** True at a large font size (Settings → Display → Font size, well above the default). */
@Composable
@ReadOnlyComposable
internal fun largeFont(): Boolean = LocalDensity.current.fontScale > LARGE_FONT_SCALE
