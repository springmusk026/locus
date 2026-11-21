package dev.locus.feature.chat

import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.llm.ModelRequirements
import dev.locus.core.model.CompareEstimate
import dev.locus.core.model.CompareTurn
import dev.locus.core.model.Message
import dev.locus.core.model.ModelCompare
import dev.locus.core.model.ModelNames
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Part

/**
 * Compare models in the chat (docs/design/compare-models.md): the parts of it that are pure, so they're
 * tested without a screen.
 */

/**
 * A reply of a Compare turn: which message it answered (for "Keep this one"), whether it's the one kept, and
 * whether it's the second model's (read-only) answer rather than the chat's own.
 */
data class ComparePick(val userMessageId: String, val kept: Boolean, val second: Boolean = false)

/** The model that wrote the reply [replyId] of [turn], from its stored [dev.locus.core.model.ReplyInfo]. */
internal fun replyModel(turn: List<Message>, replyId: String): ModelRef? {
    val reply = turn.firstOrNull { it.id == replyId }?.reply ?: return null
    val model = reply.model?.takeIf { it.isNotBlank() } ?: return null
    return ModelRef(reply.providerId.orEmpty(), model)
}

/** The short name a reply's label shows ("gpt-5.2"), as the composer names models. */
internal fun modelLabel(ref: ModelRef): String = ModelNames.short(ref.model)

/**
 * Marks the replies of Compare turns ([turns], from Settings) in [conversationId]: each one that has the
 * switcher and answered a compared message gets a [ComparePick]. The same list when there's nothing to mark.
 */
internal fun List<ChatItem>.markCompare(conversationId: String, turns: List<CompareTurn>): List<ChatItem> {
    if (turns.none { it.conversationId == conversationId }) return this
    return map { item -> if (item is ChatItem.Assistant) item.withCompare(conversationId, turns) else item }
}

/** [markCompare] for rows (a reply is always a row of its own). */
internal fun List<TranscriptRow>.markCompareRows(conversationId: String, turns: List<CompareTurn>): List<TranscriptRow> {
    if (turns.none { it.conversationId == conversationId }) return this
    return map { row ->
        val item = (row as? TranscriptRow.Single)?.item as? ChatItem.Assistant ?: return@map row
        val marked = item.withCompare(conversationId, turns)
        if (marked === item) row else TranscriptRow.Single(marked)
    }
}

private fun ChatItem.Assistant.withCompare(conversationId: String, turns: List<CompareTurn>): ChatItem.Assistant {
    val from = turn?.from ?: return this
    val t = ModelCompare.find(turns, conversationId, from) ?: return this
    val mine = model ?: return this
    if (mine != t.chat && mine != t.other) return this
    val pick = ComparePick(from, kept = t.kept == mine, second = mine == t.other)
    return if (pick == compare) this else copy(compare = pick)
}

/** The second model picked for the next send, as the composer shows it. */
data class CompareChoice(val ref: ModelRef, val label: String)

/**
 * What the composer's Compare sheet needs from a model: what the chat needs ([chatNeeds] with the
 * composer added), and tool calling whenever the chat declares tools, even before its first message
 * (the second model runs with the chat's tool list). A chat-only chat needs none.
 */
internal fun compareNeeds(needs: ModelRequirements?, chatOnly: Boolean): ModelRequirements? =
    needs?.copy(tools = !chatOnly)

/**
 * Whether [row] (as [buildModelPicker] made it, for the chat) can be picked as the second model: not the chat's
 * own model (the row marked selected), and not known to lack what the chat needs. Unknown abilities never
 * disqualify, as in the model sheet.
 */
internal fun canCompareWith(row: PickerRow): Boolean = !row.selected && row.fit.usable

/**
 * The model sheet's sections for picking the second model: the chat's own model is marked as such and can't
 * be picked, nor can models known not to serve the chat ([canCompareWith]); [second] is the one marked selected.
 */
internal fun List<PickerSection>.forCompare(second: ModelRef?): List<PickerSection> = map { s ->
    s.copy(rows = s.rows.map { r -> r.copy(chatModel = r.selected, selected = r.ref == second, pickable = canCompareWith(r)) })
}

/** The pending comparison held for the user's OK: the message, both models and the estimate. */
data class CompareConfirm(
    val text: String,
    val attachments: List<Part.Attachment>,
    val chat: CompareChoice,
    val other: CompareChoice,
    val estimate: CompareEstimate,
    /** First time: the sheet explains what Compare does. Else the estimate was over the threshold. */
    val first: Boolean,
)

/**
 * The estimate for comparing a message of [text] (with [attachments]) in a chat whose last request used
 * [contextTokens], on [chat] and [other], from models.dev's prices in [catalog] (null when it's off).
 */
internal fun compareEstimate(
    providers: List<ChatProvider>,
    catalog: ModelCatalogIndex?,
    contextTokens: Int,
    text: String,
    attachments: List<Part.Attachment>,
    chat: ModelRef,
    other: ModelRef,
): CompareEstimate {
    fun price(ref: ModelRef) = providers.firstOrNull { it.id == ref.providerId }?.let { p -> catalog?.lookup(p.baseUrl, p.format, ref.model)?.price }
    val newTokens = (text.length + 3) / 4 + attachments.sumOf { if (ModelRequirements.isImage(it)) ModelRequirements.IMAGE_TOKENS else 50 }
    return ModelCompare.estimate(contextTokens, newTokens, price(chat), price(other))
}

/** The title [ref] is listed under: the provider's or catalogue's display name where known, else the short id. */
internal fun compareTitle(ref: ModelRef, lists: Map<String, ModelList>): String =
    (lists[ref.providerId] as? ModelList.Loaded)?.models?.firstOrNull { it.id == ref.model }?.displayName?.takeIf { it.isNotBlank() && it != ref.model }
        ?: ModelNames.short(ref.model)
