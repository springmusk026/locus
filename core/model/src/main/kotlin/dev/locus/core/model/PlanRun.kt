package dev.locus.core.model

/**
 * What Approve's estimate says (docs/design/plan-run.md), in US dollars, for the first request after the
 * approval: [run] on the model that runs the plan, [chatWarm] on the chat's own model for comparison. Null
 * where models.dev lists no price for that model. [promptTokens] is the prompt both would send.
 */
data class PlanRunEstimate(val promptTokens: Int, val chatWarm: Double?, val run: Double?)

/**
 * Plan mode's "Run with" (docs/design/plan-run.md): the plan is made on the chat's model and carried out on
 * another one the user picks on the plan card. The rules that don't need Android: which pick counts, what the
 * first request costs, and when Approve asks first.
 */
object PlanRun {
    /**
     * The model the approved plan runs on: [picked], or null (the chat's own model, the default) when nothing
     * was picked, the pick has no model, or it is the chat's own model [chat].
     */
    fun runOn(chat: ModelRef?, picked: ModelRef?): ModelRef? = picked?.takeIf { it.model.isNotBlank() && it != chat }

    /**
     * The first request after Approve, on a context of [contextTokens] (the chat's last request: prompt and
     * reply) plus the approval message's [newTokens]. Only that request's input is counted; every later step
     * reads most of its prompt from the run model's cache.
     *
     * - The run model ([runPrice]) has never seen this chat, and caches are per model, so the whole prompt is
     *   a cache write there: at the 1-hour price when [hourCache] (Anthropic, where the chat's own runs ask
     *   for an hour: docs/design/cost.md, "Cache lifetimes"), else models.dev's write price, else input.
     * - The chat's model ([chatPrice]) for comparison, with its cache warm: the context read from the cache,
     *   the new message fresh. If its cache has expired it costs more; the sheet says so.
     */
    fun estimate(contextTokens: Int, newTokens: Int, chatPrice: ModelPrice?, runPrice: ModelPrice?, hourCache: Boolean = false): PlanRunEstimate {
        val context = contextTokens.coerceAtLeast(0)
        val prompt = context + newTokens.coerceAtLeast(0)
        return PlanRunEstimate(
            promptTokens = prompt,
            chatWarm = chatPrice?.cost(TokenUsage(input = prompt, output = 0, cacheRead = context)),
            run = runPrice?.cost(TokenUsage(input = prompt, output = 0, cacheWrite = prompt, cacheWrite1h = if (hourCache) prompt else 0)),
        )
    }

    /**
     * Whether Approve asks before the plan runs on [runOn]:
     * - always when [runOn] is at another provider than the chat's model [chat]: the chat is about to go to a
     *   provider the user didn't pick for it;
     * - else the first time ([confirmedBefore] false), so the user reads what it means once;
     * - else only when the run model's known first request is over [threshold]. An unknown price doesn't ask
     *   again: the first confirmation said that prices may be unknown.
     */
    fun needsConfirm(
        confirmedBefore: Boolean,
        chat: ModelRef,
        runOn: ModelRef,
        estimate: PlanRunEstimate,
        threshold: Double = ModelCompare.CONFIRM_ABOVE_USD,
    ): Boolean {
        if (runOn.providerId != chat.providerId) return true
        if (!confirmedBefore) return true
        return (estimate.run ?: 0.0) > threshold
    }

    /**
     * Whether [ref] can still run a plan in a chat whose settings are [settings]: its provider is still there
     * (a removed provider never falls back to another one, unlike a chat's own provider) and it names a model.
     */
    fun available(settings: AppSettings, ref: ModelRef): ProviderConfig? =
        settings.providers.firstOrNull { it.id == ref.providerId }?.takeIf { ref.model.isNotBlank() }
}
