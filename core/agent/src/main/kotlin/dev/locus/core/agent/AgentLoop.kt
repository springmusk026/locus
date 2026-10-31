package dev.locus.core.agent

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
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
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ToolCallContext>
}

/** Asks the user. Suspends until they answer; returning false (or timing out) means deny. */
fun interface ApprovalGate {
    suspend fun request(request: ApprovalRequest): Boolean
}

sealed interface AgentEvent {
    data class StepStarted(val step: Int) : AgentEvent

    /** A transient error interrupted the step; any partial streamed output should be discarded. */
    data class Retrying(val attempt: Int, val reason: String) : AgentEvent
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
    data class Finished(val outcome: Outcome) : AgentEvent
}

sealed interface Outcome {
    data object Completed : Outcome
    data class Refused(val detail: String?) : Outcome
    data class StepLimitReached(val steps: Int) : Outcome
    data class Failed(val message: String) : Outcome
}

class AgentConfig(
    val maxSteps: Int = 40,
    val maxRetries: Int = 3,
    /** Background runs: anything that is not READ is denied without asking. */
    val readOnly: Boolean = false,
    val maxResultChars: Int = 60_000,
    /**
     * Per-chat limits (plan mode, tools switched off in the composer): a reason to refuse this call,
     * or null to let the normal policy decide. See [ChatPolicy].
     */
    val restrict: (suspend (Tool, JsonObject) -> String?)? = null,
    /** The AI safety reviewer, or null for none. Ignored in [readOnly] runs. */
    val reviewer: ReviewerSetup? = null,
    /** How deep in sub-agents this run is: 0 for the user's chat, 1 for a helper. Helpers can't start helpers. */
    val depth: Int = 0,
    /**
     * The prompt-cache key sent with every request ([PromptCacheKey]); null means the chat's own,
     * [PromptCacheKey.forChat]. Helpers pass their profile's key.
     */
    val cacheKey: String? = null,
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
        val review = config.reviewer?.takeIf { !config.readOnly && it.mode != ReviewerMode.OFF }?.let { setup ->
            RunReview(setup, setup.trustedMessages ?: trustedUserMessages(history))
        }
        val cacheKey = config.cacheKey ?: PromptCacheKey.forChat(conversation.id)
        for (step in 1..config.maxSteps) {
            emit(AgentEvent.StepStarted(step))
            // Display-only parts (kept reasoning) never go back to the provider.
            val request = ChatRequest(conversation.systemPrompt, transcript.map { it.forModel() }, tools.specs, cacheKey)
            val (turn, reasoning) = try {
                streamWithRetry(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(AgentEvent.Finished(Outcome.Failed(e.message ?: e.toString())))
                return@flow
            }

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
            )
            if (assistant.parts.isNotEmpty() || assistant.rawPayload != null) {
                appendAndEmit(assistant, transcript)
            }

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
        }
        emit(AgentEvent.Finished(Outcome.StepLimitReached(config.maxSteps)))
    }

    companion object {
        private const val MAX_CONSECUTIVE_DENIALS = 3
        private const val MAX_RUN_DENIALS = 15

        /** How a [AgentEvent.Retrying] reason starts when the stream broke rather than the provider refused. */
        const val CONNECTION_DROPPED = "The connection dropped"
    }

    /**
     * Streams one model turn, retrying transient failures. Also returns what the model showed of its
     * reasoning, timed from the first reasoning delta until the answer or a tool call began.
     *
     * A connection that drops mid-stream (an [IOException]: reset, unexpected EOF, timeout) is retried
     * with the same bounded backoff as 429/5xx, the partial output discarded through
     * [AgentEvent.Retrying], but only while the attempt has not started a tool call. Tools never run
     * during streaming (only after a completed turn), so a retry can't repeat one.
     */
    private suspend fun FlowCollector<AgentEvent>.streamWithRetry(request: ChatRequest): Pair<AssistantTurn, Part.Reasoning?> {
        var attempt = 0
        while (true) {
            var toolCallStarted = false
            try {
                var completed: AssistantTurn? = null
                val reasoning = StringBuilder()
                var thinkingFrom: Long? = null
                var thinkingUntil: Long? = null
                fun stopThinking() {
                    if (thinkingFrom != null && thinkingUntil == null) thinkingUntil = clock()
                }
                model.stream(request).collect { event ->
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
                val kept = reasoning.toString().trim().takeIf { it.isNotEmpty() }
                    ?.let { Part.Reasoning(it, (thinkingUntil ?: clock()) - (thinkingFrom ?: clock())) }
                return turn to kept
            } catch (e: LlmException) {
                if (!e.retryable || attempt >= config.maxRetries) throw e
                attempt++
                emit(AgentEvent.Retrying(attempt, e.message.orEmpty()))
                delay(1_000L shl attempt)
            } catch (e: IOException) {
                val reason = "$CONNECTION_DROPPED: ${e.message ?: e::class.simpleName}"
                if (toolCallStarted || attempt >= config.maxRetries) throw LlmException(reason, cause = e)
                attempt++
                emit(AgentEvent.Retrying(attempt, reason))
                delay(1_000L shl attempt)
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
        val context = ToolCallContext(
            conversationId, call.id, config.readOnly, config.depth, config.restrict,
            review?.let { ReviewerSetup(it.setup.reviewer, it.setup.mode, it.setup.allowOutward, it.setup.timeoutMs, it.trusted) },
        )
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
    private inner class RunReview(val setup: ReviewerSetup, val trusted: List<String>) {
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
