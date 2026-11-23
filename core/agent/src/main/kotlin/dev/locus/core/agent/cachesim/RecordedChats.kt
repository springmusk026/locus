package dev.locus.core.agent.cachesim

import dev.locus.core.agent.ColdClearing
import dev.locus.core.agent.Compaction
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.startsTurn
import kotlinx.serialization.json.Json

/** Reading and writing a [SimSequence] as JSON: the format of the checked-in fixtures and of the bench's traces. */
object SimJson {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(sequence: SimSequence): String = json.encodeToString(SimSequence.serializer(), sequence)

    fun decode(text: String): SimSequence = json.decodeFromString(SimSequence.serializer(), text)
}

/**
 * Builds a [SimSequence] from what Locus already stores about a chat: its messages. Every assistant reply carries
 * the provider's own [dev.locus.core.model.TokenUsage] ([Message.usage]) and when it was stored
 * ([Message.createdAt], with [dev.locus.core.model.ReplyInfo.totalMs]), the same fields receipts, budgets and
 * Insights read. The tool results and the user's messages that came before each reply are in the messages too.
 *
 * What the store doesn't keep is how the prompt divides into segments, so those are worked out, and said so in
 * [SimSequence.approximations]:
 * - The frozen prefix (system prompt and tool list) isn't recorded as a count. It's the first request's prompt less
 *   its user message, the message estimated at four characters a token. Pass [prefixTokens] when it's known.
 * - What a step added is the growth of the prompt since the request before it, which is exact. Where the prompt
 *   shrank (a checkpoint replaced the transcript, or a cold gap cleared old results) it's estimated from the
 *   stored text instead.
 * - A request is dated from its reply's stored time less its duration, or from the stored time when the duration
 *   wasn't kept.
 */
object RecordedChats {

    /**
     * @param messages the chat's shown branch, oldest first, checkpoints included.
     * @param summaries Locus's own summary requests of this chat (the usage log's `compaction` entries); they aren't messages.
     */
    fun sequenceOf(
        label: String,
        messages: List<Message>,
        model: String,
        format: ApiFormat,
        price: ModelPrice,
        contextWindow: Int? = null,
        recordedPolicy: CachePolicy = CachePolicy.today(format, model),
        summaries: List<SimRecordedSummary> = emptyList(),
        prefixTokens: Int? = null,
    ): SimSequence {
        val approx = mutableListOf<String>()
        val steps = mutableListOf<SimStep>()
        var pending = mutableListOf<Message>()
        var previousPrompt: Int? = null
        var previousReply: Message? = null
        var estimatedGrowth = 0
        var undated = 0
        var prefix = prefixTokens

        for (m in messages) {
            if (m.role != Role.ASSISTANT || m.usage == null) {
                pending += m
                continue
            }
            val usage = m.usage!!
            val checkpoint = pending.any { p -> p.parts.any { it is Part.Checkpoint } }
            val turnStarts = pending.filter { p -> p.startsTurn() && p.parts.none { it is Part.Checkpoint } }
            val results = pending.flatMap { it.toolResults }.map { SimToolResult(it.content.length + it.images.size * ColdClearing.IMAGE_CHARS, it.isError) }
            val userTokens = turnStarts.sumOf { Compaction.estimateTokens(it.userText) }
            val firstPrefix = prefix ?: (usage.input - pending.sumOf { p -> p.parts.filterIsInstance<Part.Text>().sumOf { Compaction.estimateTokens(it.text) } }).coerceAtLeast(0).also {
                prefix = it
                approx += "The system prompt and tool list weren't recorded as a count: ${"%,d".format(java.util.Locale.ROOT, it)} tokens is the first request's prompt less its user message (estimated at 4 characters a token)."
            }
            val grown = when {
                previousPrompt == null -> usage.input - firstPrefix
                checkpoint -> {
                    val view = pending.lastNotNullOfOrNull { p -> p.parts.filterIsInstance<Part.Checkpoint>().lastOrNull()?.let { Compaction.render(p.conversationId, it) } }.orEmpty()
                    estimatedGrowth++
                    (usage.input - firstPrefix - Compaction.estimateTokens(view)).coerceAtLeast(0)
                }
                usage.input >= previousPrompt!! -> usage.input - previousPrompt!!
                else -> {
                    // The prompt got smaller without a checkpoint: old tool results were cleared (a cold gap) or the
                    // provider counted differently. Take what was added from the text itself.
                    estimatedGrowth++
                    val echo = previousReply?.let { r -> Compaction.estimateTokens(r.text + r.toolCalls.joinToString("") { it.argumentsJson }) } ?: 0
                    echo + userTokens + results.sumOf { (it.chars + 3) / 4 }
                }
            }
            val replyAt = m.createdAt
            val duration = m.reply?.totalMs
            if (duration == null) undated++
            steps += SimStep(
                sentAtMs = replyAt - (duration ?: 0), replyAtMs = replyAt, newTokens = grown, userTokens = userTokens,
                results = results, outputTokens = usage.output, startsTurn = turnStarts.isNotEmpty() || steps.isEmpty(), recorded = usage,
            )
            previousPrompt = usage.input
            previousReply = m
            pending = mutableListOf()
        }
        if (estimatedGrowth > 0) approx += "$estimatedGrowth step(s) follow a summary or a cleared-results gap, where the prompt shrank: what they added is estimated from the stored text, not measured."
        if (undated > 0) approx += "$undated request(s) have no stored duration and are dated from when their reply was stored."
        val summaryTokens = messages.flatMap { it.parts.filterIsInstance<Part.Checkpoint>() }.filter { it.from == null }
            .map { Compaction.estimateTokens(it.summary) }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        return SimSequence(
            label = label, model = model, format = format, price = price, contextWindow = contextWindow,
            prefixTokens = prefix ?: 0, steps = steps, recordedPolicy = recordedPolicy, recordedSummaries = summaries,
            observedSummaryTokens = summaryTokens, approximations = approx,
        )
    }

    private inline fun <T, R : Any> List<T>.lastNotNullOfOrNull(transform: (T) -> R?): R? {
        for (i in indices.reversed()) transform(this[i])?.let { return it }
        return null
    }
}
