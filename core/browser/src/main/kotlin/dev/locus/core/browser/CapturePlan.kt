package dev.locus.core.browser

import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The sizes for a `browser_save png`: what part of the page is drawn, how big the image is, and the
 * scroll offsets to visit for a full-page capture. Pure arithmetic, so the caps are unit-tested
 * (the drawing itself needs a WebView and is checked on the phone).
 *
 * All lengths are physical pixels of the browser view. [viewWidth] × [viewHeight] is the viewport.
 */
internal data class CapturePlan(
    val viewWidth: Int,
    val viewHeight: Int,
    val fullPage: Boolean,
    /** The page's full height; equals [viewHeight] for a viewport capture. */
    val pageHeight: Int,
    /** The height drawn from the top of the page (full page), or [viewHeight] (viewport). */
    val sourceHeight: Int,
    val outWidth: Int,
    val outHeight: Int,
    /** Scroll offsets, top first, whose viewports together cover [sourceHeight]. */
    val tiles: List<Int>,
) {
    val scale: Float get() = outWidth.toFloat() / viewWidth

    /** A full-page capture that stopped at [MAX_PAGE_PX] before the end of the page. */
    val cutOff: Boolean get() = fullPage && sourceHeight < pageHeight

    /** What the image shows, in words for the model: pixel size, viewport or full page, scaling, cut-off. */
    fun describe(scrolledTo: Int = 0): String = buildString {
        append("PNG ${outWidth} × ${outHeight} px of ")
        when {
            !fullPage -> {
                append("the viewport only (what the browser shows")
                append(if (scrolledTo > 0) ", scrolled ${px(scrolledTo)} down the page)" else ", at the top of the page)")
            }
            pageHeight <= viewHeight -> append("the full page (it is no taller than the viewport; content in a scrolling panel inside the page isn't expanded)")
            cutOff -> append("the top ${px(sourceHeight)} of a ${px(pageHeight)}-tall page: cut off at the ${px(MAX_PAGE_PX)} limit")
            else -> append("the full page (${px(pageHeight)} tall)")
        }
        if (outWidth != viewWidth) append(", scaled to ${(scale * 100).roundToInt()}% to stay within the size limit")
        append('.')
    }

    companion object {
        /** A full-page capture covers at most this much of the page, about six phone screens. */
        const val MAX_PAGE_PX = 15_000

        /** The image's long edge stays within this (decoders and GPU textures cope with it). */
        const val MAX_OUTPUT_EDGE = 8_192

        /** And its area within this: 8 MP is 32 MB as ARGB, the only full-size bitmap held. */
        const val MAX_OUTPUT_PIXELS = 8_000_000L

        fun viewport(viewWidth: Int, viewHeight: Int): CapturePlan {
            require(viewWidth > 0 && viewHeight > 0)
            val s = fit(viewWidth, viewHeight)
            return CapturePlan(
                viewWidth, viewHeight, fullPage = false,
                pageHeight = viewHeight, sourceHeight = viewHeight,
                outWidth = scaled(viewWidth, s), outHeight = scaled(viewHeight, s),
                tiles = listOf(0),
            )
        }

        fun fullPage(viewWidth: Int, viewHeight: Int, pageHeight: Int): CapturePlan {
            require(viewWidth > 0 && viewHeight > 0)
            val page = max(pageHeight, viewHeight)
            val source = min(page, max(MAX_PAGE_PX, viewHeight))
            val s = fit(viewWidth, source)
            return CapturePlan(
                viewWidth, viewHeight, fullPage = true,
                pageHeight = page, sourceHeight = source,
                outWidth = scaled(viewWidth, s), outHeight = scaled(source, s),
                tiles = tiles(viewHeight, source),
            )
        }

        /** Offsets 0, h, 2h, … with the last one moved up so its viewport ends exactly at [source]. */
        internal fun tiles(viewHeight: Int, source: Int): List<Int> {
            if (source <= viewHeight) return listOf(0)
            val out = mutableListOf<Int>()
            var y = 0
            while (y + viewHeight < source) {
                out += y
                y += viewHeight
            }
            out += source - viewHeight
            return out.distinct()
        }

        /** The largest scale ≤ 1 that keeps [w] × [h] within the edge and area caps. */
        internal fun fit(w: Int, h: Int): Float {
            val edge = MAX_OUTPUT_EDGE.toFloat() / max(w, h)
            val area = sqrt(MAX_OUTPUT_PIXELS.toDouble() / (w.toLong() * h)).toFloat()
            return min(1f, min(edge, area))
        }

        // floor, so rounding never pushes a dimension past a cap.
        private fun scaled(length: Int, s: Float) = if (s >= 1f) length else max(1, floor(length * s.toDouble()).toInt())

        private fun px(n: Int) = String.format(Locale.ROOT, "%,d px", n)
    }
}
