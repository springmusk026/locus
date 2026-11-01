package dev.locus.core.agent

import dev.locus.core.agent.home.MemoryAuthor
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.ContextOverflow
import dev.locus.core.llm.Failover
import dev.locus.core.llm.INVALID_INPUT_KEY
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.LlmJson
import dev.locus.core.llm.ModelCapabilities
import dev.locus.core.llm.ModelRequirements
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
import dev.locus.core.model.startsTurn
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /**
     * The run's taint when this call started (see [Taint]): what outside content it had read, and the
     * hosts its user named. Helpers start from it; memory_save records it with what it saves.
     */
    val taint: TaintSeed = TaintSeed(emptySet(), emptySet()),
    /** Who is acting, for provenance: [MemoryAuthor.AGENT], or [MemoryAuthor.REFLECTION] for the after-task pass. */
    val author: String = MemoryAuthor.AGENT,
    /** Whose turn workspace changes from this call belong to (see [dev.locus.core.agent.tools.ChangeRecorder]); null: not tracked. */
    val changeOwner: dev.locus.core.agent.tools.ChangeOwner? = null,
    /** The project the call runs for (see [ChatScope]); helpers started from it get the same. Null outside projects. */
    val scope: dev.locus.core.model.ChatScopeSpec? = null,
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ToolCallContext>

    /** The same run's context for another call: how a call run at a step boundary (Run anyway) joins the run. */
    fun forCall(callId: String) = ToolCallContext(conversationId, callId, readOnly, depth, restrict, reviewer, budget, taint, author, changeOwner, scope)
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
    data class FellBack(
        val from: String,
        val to: String,
        val reason: String,
        /** Backups passed over because they're known not to serve this request: "gpt-3.5 (OpenAI): no vision". */
        val skipped: List<String> = emptyList(),
        /** What couldn't be confirmed about [to] ("vision"): it was tried anyway, since unknown isn't "no". */
        val unconfirmed: String? = null,
    ) : AgentEvent
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
    /**
     * The most of one result that ever goes into the transcript: for tools that page their own output
     * ([Tool.pagesOwnOutput]) and when a long result couldn't be saved to a file.
     */
    val maxResultChars: Int = 60_000,
    /**
     * How much of a long result the model sees inline (its start and end) once the whole of it is
     * saved through [spill]; see [LongOutput]. Helpers use [LongOutput.HELPER_INLINE_CHARS].
     */
    val inlineResultChars: Int = LongOutput.CHAT_INLINE_CHARS,
    /**
     * Saves a long result's full text to a workspace file the shortened result names. Null: long
     * results are cut to [maxResultChars] with nothing saved.
     */
    val spill: OutputSpill? = null,
    /**
     * Whether consecutive calls of a turn that only look and run without asking may run at the same
     * time (see [AgentLoop]'s executeAll). Results always stay in the order the model made the calls.
     */
    val parallelReads: Boolean = true,
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
     * Called with the run's [ToolCallContext] (call id [AgentLoop.STEP_BOUNDARY_CALL]) and project
     * scope, so a call it runs for the user ([ToolCallContext.forCall]) counts as part of this turn.
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
     * Whose turn this run's workspace changes belong to. Null (the user's own chat): this chat and its
     * current turn. A helper passes its parent's, so its edits show under the parent's turn.
     */
    val changeOwner: dev.locus.core.agent.tools.ChangeOwner? = null,
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
    /**
     * Hosts the user set up in Locus (model providers, MCP servers, connectors), trusted like hosts they
     * typed once the run is tainted (see [Taint]). Read for every gated call. Null: none.
     */
    val trustedHosts: (suspend () -> Set<String>)? = null,
    /**
     * The taint a helper inherits from the run that started it. Null for a run of its own: it starts from
     * what its history already holds, with the hosts its user typed.
     */
    val taintSeed: TaintSeed? = null,
    /** Who is acting, passed to tools for provenance (see [ToolCallContext.author]). */
    val author: String = MemoryAuthor.AGENT,
    /**
     * The project this run is for: its tool calls (and saved long output) resolve the workspace in the
     * project's folder, Linux mounts that folder, and memory tools use the project's memory. Null:
     * the whole workspace and global memory. See [ChatScope] and docs/design/projects.md.
     */
    val scope: dev.locus.core.model.ChatScopeSpec? = null,
)

/**
 * One model of the fallback chain. [open] builds it when it's needed (and may fail, e.g. no key).
 * [capabilities] is what's known of it (docs/design/model-capabilities.md): a backup known not to
 * serve the request (no tool calling, no vision, too small a window) is skipped.
 */
class FallbackModel(
    val providerId: String,
    val model: String,
    val label: String,
    val capabilities: ModelCapabilities = ModelCapabilities.UNKNOWN,
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
        // Concurrent sets: calls that run together read them while finished ones add to them.
        val taint = RunTaint(
            sources = concurrentSetOf(config.taintSeed?.sources.orEmpty() + Taint.sourcesIn(history)),
            userHosts = concurrentSetOf(
                config.taintSeed?.trustedHosts
                    ?: Taint.hostsIn(config.reviewer?.trustedMessages ?: trustedUserMessages(history, limit = Int.MAX_VALUE)),
            ),
        )
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
                request = ChatRequest(
                    conversation.systemPrompt, transcript.map { it.forModel() }, tools.specs, cacheKey, taskBudget = serverBudget,
                    deferredTools = tools.requestDeferred(),
                )
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
                        if (compact(conversation, transcript, serving, cacheKey, serverBudget, budget, config.compaction, taint.sources, overflow = true)) {
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
                else -> executeAll(conversation.id, turn.toolCalls, transcript, review, taint)
            }
            appendAndEmit(Message(ids(), conversation.id, Role.TOOL, results, clock()), transcript)

            if (turn.stopReason == StopReason.REFUSAL) {
                emit(AgentEvent.Finished(Outcome.Refused(turn.refusalDetail)))
                return@flow
            }
            // A proposed plan waits for the user: stop without asking the model for more. A call_tool
            // counts as the tool it ran.
            val ran = turn.toolCalls.associate { c -> c.id to tools.resolve(c).call.name }
            if (results.any { r -> !r.isError && tools[ran[r.callId] ?: r.toolName]?.endsTurn == true }) {
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
                        if (compact(conversation, transcript, serving, cacheKey, serverBudget, budget, setup, taint.sources, overflow = false)) compactedAtStep = step
                    }
                }
                // It runs with this step's tool-call context, so a call the user runs here (Run anyway)
                // belongs to this turn: its workspace changes, budget, project and taint.
                config.atStepBoundary?.let { boundary ->
                    withContext(callContext(conversation.id, STEP_BOUNDARY_CALL, transcript, review, taint) + ChatScope.element(config.scope)) { boundary() }
                }?.forEach { added ->
                    appendAndEmit(added, transcript)
                    review?.trust(added)
                    // A call result Locus added (Run anyway) taints the rest of this run, as it would the next one.
                    taint.sources += Taint.sourcesIn(listOf(added))
                    // A helper's notes come from the agent that started it, not from the user.
                    if (config.taintSeed == null) taint.userHosts += Taint.hostsIn(trustedUserMessages(listOf(added)))
                }
            }
        }
    }

    companion object {
        private const val MAX_CONSECUTIVE_DENIALS = 3

        /** The call id of the context [AgentConfig.atStepBoundary] runs with; see [ToolCallContext.forCall]. */
        const val STEP_BOUNDARY_CALL = "step-boundary"

        /** Decisions under which a call actually ran. */
        private val RAN = setOf(ApprovalDecision.AUTO_ALLOWED, ApprovalDecision.USER_APPROVED, ApprovalDecision.REVIEWER_ALLOWED)
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

        /** Backups passed over so far because they can't serve the request, with why. */
        val skipped = mutableListOf<String>()

        /** [e], with the backups that were passed over named, when there were any. */
        fun explain(e: LlmException): LlmException =
            if (skipped.isEmpty()) e else LlmException("${e.message}\nBackup models skipped: ${skipped.joinToString("; ")}.", e.httpStatus, e.retryable, e, e.kind, e.retryAfterMs)

        /**
         * Moves to the next backup that can serve [request] and opens, telling the UI with
         * [AgentEvent.FellBack]. Returns false when none is left. Backups known not to meet the request's
         * needs ([ModelRequirements.of]: tools, images, the context so far plus room for a reply) are
         * skipped, and so are those that fail to open (no key). Unknown abilities don't disqualify.
         */
        suspend fun FlowCollector<AgentEvent>.switch(reason: LlmException, request: ChatRequest): Boolean {
            val needs = ModelRequirements.of(request)
            val passedOver = mutableListOf<String>()
            while (next < config.fallbacks.size) {
                val candidate = config.fallbacks[next++]
                val fit = candidate.capabilities.fit(needs)
                if (!fit.usable) {
                    passedOver += "${candidate.label}: ${fit.reason}"
                    skipped += passedOver.last()
                    continue
                }
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
                emit(AgentEvent.FellBack(from, candidate.label, reason.message.orEmpty(), passedOver.toList(), fit.unconfirmedLabel))
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
        /** What the run has read from outside ([Taint]); the checkpoint keeps it, so a later run starts tainted. */
        tainted: Set<String>,
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
                val summaryRequest = Compaction.request(conversation.systemPrompt, source, tools.specs, cacheKey, taskBudget, setup.prompt, ask)
                summary = complete(summaryRequest.copy(deferredTools = tools.requestDeferred()), serving)
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
        val checkpoint = Message(ids(), conversation.id, Role.USER, listOf(Compaction.checkpoint(text, transcript, setup.contextWindow, tainted)), clock())
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
                    if (with(serving) { switch(e, request) }) {
                        attempt = 0
                        continue
                    }
                }
                if (spent) throw serving.explain(e)
                val asked = e.retryAfterMs
                if (asked != null && asked > Failover.MAX_WAIT_MS) {
                    throw LlmException("${serving.explain(e).message}\nThe provider asked to wait ${waitLabel(asked)} before trying again.", e.httpStatus, cause = e, kind = e.kind, retryAfterMs = asked)
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
     * Runs a turn's calls and returns their results in the order the model made them.
     *
     * Calls that ask, write, or drive something shared run one at a time, so approvals arrive in
     * order and each sees the effects of the ones before it. A run of two or more consecutive calls
     * that only look and would run without asking anyone ([runsTogether]) runs at the same time, as
     * Codex runs its parallel-safe tools (codex-rs/core/src/tools/parallel.rs). Every call of the step
     * still has its result before the step ends, so budgets, compaction and the user's queued messages
     * see the same step boundary as before, and the transcript is the same as a one-at-a-time run.
     *
     * If the run is cancelled midway, the calls without a result are answered as cancelled so the
     * transcript never has an unanswered call.
     */
    private suspend fun FlowCollector<AgentEvent>.executeAll(
        conversationId: String,
        calls: List<Part.ToolCall>,
        transcript: List<Message>,
        review: RunReview?,
        taint: RunTaint,
    ): List<Part.ToolResult> {
        val results = mutableListOf<Part.ToolResult>()
        // A call_tool becomes the call of the tool it names before anything else looks at it (see
        // ToolRegistry.resolve), so it runs exactly as a direct call would.
        val resolved = calls.map { tools.resolve(it) }
        try {
            var i = 0
            while (i < resolved.size) {
                // Decided when the run reaches it, after every call before it has finished (and tainted the run, if it did).
                var end = i
                while (end < resolved.size && runsTogether(resolved[end], review, taint)) end++
                if (end - i >= 2) {
                    executeTogether(conversationId, resolved.subList(i, end), transcript, review, taint, into = results)
                    i = end
                } else {
                    results += execute(conversationId, resolved[i], transcript, review, taint)
                    i++
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                calls.drop(results.size).forEach { results += skipped(it, "Cancelled by the user before it finished.") }
                store.append(Message(ids(), conversationId, Role.TOOL, results, clock()))
            }
            throw e
        }
        return results
    }

    /**
     * Whether [call] may run alongside its neighbours: it only looks ([Tool.isObservation]), runs
     * without asking anyone (Allow, no chat limit, no forced confirmation, not covered by the
     * reviewer), doesn't need to run alone ([Tool.runsAlone]: the browser, ask_user, helpers), and
     * wouldn't be held back by taint even if a call beside it brought outside text in. That last check
     * matters: one at a time, a page read first taints the run and a fetch after it then asks; side by
     * side the fetch would already be running. So calls that taint can gate never run together.
     */
    private suspend fun runsTogether(resolved: ResolvedCall, review: RunReview?, taint: RunTaint): Boolean {
        if (!config.parallelReads || resolved.error != null) return false
        val call = resolved.call
        val tool = tools[call.name] ?: return false
        if (tool.runsAlone || tool.endsTurn) return false
        val args = runCatching { LlmJson.parseToJsonElement(call.argumentsJson) as JsonObject }.getOrNull() ?: return false
        if (INVALID_INPUT_KEY in args || validateArguments(tool.spec.inputSchema, args) != null) return false
        if (!tool.isObservation(args)) return false
        if (config.restrict?.invoke(tool, args) != null) return false
        if (policyFor(tool) != ToolPolicy.ALLOW) return false
        if (tool.mustConfirm(args) != null) return false
        if (taint.couldGate(tool, args)) return false
        if (review?.covers(ToolPolicy.ALLOW, tool, args) == true) return false
        return true
    }

    /**
     * Runs [batch] at the same time and finishes each in call order: its events (held back while it
     * ran), its taint, its bounded result and audit entry, as [execute] does for a single call. Each
     * result goes [into] the turn's results as soon as it and those before it are done, so a
     * cancellation keeps the ones that finished.
     */
    private suspend fun FlowCollector<AgentEvent>.executeTogether(
        conversationId: String,
        batch: List<ResolvedCall>,
        transcript: List<Message>,
        review: RunReview?,
        taint: RunTaint,
        into: MutableList<Part.ToolResult>,
    ): Unit = coroutineScope {
        val running = batch.map { call ->
            async {
                // A flow can only be emitted to from its own coroutine; events wait here until their turn.
                val events = mutableListOf<AgentEvent>()
                val ran = with(FlowCollector<AgentEvent> { events += it }) { runCall(conversationId, call, transcript, review, taint) }
                ran to events
            }
        }
        for (deferred in running) {
            val (ran, events) = deferred.await()
            events.forEach { emit(it) }
            into += finish(conversationId, ran, taint)
        }
    }

    /**
     * A call that has run (or been refused), before its result is bounded, audited and reported. [call]
     * is what ran (the tool a call_tool named); [original] is what the model sent, which its result answers.
     */
    private class Ran(
        val original: Part.ToolCall,
        val call: Part.ToolCall,
        val tool: Tool?,
        val decision: ApprovalDecision,
        val result: ToolResult,
        val review: ReviewAudit?,
        val startedAt: Long,
    )

    private suspend fun FlowCollector<AgentEvent>.execute(
        conversationId: String,
        resolved: ResolvedCall,
        transcript: List<Message>,
        review: RunReview?,
        taint: RunTaint,
    ): Part.ToolResult = finish(conversationId, runCall(conversationId, resolved, transcript, review, taint), taint)

    private suspend fun FlowCollector<AgentEvent>.runCall(
        conversationId: String,
        resolved: ResolvedCall,
        transcript: List<Message>,
        review: RunReview?,
        taint: RunTaint,
    ): Ran {
        val startedAt = clock()
        val call = resolved.call
        val tool = tools[call.name]
        val args = runCatching { LlmJson.parseToJsonElement(call.argumentsJson) as JsonObject }.getOrNull()

        val (decision, result, reviewed) = when {
            resolved.error != null -> Authorized(ApprovalDecision.INVALID_INPUT, ToolResult.error(resolved.error))
            tool == null -> Authorized(ApprovalDecision.INVALID_INPUT, ToolResult.error("Unknown tool '${call.name}'."))
            args == null || INVALID_INPUT_KEY in args -> Authorized(
                ApprovalDecision.INVALID_INPUT,
                ToolResult.error(buildJsonObject { put("INVALID_JSON", args?.str(INVALID_INPUT_KEY) ?: call.argumentsJson) }.toString()),
            )
            else -> validateArguments(tool.spec.inputSchema, args)?.let { Authorized(ApprovalDecision.INVALID_INPUT, ToolResult.error(it)) }
                ?: authorizeAndRun(conversationId, call, tool, args, transcript, review, taint)
        }
        return Ran(resolved.original, call, tool, decision, result, reviewed, startedAt)
    }

    private suspend fun FlowCollector<AgentEvent>.finish(conversationId: String, ran: Ran, taint: RunTaint): Part.ToolResult {
        val call = ran.call
        val decision = ran.decision
        val result = ran.result
        val reviewed = ran.review
        val startedAt = ran.startedAt
        // From here on the run has read whatever this call brought in. Locus's own refusals don't count.
        if (decision in RAN && Taint.isSource(call.name, result.content)) taint.sources += call.name

        val bounded = bound(ran.tool, call.name, result)
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
        // The result answers the call the model made: a call_tool's keeps that name, which providers match it by.
        return Part.ToolResult(ran.original.id, ran.original.name, bounded.content, bounded.isError, decision)
    }

    private suspend fun FlowCollector<AgentEvent>.authorizeAndRun(
        conversationId: String,
        call: Part.ToolCall,
        tool: Tool,
        args: JsonObject,
        transcript: List<Message>,
        review: RunReview?,
        taint: RunTaint,
    ): Authorized {
        // Deterministic limits come first and the reviewer is never consulted for them.
        config.restrict?.invoke(tool, args)?.let { reason -> return Authorized(ApprovalDecision.POLICY_DENIED, ToolResult.error(reason)) }
        val basePolicy = if (config.readOnly && !tool.isObservation(args)) ToolPolicy.DENY else policyFor(tool)
        // Once the run has read outside content, calls that could carry data out or persist instructions
        // need a person (or the reviewer, told why); see Taint and docs/design/taint.md.
        val taintReason = if (basePolicy == ToolPolicy.DENY) null else taint.reason(tool, args)
        if (taintReason != null && config.readOnly) {
            return Authorized(
                ApprovalDecision.POLICY_DENIED,
                ToolResult.error(
                    "Not allowed: this background run has read outside content (from ${taint.sources.sorted().joinToString()}), so it can't " +
                        "send anything, open addresses that weren't in its task, or save for later chats. Continue without it.",
                ),
            )
        }
        val confirmReason = (if (basePolicy == ToolPolicy.DENY) null else tool.mustConfirm(args))
            .let { own -> listOfNotNull(own, taintReason).joinToString("\n").ifEmpty { null } }
        val policy = if (confirmReason != null) ToolPolicy.ASK else basePolicy
        if (policy == ToolPolicy.DENY) {
            return Authorized(
                ApprovalDecision.POLICY_DENIED,
                ToolResult.error(if (config.readOnly) "Not allowed: this is a read-only background run." else "Not allowed: the user has turned this tool off."),
            )
        }
        suspend fun callContext() = callContext(conversationId, call.id, transcript, review, taint)
        // A call that can only fail (an edit to a file the chat hasn't read) says so without asking anyone.
        withContext(callContext() + ChatScope.element(config.scope)) { tool.precheck(args) }?.let { reason -> return Authorized(ApprovalDecision.INVALID_INPUT, ToolResult.error(reason)) }
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
            withContext(context + ChatScope.element(config.scope)) { tool.execute(args) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            ToolResult.error(e.message.orEmpty())
        } catch (e: Exception) {
            ToolResult.error("${e::class.simpleName}: ${e.message}")
        }
        return Authorized(decision, result, checked?.audit)
    }

    /**
     * The one place a result is fitted to the transcript. A long result keeps its start and end inline
     * ([AgentConfig.inlineResultChars]) and all of it is saved to a workspace file the result names,
     * the file a tool saved itself ([ToolResult.savedTo], run_command) or one [AgentConfig.spill]
     * writes. Tools that page their own output, and results that couldn't be saved, are only capped at
     * [AgentConfig.maxResultChars]. Marked outside text stays marked (see [LongOutput]).
     */
    private suspend fun bound(tool: Tool?, name: String, result: ToolResult): ToolResult {
        val content = result.content
        if (tool?.pagesOwnOutput == true || (content.length <= config.inlineResultChars && result.savedTo == null)) {
            return if (content.length > config.maxResultChars) result.copy(content = LongOutput.shorten(content, config.maxResultChars, null)) else result
        }
        val savedTo = result.savedTo ?: config.spill?.let { spill -> withContext(ChatScope.element(config.scope)) { spill.save(name, content) } }
        val limit = if (savedTo != null) config.inlineResultChars else config.maxResultChars
        return result.copy(content = LongOutput.shorten(content, limit, savedTo), savedTo = savedTo)
    }

    private fun approvalRequest(conversationId: String, call: Part.ToolCall, tool: Tool, args: JsonObject, confirmReason: String?, note: String?) =
        ApprovalRequest(
            conversationId, call.id, tool.spec.name, tool.risk,
            tool.describe(args) + (confirmReason?.let { "\n$it" } ?: ""),
            call.argumentsJson,
            reviewerNote = note,
        )

    private data class Authorized(val decision: ApprovalDecision, val result: ToolResult, val review: ReviewAudit? = null)

    /** One run's taint: the outside content it has read, and the hosts its user named (see [Taint]). */
    private inner class RunTaint(val sources: MutableSet<String>, val userHosts: MutableSet<String>) {
        suspend fun reason(tool: Tool, args: JsonObject): String? {
            if (sources.isEmpty()) return null
            return Taint.reason(tool, args, sources, userHosts + config.trustedHosts?.invoke().orEmpty())
        }

        /**
         * Whether taint would hold [tool] back if the run were tainted: calls that read outside text
         * can run beside this one, so it mustn't be one taint gates (see [runsTogether]).
         */
        suspend fun couldGate(tool: Tool, args: JsonObject): Boolean =
            Taint.reason(tool, args, sources.ifEmpty { setOf("a call beside this one") }, userHosts + config.trustedHosts?.invoke().orEmpty()) != null

        fun seed() = TaintSeed(sources.toSet(), userHosts.toSet())
    }

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

    /** The context [callId] runs with in this run (see [ToolCallContext]). */
    private suspend fun callContext(conversationId: String, callId: String, transcript: List<Message>, review: RunReview?, taint: RunTaint) = ToolCallContext(
        conversationId, callId, config.readOnly || config.chatReadOnly?.invoke() == true, config.depth, config.restrict,
        review?.let { ReviewerSetup(it.setup.reviewer, it.setup.mode, it.setup.allowOutward, it.setup.timeoutMs, it.trusted) },
        config.budget?.takeIf { it.active },
        taint.seed(),
        config.author,
        config.changeOwner ?: turnOwner(conversationId, transcript),
        config.scope,
    )

    /**
     * The chat's current turn: the first message after the latest message that starts a turn (see
     * [dev.locus.core.model.startsTurn]), which is how the chat screen keys turns. Null before any.
     */
    private fun turnOwner(conversationId: String, transcript: List<Message>): dev.locus.core.agent.tools.ChangeOwner? {
        val start = transcript.indexOfLast { it.startsTurn() }
        val first = transcript.getOrNull(start + 1) ?: return null
        return dev.locus.core.agent.tools.ChangeOwner(conversationId, first.id)
    }

    private fun skipped(call: Part.ToolCall, reason: String) = Part.ToolResult(call.id, call.name, reason, isError = true)

    private suspend fun FlowCollector<AgentEvent>.appendAndEmit(message: Message, transcript: MutableList<Message>) {
        store.append(message)
        transcript += message
        emit(AgentEvent.MessageAppended(message))
    }
}

private fun <T> concurrentSetOf(items: Collection<T>): MutableSet<T> =
    java.util.concurrent.ConcurrentHashMap.newKeySet<T>().apply { addAll(items) }

/** "45 s", "12 min", "3 h": a wait the provider asked for, for a message. */
internal fun waitLabel(ms: Long): String = when {
    ms < 90_000 -> "${(ms + 999) / 1000} s"
    ms < 90 * 60_000 -> "${(ms + 59_999) / 60_000} min"
    else -> "${(ms + 3_599_999) / 3_600_000} h"
}
