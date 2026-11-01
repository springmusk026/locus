package dev.locus.core.agent

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.ContextOverflow
import dev.locus.core.llm.Failover
import dev.locus.core.llm.INVALID_INPUT_KEY
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.LlmJson
import dev.locus.core.llm.PromptCacheKey
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.ReviewAudit
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** Where the loop writes what happened. Implementations persist; the loop never edits history. */
interface TranscriptStore {
    suspend fun append(message: Message)
    suspend fun record(entry: AuditEntry)
}

data class ApprovalRequest(
    val conversationId: String,
    val callId: String,
    val toolName: String,
    val risk: ToolRisk,
    val summary: String,
    val argumentsJson: String,
    /** What the AI safety reviewer said, shown as one line on the approval card; null when it wasn't asked. */
    val reviewerNote: String? = null,
    /** Set when a helper (sub-agent) asks: shown as "Researcher #2 wants to…". Null for the chat's own agent. */
    val origin: ApprovalOrigin? = null,
)

/** Which helper an approval request came from. */
data class ApprovalOrigin(val agentId: String, val label: String)

/**
 * The tool call being run, available to tools through the coroutine context. Sub-agent tools need it:
 * which chat and call started them, and the limits of the run they belong to (which helpers inherit).
 */
class ToolCallContext(
    val conversationId: String,
    val callId: String,
    val readOnly: Boolean,
    /** 0 for the user's chat; 1 inside a helper. */
    val depth: Int,
    val restrict: (suspend (Tool, JsonObject) -> String?)?,
    /** The run's reviewer, with what the user typed in that chat (a helper's task is not the user's words). */
    val reviewer: ReviewerSetup?,
    /** The task's budget, which helpers started from this call count toward; null when there's none. */
    val budget: RunBudget? = null,
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ToolCallContext>
}

/** Asks the user. Suspends until they answer; returning false (or timing out) means deny. */
fun interface ApprovalGate {
    suspend fun request(request: ApprovalRequest): Boolean
}

sealed interface AgentEvent {
    data class StepStarted(val step: Int) : AgentEvent

    /**
     * A transient error interrupted the step; any partial streamed output should be discarded. The
     * retry goes out after [waitMs] (the provider's `retry-after`, or the backoff).
     */
    data class Retrying(val attempt: Int, val reason: String, val waitMs: Long = 0) : AgentEvent

    /**
     * The model [from] couldn't answer ([reason]), so the step starts over on the next model of the
     * fallback chain, [to]. Like [Retrying], any partial output streamed so far should be discarded:
     * the new model gets the same request and never sees it.
     */
    data class FellBack(val from: String, val to: String, val reason: String) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class ToolCallStarted(val callId: String, val name: String) : AgentEvent
    data class AwaitingApproval(val request: ApprovalRequest) : AgentEvent
    /**
     * The safety reviewer stopped a call (it was not run). The chat offers "Run anyway", which re-runs
     * exactly [request]'s call.
     */
    data class ReviewerBlocked(val request: ApprovalRequest, val reason: String) : AgentEvent
    data class ToolFinished(val callId: String, val name: String, val result: ToolResult, val decision: ApprovalDecision) : AgentEvent
    data class MessageAppended(val message: Message) : AgentEvent

    /**
     * The run is summarising its transcript before the next request ([AgentConfig.compaction]):
     * because the last request filled the window, or because the provider said it no longer fits
     * ([overflow]). Any partial output streamed so far should be discarded, as for [Retrying].
     */
    data class Compacting(val overflow: Boolean) : AgentEvent

    /**
     * The summary is stored as [checkpoint] (also sent as [MessageAppended]) and now stands in for the
     * [replaced] messages before it. [usage] is the summary request's, for usage records.
     */
    data class Compacted(
        val checkpoint: Message,
        val replaced: Int,
        val usage: dev.locus.core.model.TokenUsage?,
        val providerId: String?,
        val model: String?,
        val durationMs: Long,
    ) : AgentEvent

    /** Summarising failed; the run carries on (or, after an overflow, fails). */
    data class CompactionFailed(val reason: String) : AgentEvent
    data class Finished(val outcome: Outcome) : AgentEvent
}

sealed interface Outcome {
    data object Completed : Outcome
    data class Refused(val detail: String?) : Outcome
    data class StepLimitReached(val steps: Int) : Outcome

    /** The task reached [limit] of its budget and the user (or, for a helper, the rule) said stop. */
    data class BudgetStopped(val limit: BudgetLimit, val message: String) : Outcome
    data class Failed(val message: String) : Outcome
}

class AgentConfig(
    /**
     * Steps before the run stops with [Outcome.StepLimitReached], or null for no limit. Chats the user
     * watches have none (Stop and the task budget are theirs to use); helpers, scheduled runs and side
     * passes set one.
     */
    val maxSteps: Int? = null,
    val maxRetries: Int = 3,
    /**
     * Background runs: only calls that observe run, and nothing waits for the user. Anything else, and
     * anything that would ask (set to Ask, or a forced confirmation), is denied without asking.
     */
    val readOnly: Boolean = false,
    val maxResultChars: Int = 60_000,
    /**
     * Per-chat limits (plan mode, tools switched off in the composer): a reason to refuse this call,
     * or null to let the normal policy decide. See [ChatPolicy].
     */
    val restrict: (suspend (Tool, JsonObject) -> String?)? = null,
    /**
     * Whether the chat is in Read-only mode right now (checked for every call, since the user can switch
     * mid-run). Its calls then run with [ToolCallContext.readOnly], so a page opened in the browser
     * can't download into the workspace. Refusing calls is [restrict]'s job.
     */
    val chatReadOnly: (suspend () -> Boolean)? = null,
    /** The AI safety reviewer, or null for none. Ignored in [readOnly] runs. */
    val reviewer: ReviewerSetup? = null,
    /** How deep in sub-agents this run is: 0 for the user's chat, 1 for a helper. Helpers can't start helpers. */
    val depth: Int = 0,
    /**
     * Told about each request once its step ends: the exact [ChatRequest] given to the model, and the id
     * of the reply it produced (null if it failed). For the "What was sent" viewer; it must stay cheap.
     */
    val onRequest: ((request: ChatRequest, replyId: String?) -> Unit)? = null,
    /**
     * The prompt-cache key sent with every request ([PromptCacheKey]); null means the chat's own,
     * [PromptCacheKey.forChat]. Helpers pass their profile's key.
     */
    val cacheKey: String? = null,
    /**
     * The provider config and model this run's [ChatModel] talks to, stored with each reply for Insights.
     * When both are null the conversation's own are used: exact for helper chats, which store the
     * resolved model as their override. Never sent to the model.
     */
    val providerId: String? = null,
    val model: String? = null,
    /**
     * Messages to add at a step boundary: after a step's tool results, before the next request. The
     * user's queued messages (and a helper's steer) arrive this way. They're appended at the end of the
     * transcript, never inserted, and the system prompt and tool list stay as they are, so the
     * provider's cached prefix stays valid. Not called once the run is ending (no tool calls, a
     * refusal, a proposed plan, or the last allowed step): the caller starts the next turn with them.
     */
    val atStepBoundary: (suspend () -> List<Message>)? = null,
    /**
     * The task's budget: every request of this run is recorded in it, and between steps a reached limit
     * goes to [budgetGate]. Shared with the helpers this run starts. Null: no budget.
     */
    val budget: RunBudget? = null,
    /** Asked when a limit is reached between steps; null (or false) stops the run. */
    val budgetGate: BudgetGate? = null,
    /**
     * The fallback chain: models to switch to, in order, when this run's model can't answer (see
     * [Failover] and docs/design/fallbacks.md). The request stays exactly the same (system prompt,
     * tools, messages), so it's valid for any of them; only the provider's cache is lost. Empty: none.
     */
    val fallbacks: List<FallbackModel> = emptyList(),
    /** How notes name this run's own model, e.g. "claude-opus-5-5 (Anthropic)"; null uses [model]. */
    val modelLabel: String? = null,
    /**
     * Compaction inside the run: at a step boundary once the last request used
     * [CompactionSetup.threshold] of the window, and when the provider rejects a request as too long
     * for its context window (the step is then sent once more). Null: neither, and an overflow fails
     * the run. See docs/design/compaction.md.
     */
    val compaction: CompactionSetup? = null,
)

/** One model of the fallback chain. [open] builds it when it's needed (and may fail, e.g. no key). */
class FallbackModel(
    val providerId: String,
    val model: String,
    val label: String,
    val open: suspend () -> ChatModel,
)

class AgentLoop(
    private val model: ChatModel,
    private val tools: ToolRegistry,
    private val policyFor: (Tool) -> ToolPolicy,
    private val gate: ApprovalGate,
    private val store: TranscriptStore,
    private val config: AgentConfig = AgentConfig(),
    private val ids: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Runs the model ↔ tools loop for [conversation] until the model stops calling tools.
     * [history] must already include the user's latest message.
     */
    fun run(conversation: Conversation, history: List<Message>): Flow<AgentEvent> = flow {
        val transcript = history.toMutableList()
        val serving = Serving(
            model,
            if (config.providerId == null && config.model == null) conversation.providerId to conversation.modelOverride else config.providerId to config.model,
        )
        val review = config.reviewer?.takeIf { !config.readOnly && it.mode != ReviewerMode.OFF }?.let { setup ->
            RunReview(setup, setup.trustedMessages ?: trustedUserMessages(history))
        }
        val cacheKey = config.cacheKey ?: PromptCacheKey.forChat(conversation.id)
        val budget = config.budget?.takeIf { it.active }
        // The model paces itself against the token budget where the provider supports it. Only the chat's
        // own agent is told: a helper gets a part of the task, not all of it. The value never changes
        // within a chat (Continue doesn't resend a bigger one), so the prompt cache stays valid.
        val serverBudget = budget?.budget?.tokens?.takeIf { config.depth == 0 }
        var step = 0
        // The step after which the transcript was last summarised at the threshold.
        var compactedAtStep = Int.MIN_VALUE / 2
        while (true) {
            step++
            config.maxSteps?.let { max ->
                if (step > max) {
                    emit(AgentEvent.Finished(Outcome.StepLimitReached(max)))
                    return@flow
                }
            }
            // The only place a budget stops a run: between steps, after every call of the last step has
            // its result, so nothing is cut off mid-tool and the transcript stays valid to continue.
            budget?.let { b ->
                while (true) {
                    val limit = b.exceeded() ?: break
                    val goOn = config.budgetGate?.onLimit(b, limit) == true
                    if (!goOn) {
                        emit(AgentEvent.Finished(Outcome.BudgetStopped(limit, b.stopMessage(limit))))
                        return@flow
                    }
                    b.extend(limit)
                }
            }
            emit(AgentEvent.StepStarted(step))
            lateinit var request: ChatRequest
            lateinit var streamed: Streamed
            var compactedForOverflow = false
            var summarised = false
            while (true) {
                // Display-only parts (kept reasoning) never go back to the provider.
                request = ChatRequest(conversation.systemPrompt, transcript.map { it.forModel() }, tools.specs, cacheKey, taskBudget = serverBudget)
                try {
                    streamed = streamWithRetry(request, serving)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    config.onRequest?.invoke(request, null)
                    val overflow = ContextOverflow.isOverflow(e)
                    // Too long for the window: summarise, then send the step once more.
                    if (overflow && !compactedForOverflow && config.compaction != null) {
                        compactedForOverflow = true
                        if (compact(conversation, transcript, serving, cacheKey, serverBudget, budget, config.compaction, overflow = true)) {
                            summarised = true
                            continue
                        }
                    }
                    val message = if (overflow) overflowMessage(e, serving.label, summarised) else e.message ?: e.toString()
                    emit(AgentEvent.Finished(Outcome.Failed(message)))
                    return@flow
                }
            }
            val (turn, reasoning, timing) = streamed
            val servedBy = serving.servedBy
            budget?.record(turn.usage, servedBy.first, servedBy.second)

            val assistant = Message(
                id = ids(),
                conversationId = conversation.id,
                role = Role.ASSISTANT,
                parts = buildList {
                    reasoning?.let(::add)
                    if (turn.text.isNotEmpty()) add(Part.Text(turn.text))
                    addAll(turn.toolCalls)
                },
                createdAt = clock(),
                format = turn.format,
                rawPayload = turn.rawPayload,
                usage = turn.usage,
                reply = ReplyInfo(servedBy.first, servedBy.second, timing.firstTokenMs, timing.totalMs, serving.fellBackFrom),
            )
            val replied = assistant.parts.isNotEmpty() || assistant.rawPayload != null
            config.onRequest?.invoke(request, assistant.id.takeIf { replied })
            if (replied) appendAndEmit(assistant, transcript)

            if (turn.toolCalls.isEmpty()) {
                emit(AgentEvent.Finished(if (turn.stopReason == StopReason.REFUSAL) Outcome.Refused(turn.refusalDetail) else Outcome.Completed))
                return@flow
            }

            val results = when (turn.stopReason) {
                // A refusal or truncation can cut a tool call off mid-input: never run that turn's tools,
                // but still answer every call so the transcript stays valid for the next request.
                StopReason.REFUSAL -> turn.toolCalls.map { skipped(it, "Not run: the response was declined.") }
                StopReason.MAX_TOKENS -> turn.toolCalls.map { skipped(it, "Not run: your output hit the token limit mid-call. Retry with smaller arguments, e.g. write the file in parts.") }
                else -> executeAll(conversation.id, turn.toolCalls, transcript, review)
            }
            appendAndEmit(Message(ids(), conversation.id, Role.TOOL, results, clock()), transcript)

            if (turn.stopReason == StopReason.REFUSAL) {
                emit(AgentEvent.Finished(Outcome.Refused(turn.refusalDetail)))
                return@flow
            }
            // A proposed plan waits for the user: stop without asking the model for more.
            if (results.any { r -> !r.isError && tools[r.toolName]?.endsTurn == true }) {
                emit(AgentEvent.Finished(Outcome.Completed))
                return@flow
            }
            // The step boundary: what the user added while this step ran goes to the end of the
            // transcript, and the next request sees it.
            if (config.maxSteps == null || step < config.maxSteps) {
                // Compaction comes first, with every call of this step answered, so no call is cut off
                // from its result; the user's messages then follow the summary, mid-task notes and all.
                config.compaction?.let { setup ->
                    val pending = results.sumOf { Compaction.estimateTokens(it.content) }
                    // Not again right after one: if the summary alone is that big, another won't help.
                    if (step > compactedAtStep + 1 && Compaction.due(turn.usage, setup, pending)) {
                        if (compact(conversation, transcript, serving, cacheKey, serverBudget, budget, setup, overflow = false)) compactedAtStep = step
                    }
                }
                config.atStepBoundary?.invoke()?.forEach { added ->
                    appendAndEmit(added, transcript)
                    review?.trust(added)
                }
            }
        }
    }

    companion object {
        private const val MAX_CONSECUTIVE_DENIALS = 3
        private const val MAX_RUN_DENIALS = 15

        /** Fewer messages than this aren't worth summarising (a task, a call and its result is the least). */
        private const val MIN_COMPACT_MESSAGES = 3

        /** A summary request that overflows itself is sent again with less of the transcript, this many tries in all. */
        private const val MAX_COMPACTION_TRIES = 4

        /** How a [AgentEvent.Retrying] reason starts when the stream broke rather than the provider refused. */
        const val CONNECTION_DROPPED = "The connection dropped"
    }

    /**
     * Which model a run is on. It starts on the run's own model; after a fallback the rest of the run
     * stays on the backup (the next run starts over on the chat's own model).
     */
    private inner class Serving(var model: ChatModel, var servedBy: Pair<String?, String?>) {
        private var next = 0
        val label: String get() = fellBackTo ?: config.modelLabel ?: servedBy.second ?: "the chat's model"
        private var fellBackTo: String? = null

        /** The label of the run's own model once a backup answers for it; null while it's still on its own. */
        var fellBackFrom: String? = null
            private set

        val canSwitch: Boolean get() = next < config.fallbacks.size

        /**
         * Moves to the next backup that opens, telling the UI with [AgentEvent.FellBack]. Returns false
         * when none is left; backups that fail to open (no key) are skipped.
         */
        suspend fun FlowCollector<AgentEvent>.switch(reason: LlmException): Boolean {
            while (next < config.fallbacks.size) {
                val candidate = config.fallbacks[next++]
                val opened = try {
                    candidate.open()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: continue
                val from = label
                if (fellBackFrom == null) fellBackFrom = config.modelLabel ?: servedBy.second ?: from
                model = opened
                servedBy = candidate.providerId to candidate.model
                fellBackTo = candidate.label
                emit(AgentEvent.FellBack(from, candidate.label, reason.message.orEmpty()))
                return true
            }
            return false
        }
    }

    /**
     * Summarises [transcript] with the run's current model into a stored [Part.Checkpoint] and swaps
     * the in-memory transcript for its view ([Compaction.view]); the store only grows. The request is
     * the next turn's prefix plus one question, so it reads the provider's cache. When that request
     * overflows as well, the oldest messages are left out of it until it fits. False when no summary came.
     */
    private suspend fun FlowCollector<AgentEvent>.compact(
        conversation: Conversation,
        transcript: MutableList<Message>,
        serving: Serving,
        cacheKey: String,
        taskBudget: Long?,
        budget: RunBudget?,
        setup: CompactionSetup,
        overflow: Boolean,
    ): Boolean {
        if (transcript.size < MIN_COMPACT_MESSAGES) {
            emit(AgentEvent.CompactionFailed("too little to summarise"))
            return false
        }
        emit(AgentEvent.Compacting(overflow))
        val startedAt = clock()
        var source: List<Message> = transcript.toList()
        var summary: AssistantTurn? = null
        var failure: Exception? = null
        for (attempt in 1..MAX_COMPACTION_TRIES) {
            val ask = Message(ids(), conversation.id, Role.USER, emptyList(), clock())
            try {
                summary = complete(Compaction.request(conversation.systemPrompt, source, tools.specs, cacheKey, taskBudget, setup.prompt, ask), serving)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                if (!ContextOverflow.isOverflow(e)) break
                source = Compaction.dropOldest(source, ids()) ?: break
            }
        }
        val turn = summary
        if (turn == null) {
            emit(AgentEvent.CompactionFailed(failure?.message ?: "no summary"))
            return false
        }
        budget?.record(turn.usage, serving.servedBy.first, serving.servedBy.second)
        val text = turn.text.trim()
        if (text.length < Compaction.MIN_SUMMARY_CHARS) {
            emit(AgentEvent.CompactionFailed("the model returned no summary"))
            return false
        }
        val checkpoint = Message(ids(), conversation.id, Role.USER, listOf(Compaction.checkpoint(text, transcript, setup.contextWindow)), clock())
        val replaced = transcript.size
        store.append(checkpoint)
        emit(AgentEvent.MessageAppended(checkpoint))
        transcript.clear()
        transcript += Compaction.view(checkpoint)
        emit(AgentEvent.Compacted(checkpoint, replaced, turn.usage, serving.servedBy.first, serving.servedBy.second, clock() - startedAt))
        return true
    }

    /** One whole turn, not streamed to the UI (the summary), retrying transient failures as a step does. */
    private suspend fun complete(request: ChatRequest, serving: Serving): AssistantTurn {
        var attempt = 0
        while (true) {
            try {
                var done: AssistantTurn? = null
                serving.model.stream(request).collect { event -> if (event is ChatEvent.Completed) done = event.turn }
                return done ?: throw LlmException("The stream ended without a response.", retryable = true)
            } catch (e: LlmException) {
                if (!e.retryable || attempt >= config.maxRetries) throw e
                attempt++
                delay(Failover.backoffMs(attempt, e))
            } catch (e: IOException) {
                if (attempt >= config.maxRetries) throw LlmException("$CONNECTION_DROPPED: ${e.message ?: e::class.simpleName}", cause = e)
                attempt++
                delay(Failover.backoffMs(attempt, null))
            }
        }
    }

    /** Why a run ended on a context-overflow error, and what the user can do about it. */
    private fun overflowMessage(e: Exception, model: String, summarised: Boolean): String {
        val what = if (summarised) {
            "This task no longer fits in $model's context window, even after summarising the earlier messages."
        } else {
            "This task no longer fits in $model's context window."
        }
        return "$what Start a new chat for the rest (the agent there can read this one with chat_history_read), or switch to a model " +
            "with a larger window.\n\nThe provider said: ${e.message.orEmpty().take(500)}"
    }

    /** How long a model turn took, on the attempt that succeeded (see [ReplyInfo]). */
    private class Timing(val firstTokenMs: Long?, val totalMs: Long)

    private data class Streamed(val turn: AssistantTurn, val reasoning: Part.Reasoning?, val timing: Timing)

    /**
     * Streams one model turn, retrying transient failures. Also returns what the model showed of its
     * reasoning, timed from the first reasoning delta until the answer or a tool call began, and how
     * long the turn took to start streaming and to finish.
     *
     * A connection that drops mid-stream (an [IOException]: reset, unexpected EOF, timeout) is retried
     * with the same bounded backoff as 429/5xx, the partial output discarded through
     * [AgentEvent.Retrying], but only while the attempt has not started a tool call. Tools never run
     * during streaming (only after a completed turn), so a retry can't repeat one.
     *
     * When the model can't answer (out of quota, key refused, a `retry-after` too long to wait, or
     * retries spent on rate limits and overload), the same request goes to the next model of the
     * fallback chain ([Serving.switch]), at the same clean point a retry would use: the partial output
     * is discarded, never sent on, so a reply is never stitched from two models. Each model gets its
     * own retries. A dropped connection doesn't switch: it's the phone's network, not the model.
     */
    private suspend fun FlowCollector<AgentEvent>.streamWithRetry(request: ChatRequest, serving: Serving): Streamed {
        var attempt = 0
        while (true) {
            var toolCallStarted = false
            try {
                val sentAt = clock()
                var firstAt: Long? = null
                var completed: AssistantTurn? = null
                val reasoning = StringBuilder()
                var thinkingFrom: Long? = null
                var thinkingUntil: Long? = null
                fun stopThinking() {
                    if (thinkingFrom != null && thinkingUntil == null) thinkingUntil = clock()
                }
                serving.model.stream(request).collect { event ->
                    if (firstAt == null && event !is ChatEvent.Completed) firstAt = clock()
                    when (event) {
                        is ChatEvent.TextDelta -> {
                            stopThinking()
                            emit(AgentEvent.TextDelta(event.text))
                        }
                        is ChatEvent.ReasoningDelta -> {
                            if (thinkingFrom == null) thinkingFrom = clock()
                            // Thinking can resume after a pause (interleaved); time it to the last delta then.
                            thinkingUntil = null
                            reasoning.appendReasoning(event.text)
                            emit(AgentEvent.ReasoningDelta(event.text))
                        }
                        is ChatEvent.ToolCallStarted -> {
                            stopThinking()
                            toolCallStarted = true
                            emit(AgentEvent.ToolCallStarted(event.id, event.name))
                        }
                        is ChatEvent.Completed -> completed = event.turn
                    }
                }
                stopThinking()
                val turn = completed ?: throw LlmException("The stream ended without a response.", retryable = true)
                val doneAt = clock()
                val kept = reasoning.toString().trim().takeIf { it.isNotEmpty() }
                    ?.let { Part.Reasoning(it, (thinkingUntil ?: clock()) - (thinkingFrom ?: clock())) }
                // A turn that arrives in one piece (no deltas) has its first token when it completes.
                val timing = Timing((firstAt ?: doneAt) - sentAt, doneAt - sentAt)
                return Streamed(turn, kept, timing)
            } catch (e: LlmException) {
                val spent = !e.retryable || attempt >= config.maxRetries
                if (serving.canSwitch && (Failover.switchesNow(e) || (spent && Failover.switchesAfterRetries(e)))) {
                    if (with(serving) { switch(e) }) {
                        attempt = 0
                        continue
                    }
                }
                if (spent) throw e
                val asked = e.retryAfterMs
                if (asked != null && asked > Failover.MAX_WAIT_MS) {
                    throw LlmException("${e.message}\nThe provider asked to wait ${waitLabel(asked)} before trying again.", e.httpStatus, cause = e, kind = e.kind, retryAfterMs = asked)
                }
                attempt++
                val wait = Failover.backoffMs(attempt, e)
                emit(AgentEvent.Retrying(attempt, e.message.orEmpty(), wait))
                delay(wait)
            } catch (e: IOException) {
                val reason = "$CONNECTION_DROPPED: ${e.message ?: e::class.simpleName}"
                if (toolCallStarted || attempt >= config.maxRetries) throw LlmException(reason, cause = e)
                attempt++
                val wait = Failover.backoffMs(attempt, null)
                emit(AgentEvent.Retrying(attempt, reason, wait))
                delay(wait)
            }
        }
    }

    /**
     * Runs calls one at a time so approvals arrive in order. If the run is cancelled midway, the
     * remaining calls are answered as cancelled so the transcript never has an unanswered call.
     */
    private suspend fun FlowCollector<AgentEvent>.executeAll(
        conversationId: String,
        calls: List<Part.ToolCall>,
        transcript: List<Message>,
        review: RunReview?,
    ): List<Part.ToolResult> {
        val results = mutableListOf<Part.ToolResult>()
        try {
            for (call in calls) results += execute(conversationId, call, transcript, review)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                calls.drop(results.size).forEach { results += skipped(it, "Cancelled by the user before it ran.") }
                store.append(Message(ids(), conversationId, Role.TOOL, results, clock()))
            }
            throw e
        }
        return results
    }

    private suspend fun FlowCollector<AgentEvent>.execute(
        conversationId: String,
        call: Part.ToolCall,
        transcript: List<Message>,
        review: RunReview?,
    ): Part.ToolResult {
        val startedAt = clock()
        val tool = tools[call.name]
        val args = runCatching { LlmJson.parseToJsonElement(call.argumentsJson) as JsonObject }.getOrNull()

        val (decision, result, reviewed) = when {
            tool == null -> Authorized(ApprovalDecision.INVALID_INPUT, ToolResult.error("Unknown tool '${call.name}'."))
            args == null || INVALID_INPUT_KEY in args -> Authorized(
                ApprovalDecision.INVALID_INPUT,
                ToolResult.error(buildJsonObject { put("INVALID_JSON", args?.str(INVALID_INPUT_KEY) ?: call.argumentsJson) }.toString()),
            )
            else -> validateArguments(tool.spec.inputSchema, args)?.let { Authorized(ApprovalDecision.INVALID_INPUT, ToolResult.error(it)) }
                ?: authorizeAndRun(conversationId, call, tool, args, transcript, review)
        }

        val bounded = if (result.content.length > config.maxResultChars) {
            result.copy(content = result.content.take(config.maxResultChars) + "\n…[truncated ${result.content.length - config.maxResultChars} characters]")
        } else {
            result
        }
        store.record(
            AuditEntry(
                id = ids(),
                conversationId = conversationId,
                callId = call.id,
                toolName = call.name,
                argumentsJson = call.argumentsJson,
                decision = decision,
                resultPreview = bounded.content.take(500).let { preview -> reviewed?.prefix(preview) ?: preview },
                isError = bounded.isError,
                startedAt = startedAt,
                finishedAt = clock(),
            ),
        )
        emit(AgentEvent.ToolFinished(call.id, call.name, bounded, decision))
        return Part.ToolResult(call.id, call.name, bounded.content, bounded.isError, decision)
    }

    private suspend fun FlowCollector<AgentEvent>.authorizeAndRun(
        conversationId: String,
        call: Part.ToolCall,
        tool: Tool,
        args: JsonObject,
        transcript: List<Message>,
        review: RunReview?,
    ): Authorized {
        // Deterministic limits come first and the reviewer is never consulted for them.
        config.restrict?.invoke(tool, args)?.let { reason -> return Authorized(ApprovalDecision.POLICY_DENIED, ToolResult.error(reason)) }
        val basePolicy = if (config.readOnly && !tool.isObservation(args)) ToolPolicy.DENY else policyFor(tool)
        val confirmReason = if (basePolicy == ToolPolicy.DENY) null else tool.mustConfirm(args)
        val policy = if (confirmReason != null) ToolPolicy.ASK else basePolicy
        if (policy == ToolPolicy.DENY) {
            return Authorized(
                ApprovalDecision.POLICY_DENIED,
                ToolResult.error(if (config.readOnly) "Not allowed: this is a read-only background run." else "Not allowed: the user has turned this tool off."),
            )
        }
        suspend fun callContext() = ToolCallContext(
            conversationId, call.id, config.readOnly || config.chatReadOnly?.invoke() == true, config.depth, config.restrict,
            review?.let { ReviewerSetup(it.setup.reviewer, it.setup.mode, it.setup.allowOutward, it.setup.timeoutMs, it.trusted) },
            config.budget?.takeIf { it.active },
        )
        // A call that can only fail (an edit to a file the chat hasn't read) says so without asking anyone.
        withContext(callContext()) { tool.precheck(args) }?.let { reason -> return Authorized(ApprovalDecision.INVALID_INPUT, ToolResult.error(reason)) }
        // A background run has no one to ask: a call that would need the user (set to Ask, or one that
        // must be confirmed, like a URL carrying a lot of data) is refused rather than left waiting.
        if (config.readOnly && policy == ToolPolicy.ASK) {
            return Authorized(
                ApprovalDecision.POLICY_DENIED,
                ToolResult.error("Not allowed: this is a read-only background run and this action needs the user's approval."),
            )
        }

        val checked = review?.takeIf { it.covers(policy, tool, args) }?.check(call, tool, args, policy, confirmReason, transcript)
        val decision = when {
            checked?.action == ReviewAction.RUN -> ApprovalDecision.REVIEWER_ALLOWED
            checked?.action == ReviewAction.BLOCK -> {
                val reason = checked.note
                emit(AgentEvent.ReviewerBlocked(approvalRequest(conversationId, call, tool, args, confirmReason, null), reason))
                return Authorized(
                    ApprovalDecision.REVIEWER_DENIED,
                    ToolResult.error(
                        "Blocked by the safety reviewer: $reason. Don't try to get around this; take a safer route that matches the " +
                            "user's request, or ask the user.",
                    ),
                    checked.audit,
                )
            }
            policy == ToolPolicy.ALLOW && checked == null -> ApprovalDecision.AUTO_ALLOWED
            else -> {
                // Ask (or an allowed call the reviewer didn't clear): the user decides, with the reviewer's note.
                val request = approvalRequest(conversationId, call, tool, args, confirmReason, checked?.note)
                emit(AgentEvent.AwaitingApproval(request))
                if (!gate.request(request)) {
                    return Authorized(
                        ApprovalDecision.USER_DENIED,
                        ToolResult.error("The user declined this action. Ask them how they would like to proceed."),
                        checked?.audit,
                    )
                }
                ApprovalDecision.USER_APPROVED
            }
        }
        val context = callContext()
        val result = try {
            withContext(context) { tool.execute(args) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            ToolResult.error(e.message.orEmpty())
        } catch (e: Exception) {
            ToolResult.error("${e::class.simpleName}: ${e.message}")
        }
        return Authorized(decision, result, checked?.audit)
    }

    private fun approvalRequest(conversationId: String, call: Part.ToolCall, tool: Tool, args: JsonObject, confirmReason: String?, note: String?) =
        ApprovalRequest(
            conversationId, call.id, tool.spec.name, tool.risk,
            tool.describe(args) + (confirmReason?.let { "\n$it" } ?: ""),
            call.argumentsJson,
            reviewerNote = note,
        )

    private data class Authorized(val decision: ApprovalDecision, val result: ToolResult, val review: ReviewAudit? = null)

    private enum class ReviewAction { RUN, BLOCK, ASK }

    /** [note] is the reviewer's reason (for BLOCK) or the line shown on the approval card (for ASK). */
    private class Checked(val action: ReviewAction, val note: String, val audit: ReviewAudit)

    /**
     * The reviewer for one run, with its circuit breaker: after [MAX_CONSECUTIVE_DENIALS] denials in a
     * row, or [MAX_RUN_DENIALS] in the run, it stops blocking and the user is asked instead, so a
     * confused reviewer can't stall a task or push the agent into ever stranger workarounds.
     */
    private inner class RunReview(val setup: ReviewerSetup, trusted: List<String>) {
        var trusted: List<String> = trusted
            private set

        /** A message the user added mid-run: what they typed counts as their request too. */
        fun trust(message: Message) {
            val typed = trustedUserMessages(listOf(message))
            if (typed.isNotEmpty()) trusted = (trusted + typed).takeLast(ReviewContext.USER_MESSAGES)
        }

        private var consecutiveDenials = 0
        private var runDenials = 0
        private var tripped = false

        suspend fun covers(policy: ToolPolicy, tool: Tool, args: JsonObject): Boolean = when (setup.mode) {
            ReviewerMode.OFF -> false
            ReviewerMode.REVIEW_ASKS -> policy == ToolPolicy.ASK
            ReviewerMode.REVIEW_ALL_BUT_READS -> policy == ToolPolicy.ASK || (policy == ToolPolicy.ALLOW && !tool.isObservation(args))
        }

        suspend fun check(call: Part.ToolCall, tool: Tool, args: JsonObject, policy: ToolPolicy, confirmReason: String?, transcript: List<Message>): Checked {
            if (trusted.isEmpty()) {
                return Checked(ReviewAction.ASK, "Reviewer skipped: there's no request from you to check this against", ReviewAudit(fallback = "no_user_message"))
            }
            val ctx = ReviewContext(
                userMessages = trusted,
                plan = reviewPlan(transcript),
                toolName = tool.spec.name,
                risk = tool.risk,
                summary = tool.describe(args),
                confirmReason = confirmReason,
                argumentsJson = ReviewContext.truncateArgs(call.argumentsJson),
            )
            val startedAt = clock()
            val verdict = try {
                withTimeoutOrNull(setup.timeoutMs) { setup.reviewer.review(ctx) }
            } catch (e: CancellationException) {
                // Our own timeout surfaces as null above; anything else is the run being cancelled.
                throw e
            } catch (e: Exception) {
                return unavailable(e.message ?: e::class.simpleName.orEmpty(), clock() - startedAt)
            } ?: return unavailable("no verdict (timeout or no reply)", clock() - startedAt)

            val audit = ReviewAudit(
                model = verdict.model,
                verdict = verdict.verdict.name.lowercase(),
                reason = verdict.reason,
                latencyMs = verdict.latencyMs.takeIf { it > 0 } ?: (clock() - startedAt),
                inputTokens = verdict.usage?.input ?: 0,
                outputTokens = verdict.usage?.output ?: 0,
            )
            val reason = verdict.reason
            return when (verdict.verdict) {
                Verdict.ALLOW -> {
                    consecutiveDenials = 0
                    if (confirmReason != null && !setup.allowOutward) {
                        Checked(ReviewAction.ASK, "Reviewer: $reason (actions like this always come to you)", audit.copy(fallback = "outward_needs_user"))
                    } else {
                        Checked(ReviewAction.RUN, reason, audit)
                    }
                }
                Verdict.ASK -> {
                    consecutiveDenials = 0
                    Checked(ReviewAction.ASK, "Reviewer: $reason", audit)
                }
                Verdict.DENY -> when {
                    // An allowed call isn't blocked on the reviewer's word alone; it comes to the user instead.
                    policy == ToolPolicy.ALLOW -> Checked(ReviewAction.ASK, "Reviewer flagged this: $reason", audit.copy(fallback = "demoted"))
                    tripped -> Checked(ReviewAction.ASK, "Reviewer would block this: $reason", audit.copy(fallback = "breaker"))
                    else -> {
                        consecutiveDenials++
                        runDenials++
                        if (consecutiveDenials >= MAX_CONSECUTIVE_DENIALS || runDenials >= MAX_RUN_DENIALS) tripped = true
                        Checked(ReviewAction.BLOCK, reason, audit)
                    }
                }
            }
        }

        private fun unavailable(why: String, latencyMs: Long) =
            Checked(ReviewAction.ASK, "Reviewer unavailable", ReviewAudit(fallback = "unavailable: ${why.take(120)}", latencyMs = latencyMs))
    }

    private fun skipped(call: Part.ToolCall, reason: String) = Part.ToolResult(call.id, call.name, reason, isError = true)

    private suspend fun FlowCollector<AgentEvent>.appendAndEmit(message: Message, transcript: MutableList<Message>) {
        store.append(message)
        transcript += message
        emit(AgentEvent.MessageAppended(message))
    }
}

/** "45 s", "12 min", "3 h": a wait the provider asked for, for a message. */
internal fun waitLabel(ms: Long): String = when {
    ms < 90_000 -> "${(ms + 999) / 1000} s"
    ms < 90 * 60_000 -> "${(ms + 59_999) / 60_000} min"
    else -> "${(ms + 3_599_999) / 3_600_000} h"
}
