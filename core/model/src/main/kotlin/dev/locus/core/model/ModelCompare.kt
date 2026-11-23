package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * One Compare send (docs/design/compare-models.md): the user message [userMessageId] of chat
 * [conversationId] was answered twice, as sibling branches, by the chat's own model [chat] and by
 * [other]. [kept] is the model whose answer the user kept ("Keep this one"), null until they pick.
 */
@Serializable
data class CompareTurn(
    val conversationId: String,
    val userMessageId: String,
    val chat: ModelRef,
    val other: ModelRef,
    val kept: ModelRef? = null,
)

/**
 * How often the user kept each model of one pair. The pair is unordered: [first] sorts before
 * [second] ([ModelCompare.pairOf]), whichever was the chat's model at the time.
 */
@Serializable
data class ModelPickTally(
    val first: ModelRef,
    val second: ModelRef,
    val firstKept: Int = 0,
    val secondKept: Int = 0,
) {
    val total: Int get() = firstKept + secondKept
}

/**
 * The estimated input cost of a comparison's first two requests, in US dollars: [chat] for the chat's
 * own model, [other] for the second one. Null where models.dev lists no price for that model.
 */
data class CompareEstimate(val promptTokens: Int, val chat: Double?, val other: Double?) {
    /** Both together; null when either price is unknown. */
    val total: Double? get() = if (chat != null && other != null) chat + other else null
}

/** The rules of Compare models that don't need Android: the estimate, when to ask, and the tally. */
object ModelCompare {
    /** How many comparisons [AppSettings.compareTurns] remembers for "Keep this one". */
    const val MAX_TURNS = 50

    /** Above this estimate (both requests' input, US dollars) a Compare send asks again, after the first time. */
    const val CONFIRM_ABOVE_USD = 0.05

    /**
     * The estimate for sending a message of [newTokens] tokens on top of a context of [contextTokens]
     * (the chat's last request: prompt and reply), to both models. Only the first request's input is
     * counted; replies and further tool steps add more.
     *
     * - The chat's model, [chatPrice]: priced as fresh input, the most it can cost. Its prompt cache
     *   (keyed by the chat's model) usually makes it cheaper; it's an upper bound.
     * - The other model, [otherPrice]: its provider has never seen this chat, so the whole prompt is a
     *   cache write there (models.dev's write price, else the input price).
     */
    fun estimate(contextTokens: Int, newTokens: Int, chatPrice: ModelPrice?, otherPrice: ModelPrice?): CompareEstimate {
        val prompt = (contextTokens.coerceAtLeast(0) + newTokens.coerceAtLeast(0))
        return CompareEstimate(
            promptTokens = prompt,
            chat = chatPrice?.cost(TokenUsage(input = prompt, output = 0)),
            other = otherPrice?.cost(TokenUsage(input = prompt, output = 0, cacheWrite = prompt)),
        )
    }

    /**
     * Whether a Compare send asks first: always until the user has confirmed one ([confirmedBefore]),
     * then only when the known estimate is over [threshold]. An unknown price doesn't ask again: the
     * first confirmation said that prices may be unknown.
     */
    fun needsConfirm(confirmedBefore: Boolean, estimate: CompareEstimate, threshold: Double = CONFIRM_ABOVE_USD): Boolean {
        if (!confirmedBefore) return true
        val known = listOfNotNull(estimate.chat, estimate.other).sum()
        return known > threshold
    }

    /** [a] and [b] in a fixed order, so a pair is one row whichever model was the chat's. */
    fun pairOf(a: ModelRef, b: ModelRef): Pair<ModelRef, ModelRef> =
        if (key(a) <= key(b)) a to b else b to a

    private fun key(r: ModelRef) = "${r.providerId}\u0000${r.model}"

    /** [turns] with [turn] first; an older entry for the same message is replaced. At most [MAX_TURNS]. */
    fun remember(turns: List<CompareTurn>, turn: CompareTurn): List<CompareTurn> =
        (listOf(turn) + turns.filterNot { it.conversationId == turn.conversationId && it.userMessageId == turn.userMessageId }).take(MAX_TURNS)

    /** The comparison that answered [userMessageId] in [conversationId], or null when that wasn't one. */
    fun find(turns: List<CompareTurn>, conversationId: String, userMessageId: String): CompareTurn? =
        turns.firstOrNull { it.conversationId == conversationId && it.userMessageId == userMessageId }

    /**
     * The user kept [kept]'s answer of the comparison of [userMessageId]: the turn records it and the
     * pair's tally counts it. Keeping the other answer later moves the count rather than adding a second
     * one; keeping the same one again changes nothing. Null when there's no such comparison, or [kept] is
     * neither of its models.
     */
    fun keep(
        turns: List<CompareTurn>,
        picks: List<ModelPickTally>,
        conversationId: String,
        userMessageId: String,
        kept: ModelRef,
    ): Pair<List<CompareTurn>, List<ModelPickTally>>? {
        val turn = find(turns, conversationId, userMessageId) ?: return null
        if (kept != turn.chat && kept != turn.other) return null
        if (turn.kept == kept) return turns to picks
        val (first, second) = pairOf(turn.chat, turn.other)
        val existing = picks.firstOrNull { it.first == first && it.second == second } ?: ModelPickTally(first, second)
        var tally = existing
        // Moving a pick: the earlier choice no longer counts.
        turn.kept?.let { old -> tally = if (old == first) tally.copy(firstKept = (tally.firstKept - 1).coerceAtLeast(0)) else tally.copy(secondKept = (tally.secondKept - 1).coerceAtLeast(0)) }
        tally = if (kept == first) tally.copy(firstKept = tally.firstKept + 1) else tally.copy(secondKept = tally.secondKept + 1)
        val newPicks = if (existing in picks) picks.map { if (it === existing) tally else it } else picks + tally
        val newTurns = turns.map { if (it === turn) it.copy(kept = kept) else it }
        return newTurns to newPicks
    }

    /** The tally as Insights lists it: most compared pairs first. */
    fun table(picks: List<ModelPickTally>): List<ModelPickTally> = picks.filter { it.total > 0 }.sortedByDescending { it.total }
}
