package dev.locus.core.browser

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapturePlanTest {

    @Test
    fun viewportIsTheViewAtFullSize() {
        val plan = CapturePlan.viewport(1080, 2400)
        assertEquals(1080 to 2400, plan.outWidth to plan.outHeight)
        assertEquals(listOf(0), plan.tiles)
        assertFalse(plan.cutOff)
        val text = plan.describe(scrolledTo = 1200)
        assertTrue("PNG 1080 × 2400 px" in text, text)
        assertTrue("viewport only" in text, text)
        assertTrue("1,200 px down" in text, text)
        assertFalse("scaled" in text, text)
    }

    @Test
    fun aShortPageIsOneViewport() {
        val plan = CapturePlan.fullPage(1080, 2400, pageHeight = 1500)
        assertEquals(1080 to 2400, plan.outWidth to plan.outHeight)
        assertEquals(listOf(0), plan.tiles)
        assertFalse(plan.cutOff)
        assertTrue("no taller than the viewport" in plan.describe())
    }

    @Test
    fun aLongPageIsTiledToTheExactEnd() {
        val plan = CapturePlan.fullPage(1080, 2400, pageHeight = 6000)
        assertEquals(listOf(0, 2400, 3600), plan.tiles)
        assertEquals(6000, plan.sourceHeight)
        assertEquals(1080 to 6000, plan.outWidth to plan.outHeight)
        assertFalse(plan.cutOff)
        assertTrue("the full page (6,000 px tall)" in plan.describe())
        // An exact multiple gets no overlapping last tile.
        assertEquals(listOf(0, 2400), CapturePlan.fullPage(1080, 2400, 4800).tiles)
    }

    @Test
    fun tilesCoverTheSourceWithoutGaps() {
        for (page in listOf(2401, 3000, 9999, 14_999, 15_000, 40_000)) {
            val plan = CapturePlan.fullPage(1080, 2400, page)
            var covered = 0
            for (y in plan.tiles) {
                assertTrue(y <= covered, "gap before $y for page $page")
                assertTrue(y + plan.viewHeight <= plan.sourceHeight, "tile $y overruns for page $page")
                covered = maxOf(covered, y + plan.viewHeight)
            }
            assertEquals(plan.sourceHeight, covered, "page $page")
        }
    }

    @Test
    fun aVeryLongPageIsCutOffAndSaysSo() {
        val plan = CapturePlan.fullPage(1080, 2400, pageHeight = 40_000)
        assertEquals(CapturePlan.MAX_PAGE_PX, plan.sourceHeight)
        assertTrue(plan.cutOff)
        val text = plan.describe()
        assertTrue("top 15,000 px of a 40,000 px-tall page" in text, text)
        assertTrue("cut off" in text, text)
        assertTrue("scaled to" in text, text)
    }

    @Test
    fun outputStaysWithinTheCapsAndKeepsTheAspectRatio() {
        val sizes = listOf(1080 to 2400, 1440 to 3200, 2560 to 1600, 720 to 1280, 1600 to 2560)
        for ((w, h) in sizes) {
            for (page in listOf(h, 3 * h, 15_000, 100_000)) {
                val plan = CapturePlan.fullPage(w, h, page)
                assertTrue(plan.outWidth.toLong() * plan.outHeight <= CapturePlan.MAX_OUTPUT_PIXELS, "$w×$h page $page: ${plan.outWidth}×${plan.outHeight}")
                assertTrue(maxOf(plan.outWidth, plan.outHeight) <= CapturePlan.MAX_OUTPUT_EDGE, "$w×$h page $page")
                val source = plan.viewWidth.toDouble() / plan.sourceHeight
                val out = plan.outWidth.toDouble() / plan.outHeight
                assertEquals(source, out, source * 0.01, "aspect ratio for $w×$h page $page")
            }
        }
    }

    @Test
    fun aHugeViewportIsScaledDown() {
        // A tablet-sized viewport over the area cap.
        val plan = CapturePlan.viewport(4000, 3000)
        assertTrue(plan.outWidth.toLong() * plan.outHeight <= CapturePlan.MAX_OUTPUT_PIXELS)
        assertTrue(plan.scale < 1f)
        assertTrue("scaled to" in plan.describe())
    }

    @Test
    fun fitIsOneForSmallImages() {
        assertEquals(1f, CapturePlan.fit(1080, 2400))
        assertTrue(CapturePlan.fit(1080, 15_000) < 1f)
    }
}
