package dev.locus.core.data.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamCoalescerTest {
    private var clock = 0L
    private val coalescer = StreamCoalescer(intervalMs = 50, reasoningLimit = 10, now = { clock })

    @Test
    fun `the first delta publishes at once, then at most once per interval`() {
        assertEquals(StreamCoalescer.Snapshot("He", null), coalescer.text("He"))
        clock = 10
        assertNull(coalescer.text("ll"))
        clock = 49
        assertNull(coalescer.text("o"))
        clock = 50
        assertEquals(StreamCoalescer.Snapshot("Hello!", null), coalescer.text("!"))
    }

    @Test
    fun `flush returns only what wasn't published`() {
        coalescer.text("a")
        assertNull(coalescer.flush())
        clock = 1
        coalescer.text("b")
        assertEquals(StreamCoalescer.Snapshot("ab", null), coalescer.flush())
        assertNull(coalescer.flush())
    }

    @Test
    fun `reasoning keeps only the last characters`() {
        coalescer.reasoning("0123456789")
        clock = 100
        assertEquals(StreamCoalescer.Snapshot(null, "56789abcde"), coalescer.reasoning("abcde"))
        clock = 200
        // Long enough to trigger the batched trim; the tail is still exact.
        assertEquals(StreamCoalescer.Snapshot(null, "klmnopqrst"), coalescer.reasoning("fghijklmnopqrst"))
    }

    @Test
    fun `reset seeds the progress line that reasoning appends to`() {
        coalescer.text("x")
        coalescer.reset(reasoningSeed = "busy ")
        assertNull(coalescer.flush())
        clock = 100
        assertEquals(StreamCoalescer.Snapshot(null, "busy hmm"), coalescer.reasoning("hmm"))
    }

    @Test
    fun `clearText drops pending text but keeps reasoning`() {
        coalescer.text("a")
        clock = 1
        coalescer.text("b")
        coalescer.reasoning("r")
        coalescer.clearText()
        assertEquals(StreamCoalescer.Snapshot(null, "r"), coalescer.flush())
        clock = 100
        assertEquals(StreamCoalescer.Snapshot("c", null), coalescer.text("c"))
    }

    @Test
    fun `many deltas build the full text`() {
        val expected = StringBuilder()
        var last: String? = null
        repeat(10_000) { i ->
            clock = i.toLong()
            expected.append(i % 10)
            coalescer.text((i % 10).toString())?.text?.let { last = it }
        }
        coalescer.flush()?.text?.let { last = it }
        assertEquals(expected.toString(), last)
    }
}
