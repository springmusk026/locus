package dev.locus.feature.workspace.preview

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * How much memory the PDF viewer's page bitmaps may take. Bitmap pixels live in native memory (since
 * API 26), outside the Java heap, so the limits are fixed byte counts rather than a share of
 * `Runtime.maxMemory()`. Pure, so the limits are unit-tested (PdfMemoryTest).
 */
internal object PdfMemory {
    /** At most this many pixels in one page bitmap (64 MB in ARGB_8888): an A4 page at 3× on a phone fits. */
    const val MAX_PAGE_PIXELS = 16L * 1024 * 1024

    /** Bytes per pixel in ARGB_8888. */
    const val BYTES_PER_PIXEL = 4

    /** The longest a bitmap side may be. */
    const val MAX_WIDTH = 4_096
    const val MAX_HEIGHT = 16_384

    /** What rendered pages kept for scrolling back may hold, apart from the pages on screen. */
    const val CACHE_BYTES = 64L shl 20

    /**
     * The bitmap size for a page of [aspect] (width / height) asked for at [widthPx]: that width, scaled
     * down, keeping the page's shape, until it is within [MAX_WIDTH], [MAX_HEIGHT] and [MAX_PAGE_PIXELS].
     */
    fun pageSize(aspect: Float, widthPx: Int): Pair<Int, Int> {
        val shape = if (aspect.isFinite() && aspect > 0f) aspect.toDouble() else 1.0
        var w = widthPx.coerceIn(1, MAX_WIDTH).toDouble()
        var h = w / shape
        if (h > MAX_HEIGHT) {
            w *= MAX_HEIGHT / h
            h = MAX_HEIGHT.toDouble()
        }
        if (w * h > MAX_PAGE_PIXELS) {
            val s = sqrt(MAX_PAGE_PIXELS / (w * h))
            w *= s
            h *= s
        }
        return floor(w).toInt().coerceAtLeast(1) to floor(h).toInt().coerceAtLeast(1)
    }
}

/**
 * Least recently used values within [budgetBytes], as [sizeOf] counts them. A value bigger than the
 * whole budget isn't kept. Not thread-safe: the PDF viewer uses it under its document's lock.
 */
internal class PageCache<K, V : Any>(private val budgetBytes: Long, private val sizeOf: (V) -> Long) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)

    /** What the kept values add up to. */
    var bytes = 0L
        private set

    operator fun get(key: K): V? = entries[key]

    fun put(key: K, value: V) {
        entries.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(value)
        val oldest = entries.entries.iterator()
        while (bytes > budgetBytes && oldest.hasNext()) {
            val e = oldest.next()
            bytes -= sizeOf(e.value)
            oldest.remove()
        }
    }

    /** Drops the values whose keys match [which]. */
    fun removeIf(which: (K) -> Boolean) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (which(e.key)) {
                bytes -= sizeOf(e.value)
                it.remove()
            }
        }
    }

    fun clear() {
        entries.clear()
        bytes = 0
    }
}
