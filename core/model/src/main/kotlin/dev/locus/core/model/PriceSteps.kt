package dev.locus.core.model

/**
 * Where a model's price goes up with the length of the prompt, and where to summarise a chat so its
 * requests stay below that step (docs/design/cost.md).
 *
 * Anthropic's pricing page (long context pricing, read 2026-11-06): "Claude 4.6 and later models
 * (except Claude Haiku 5.5) … include the full 1M token context window at standard pricing", and
 * "Claude Haiku 5.5 is priced by prompt length: a request whose prompt is over 100,000 tokens pays
 * higher prices. A request's prompt length counts all of its input tokens, including cache reads and
 * cache writes." Above the step every token of the request, output included, costs 5 times as much.
 * Other providers' steps come from the models.dev catalogue ([ModelPrice.priceStep]).
 */
object PriceSteps {
    /** Steps published by the model's maker, for when the catalogue is off or hasn't the model. */
    private val DOCUMENTED = mapOf(
        "claude-haiku-5-5" to 100_000,
    )

    /** The documented step for [model], or null when none is known. Matches dated ids too ("claude-haiku-5-5-20261001"). */
    fun documented(model: String): Int? {
        val id = model.trim().lowercase().substringAfterLast('/')
        return DOCUMENTED.entries.firstOrNull { (prefix, _) -> id == prefix || id.startsWith("$prefix-") }?.value
    }

    /** The step for [model]: the catalogue's when it lists one, else the documented one. */
    fun of(model: String, price: ModelPrice?): Int? = price?.priceStep ?: documented(model)

    /**
     * The prompt size to summarise at so the next request stays under [step]. Anthropic: "set its
     * `trigger` a little below 100,000 minus your prompt's largest growth between requests"
     * (cost and intelligence guide). A Locus step adds at most a few tool results, each capped inline at
     * 16,000 characters (about 4,000 tokens, LongOutput), and the reply before them, so the margin is a
     * tenth of the step and at least [MIN_MARGIN]: 90,000 below Haiku 5.5's 100,000. Never under
     * [MIN_TRIGGER], the smallest server-side trigger Anthropic accepts.
     */
    fun compactBelow(step: Int): Int = (step - maxOf(MIN_MARGIN, step / 10)).coerceAtLeast(MIN_TRIGGER)

    /** The ceiling for [model], or null when its price doesn't step. */
    fun ceiling(model: String, price: ModelPrice?): Int? = of(model, price)?.let(::compactBelow)

    const val MIN_MARGIN = 10_000

    /** Anthropic: "`value` must be at least 50,000 tokens" (threshold compaction). */
    const val MIN_TRIGGER = 50_000
}
