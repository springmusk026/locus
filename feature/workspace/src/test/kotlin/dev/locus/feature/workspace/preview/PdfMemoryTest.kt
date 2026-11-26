package dev.locus.feature.workspace.preview

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PDF viewer's bitmaps stay within fixed limits whatever the page's shape or the zoom (#355): a
 * page bitmap was up to 4096 × 16384 ARGB (268 MB), and the cache was sized from the Java heap, which
 * bitmap pixels haven't lived in since API 26.
 */
class PdfMemoryTest {
    private fun bytes(size: Pair<Int, Int>) = size.first.toLong() * size.second * PdfMemory.BYTES_PER_PIXEL

    @Test
    fun `no page bitmap is over the cap, at any shape or width`() {
        val limit = PdfMemory.MAX_PAGE_PIXELS * PdfMemory.BYTES_PER_PIXEL
        for (aspect in listOf(0.001f, 0.05f, 0.25f, 0.707f, 1f, 1.414f, 10f, 1_000f, 0f, Float.NaN)) {
            for (width in listOf(1, 360, 1_050, 3_150, 4_096, 20_000)) {
                val size = PdfMemory.pageSize(aspect, width)
                assertTrue(bytes(size) <= limit, "$aspect at $width: $size")
                assertTrue(size.first in 1..PdfMemory.MAX_WIDTH && size.second in 1..PdfMemory.MAX_HEIGHT, "$aspect at $width: $size")
            }
        }
        // The worst case before: a tall page at full width and the old height cap.
        assertTrue(bytes(PdfMemory.pageSize(0.1f, 4_096)) <= 64L shl 20)
    }

    @Test
    fun `a capped page keeps its shape, and an ordinary one keeps its size`() {
        val a4 = 1f / 1.414f
        // An A4 page at a phone's width, unzoomed: exactly as asked.
        assertEquals(1_050 to 1_484, PdfMemory.pageSize(a4, 1_050))
        // A tall page is scaled down whole rather than cut or squashed.
        val (w, h) = PdfMemory.pageSize(0.05f, 4_096)
        assertTrue(abs(w.toFloat() / h - 0.05f) < 0.001f, "$w x $h")
        val (w2, h2) = PdfMemory.pageSize(a4, 4_096)
        assertTrue(abs(w2.toFloat() / h2 - a4) < 0.001f, "$w2 x $h2")
    }

    @Test
    fun `scrolling a long PDF at 3x keeps the cache within its budget, and one size per page`() {
        val cache = PageCache<Long, Long>(PdfMemory.CACHE_BYTES) { it }
        fun key(page: Int, width: Int) = (page.toLong() shl 32) or width.toLong()
        var peak = 0L
        for (page in 0 until 1_500) {
            for (width in listOf(1_050, 3_150)) {
                cache.removeIf { it ushr 32 == page.toLong() }
                cache.put(key(page, width), bytes(PdfMemory.pageSize(1f / 1.414f, width)))
                peak = maxOf(peak, cache.bytes)
            }
        }
        assertTrue(peak <= PdfMemory.CACHE_BYTES, "peak $peak")
        assertNull(cache[key(1_499, 1_050)], "the page's smaller size went when the sharper one came")
        assertTrue(cache[key(1_499, 3_150)] != null)
    }

    @Test
    fun `a value bigger than the whole budget isn't kept`() {
        val cache = PageCache<Int, Long>(100) { it }
        cache.put(1, 60)
        cache.put(2, 30)
        cache[1]
        cache.put(3, 30) // evicts 2, the least recently used
        assertNull(cache[2])
        assertEquals(90, cache.bytes)
        cache.put(4, 500)
        assertEquals(0, cache.bytes)
        assertNull(cache[4])
    }
}
