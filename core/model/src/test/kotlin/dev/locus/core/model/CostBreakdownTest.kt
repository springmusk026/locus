package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** `ModelPrice.breakdown` splits a request's cost without changing what `cost` adds up to. */
class CostBreakdownTest {
    private val flat = ModelPrice(4.0, 20.0, 0.2, 5.0)
    private val stepped = ModelPrice(
        0.10, 0.50, 0.01, 0.125,
        tiers = listOf(PriceTier(100_000, ModelPrice(0.50, 2.50, 0.05, 0.625))),
    )

    @Test
    fun `each part is priced at its own rate`() {
        // 100 fresh, 12,000 read, 3,000 written for 5 minutes, 1,000 for an hour, 2,000 written for 12 hours, 500 out.
        val usage = TokenUsage(input = 18_100, output = 500, cacheRead = 12_000, cacheWrite = 6_000, cacheWrite1h = 1_000, cacheWrite12h = 2_000)
        val b = flat.breakdown(usage)
        assertEquals(100 * 4.0, b.fresh, 1e-9)
        assertEquals(12_000 * 0.2, b.cacheRead, 1e-9)
        assertEquals(3_000 * 5.0, b.writeShort, 1e-9)
        assertEquals(1_000 * 4.0 * 2.0, b.write1h, 1e-9)
        assertEquals(2_000 * 4.0 * 2.5, b.write12h, 1e-9)
        assertEquals(500 * 20.0, b.output, 1e-9)
    }

    @Test
    fun `the parts add up to cost, with and without a price step or a server summary`() {
        val usages = listOf(
            TokenUsage(18_100, 500, 12_000, 6_000, cacheWrite1h = 1_000, cacheWrite12h = 2_000),
            TokenUsage(120_000, 800, 100_000, 15_000),
            TokenUsage(16_000, 1_000, 12_000, 4_000, cacheWrite1h = 4_000, serverCompaction = TokenUsage(92_000, 4_000)),
            TokenUsage(5_000, 10),
        )
        for (price in listOf(flat, stepped)) for (u in usages) {
            val parts = price.breakdown(u.withoutCompaction).usd + (u.serverCompaction?.let { price.breakdown(it).usd } ?: 0.0)
            assertEquals(price.cost(u), parts, 0.0)
        }
    }

    @Test
    fun `a prompt over the step is priced at the step's rates`() {
        val over = stepped.breakdown(TokenUsage(input = 120_000, output = 1_000, cacheRead = 100_000, cacheWrite = 20_000))
        assertEquals(100_000 * 0.05, over.cacheRead, 1e-9)
        assertEquals(20_000 * 0.625, over.writeShort, 1e-9)
        assertEquals(1_000 * 2.5, over.output, 1e-9)
    }
}
