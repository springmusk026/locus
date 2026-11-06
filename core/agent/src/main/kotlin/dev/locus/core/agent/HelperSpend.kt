package dev.locus.core.agent

import dev.locus.core.model.ModelPrice
import dev.locus.core.model.TokenUsage

/**
 * What one helper (sub-agent) has cost so far: its requests priced at its model's models.dev rates
 * ([ModelPrice.cost], so cache reads, writes and server-side summaries count as on any receipt).
 * [unpriced] requests went to a model without a known price, so [usd] is then a floor. Shown in the
 * helper's sheet and per helper on the task receipt (docs/design/cost.md, "Cost per helper run").
 */
data class HelperSpend(val usd: Double = 0.0, val priced: Int = 0, val unpriced: Int = 0) {
    /** This plus one request's [usage] at [price] (null: not known). */
    fun plus(usage: TokenUsage, price: ModelPrice?): HelperSpend =
        if (price == null) copy(unpriced = unpriced + 1) else copy(usd = usd + price.cost(usage), priced = priced + 1)

    operator fun plus(other: HelperSpend) = HelperSpend(usd + other.usd, priced + other.priced, unpriced + other.unpriced)

    /** "≈$0.04", "<$0.01", with "+" when some requests had no price; null when none had one. */
    val label: String? get() = if (priced == 0) null else costLabel(usd, partial = unpriced > 0)

    companion object {
        /** "≈$0.04", "<$0.01", "≈$1.20+" (a floor): how receipts and helper sheets write an estimate. */
        fun costLabel(usd: Double, partial: Boolean): String {
            val amount = if (usd > 0 && usd < 0.01) "<$0.01" else "≈" + RunBudget.usd(usd)
            return amount + if (partial) "+" else ""
        }
    }
}
