package dev.locus.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Compare models' estimate, when it asks first, and the local tally (docs/design/compare-models.md). */
class ModelCompareTest {
    private val chat = ModelRef("anthropic", "claude-sonnet-5-5")
    private val other = ModelRef("openai", "gpt-5.2")

    // $3 / $15 per million, cache write $3.75: Sonnet-like.
    private val chatPrice = ModelPrice(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75)

    // $1.25 / $10 per million, no cache-write price listed.
    private val otherPrice = ModelPrice(input = 1.25, output = 10.0, cacheRead = 0.125)

    @Test
    fun `the estimate prices the chat's model as fresh input and the other as a whole-prompt cache write`() {
        val e = ModelCompare.estimate(contextTokens = 90_000, newTokens = 10_000, chatPrice = chatPrice, otherPrice = chatPrice)
        assertEquals(100_000, e.promptTokens)
        // 100k fresh input at $3/M.
        assertEquals(0.30, e.chat!!, 1e-9)
        // 100k written to the cache at $3.75/M: the second model's provider has never seen the chat.
        assertEquals(0.375, e.other!!, 1e-9)
        assertEquals(0.675, e.total!!, 1e-9)
    }

    @Test
    fun `a model without a cache-write price pays input for the write`() {
        val e = ModelCompare.estimate(contextTokens = 1_000_000, newTokens = 0, chatPrice = null, otherPrice = otherPrice)
        assertEquals(1.25, e.other!!, 1e-9)
    }

    @Test
    fun `an unknown price is unknown, and so is the total`() {
        val e = ModelCompare.estimate(contextTokens = 5_000, newTokens = 100, chatPrice = chatPrice, otherPrice = null)
        assertEquals(5_100 * 3.0 / 1_000_000, e.chat!!, 1e-12)
        assertNull(e.other)
        assertNull(e.total)
    }

    @Test
    fun `it asks the first time, then only over the threshold`() {
        val cheap = CompareEstimate(1_000, 0.01, 0.01)
        val dear = CompareEstimate(500_000, 1.5, 1.9)
        val unknown = CompareEstimate(500_000, null, null)
        assertTrue(ModelCompare.needsConfirm(confirmedBefore = false, estimate = cheap))
        assertTrue(ModelCompare.needsConfirm(confirmedBefore = false, estimate = unknown))
        assertFalse(ModelCompare.needsConfirm(confirmedBefore = true, estimate = cheap))
        assertTrue(ModelCompare.needsConfirm(confirmedBefore = true, estimate = dear))
        // Unknown prices were explained the first time; they don't ask on every send.
        assertFalse(ModelCompare.needsConfirm(confirmedBefore = true, estimate = unknown))
        // A known part over the threshold asks even when the other part is unknown.
        assertTrue(ModelCompare.needsConfirm(confirmedBefore = true, estimate = CompareEstimate(500_000, 0.9, null)))
    }

    @Test
    fun `a pair is one row whichever model was the chat's`() {
        assertEquals(ModelCompare.pairOf(chat, other), ModelCompare.pairOf(other, chat))
    }

    @Test
    fun `keeping an answer counts it once, and keeping the other moves the count`() {
        val turns = ModelCompare.remember(emptyList(), CompareTurn("c1", "u1", chat, other))
        val (first, second) = ModelCompare.pairOf(chat, other)

        val (t1, p1) = ModelCompare.keep(turns, emptyList(), "c1", "u1", other)!!
        assertEquals(other, t1.single().kept)
        val row = p1.single()
        assertEquals(first to second, row.first to row.second)
        assertEquals(1, if (other == first) row.firstKept else row.secondKept)
        assertEquals(1, row.total)

        // The same pick again changes nothing.
        assertEquals(t1 to p1, ModelCompare.keep(t1, p1, "c1", "u1", other))

        // Changing one's mind moves the pick rather than counting both.
        val (t2, p2) = ModelCompare.keep(t1, p1, "c1", "u1", chat)!!
        assertEquals(chat, t2.single().kept)
        val moved = p2.single()
        assertEquals(1, moved.total)
        assertEquals(1, if (chat == first) moved.firstKept else moved.secondKept)

        // A second comparison of the same pair adds to the same row.
        val more = ModelCompare.remember(t2, CompareTurn("c2", "u9", other, chat))
        val (_, p3) = ModelCompare.keep(more, p2, "c2", "u9", chat)!!
        assertEquals(2, p3.single().total)
        assertEquals(2, if (chat == first) p3.single().firstKept else p3.single().secondKept)
    }

    @Test
    fun `keeping in a message that wasn't compared, or a third model, does nothing`() {
        val turns = listOf(CompareTurn("c1", "u1", chat, other))
        assertNull(ModelCompare.keep(turns, emptyList(), "c1", "u2", chat))
        assertNull(ModelCompare.keep(turns, emptyList(), "c1", "u1", ModelRef("x", "y")))
    }

    @Test
    fun `only the latest comparisons are remembered, newest first`() {
        var turns = emptyList<CompareTurn>()
        repeat(ModelCompare.MAX_TURNS + 5) { turns = ModelCompare.remember(turns, CompareTurn("c", "u$it", chat, other)) }
        assertEquals(ModelCompare.MAX_TURNS, turns.size)
        assertEquals("u${ModelCompare.MAX_TURNS + 4}", turns.first().userMessageId)
        // The same message again replaces its entry.
        val again = ModelCompare.remember(turns, CompareTurn("c", "u10", chat, other, kept = chat))
        assertEquals(ModelCompare.MAX_TURNS, again.size)
        assertEquals(1, again.count { it.userMessageId == "u10" })
    }

    @Test
    fun `the table lists pairs with picks, most compared first`() {
        val a = ModelPickTally(chat, other, 1, 0)
        val b = ModelPickTally(ModelRef("p", "m1"), ModelRef("p", "m2"), 3, 2)
        val empty = ModelPickTally(ModelRef("p", "m3"), ModelRef("p", "m4"))
        assertEquals(listOf(b, a), ModelCompare.table(listOf(a, empty, b)))
    }
}
