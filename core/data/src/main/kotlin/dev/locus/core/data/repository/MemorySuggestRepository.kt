package dev.locus.core.data.repository

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.MemorySuggest
import dev.locus.core.agent.home.MemoryTidy
import dev.locus.core.data.agent.HelperModel
import dev.locus.core.data.di.IoDispatcher
import dev.locus.core.model.ChatImportSource
import dev.locus.core.model.Role
import dev.locus.core.model.UsageSource
import dev.locus.core.model.importedFrom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Which imported chats to read: from one app or all ([source] null), and how recent. */
data class SuggestScope(val source: ChatImportSource? = null, val period: SuggestPeriod = SuggestPeriod.ANY)

/** How recent an imported chat must be (by when it was last active in the other app) to be read. */
enum class SuggestPeriod(val days: Long?) {
    ANY(null),
    YEAR(365),
    MONTHS_3(91),
}

/** What a suggestion run came to. */
sealed interface SuggestOutcome {
    /**
     * New-note proposals to review (possibly none) from [plan]'s chats; [dropped] items of the answers
     * couldn't be used, and [failedRequests] of the requests got no usable answer.
     */
    data class Proposals(val plan: MemorySuggest.Plan, val proposals: List<MemoryTidy.Proposal>, val dropped: Int, val failedRequests: Int) : SuggestOutcome

    /** Why it failed; nothing was saved. [TidyFailure.NOTHING]: no imported chat in the scope had anything to read. */
    data class Failed(val reason: TidyFailure) : SuggestOutcome
}

/**
 * "Suggest memories from imported chats" (docs/design/import-chats.md): on the user's tap only, a few
 * batched helper-model requests read the imported chats in a scope and propose new notes, which land in
 * Tidy up's review. Accepted ones are saved through [MemoryTidyRepository.apply] (the memory store's own
 * save), recorded as from the imported chat and flagged as outside content. The requests' tokens are
 * recorded under [UsageSource.MEMORY_SUGGEST].
 */
@Singleton
class MemorySuggestRepository @Inject constructor(
    private val home: AgentHome,
    private val helper: HelperModel,
    private val settings: SettingsRepository,
    private val conversations: ConversationRepository,
    private val tidy: MemoryTidyRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** The same helper model as Tidy up, so the same reasons it can't run. */
    suspend fun availability(): TidyAvailability = tidy.availability()

    /** How many imported chats there are, by the app they came from (none: the entry isn't shown). */
    suspend fun importedCounts(): Map<ChatImportSource, Int> =
        conversations.allOnce().mapNotNull { it.importedFrom }.groupingBy { it }.eachCount()

    /**
     * What a run over [scope] would read, newest chats first: the batches, and what is left out. Reads the
     * chats' messages until the requests are full, nothing more; sends nothing.
     */
    suspend fun plan(scope: SuggestScope, now: Long = System.currentTimeMillis()): MemorySuggest.Plan = withContext(io) {
        val since = scope.period.days?.let { now - it * DAY_MS }
        val chats = conversations.allOnce()
            .filter { c -> c.importedFrom != null && (scope.source == null || c.importedFrom == scope.source) && (since == null || c.updatedAt >= since) }
            .sortedByDescending { it.updatedAt }
            .map { c -> MemorySuggest.SourceChat(c.id, c.importedFrom!!.displayName, c.title, c.createdAt) }
        MemorySuggest.plan(chats) { chat ->
            conversations.history(chat.id).mapNotNull { m ->
                when (m.role) {
                    Role.USER -> MemorySuggest.Line(fromUser = true, m.userText)
                    Role.ASSISTANT -> MemorySuggest.Line(fromUser = false, m.text)
                    else -> null
                }
            }
        }
    }

    /**
     * Sends [plan]'s requests one after another and gathers their proposals. Saves nothing. [onRequest]
     * says which request is going out (1-based). Each request's tokens are recorded under
     * [UsageSource.MEMORY_SUGGEST], so Insights shows what it cost.
     */
    suspend fun propose(plan: MemorySuggest.Plan, now: Long = System.currentTimeMillis(), onRequest: (Int) -> Unit = {}): SuggestOutcome {
        if (availability() != TidyAvailability.READY) return SuggestOutcome.Failed(TidyFailure.UNAVAILABLE)
        if (plan.batches.isEmpty()) return SuggestOutcome.Failed(TidyFailure.NOTHING)
        val s = settings.current()
        val existing = withContext(io) { home.memoryTopics() }
        val taken = existing.map { it.name }.toMutableSet()
        val proposals = mutableListOf<MemoryTidy.Proposal>()
        var dropped = 0
        var noAnswer = 0
        var malformed = 0
        plan.batches.forEachIndexed { i, batch ->
            onRequest(i + 1)
            val answer = helper.complete(
                s.provider(s.defaultProviderId), MemorySuggest.prompt(batch, existing, now),
                maxOutputTokens = MemorySuggest.OUTPUT_TOKENS, timeoutMs = TIMEOUT_MS, source = UsageSource.MEMORY_SUGGEST,
            )
            when (val parsed = answer?.let { MemorySuggest.parse(it, batch, taken, firstId = proposals.size + 1) }) {
                null -> noAnswer++
                MemoryTidy.Parsed.Malformed -> malformed++
                is MemoryTidy.Parsed.Proposals -> {
                    val room = (MemorySuggest.MAX_PROPOSALS - proposals.size).coerceAtLeast(0)
                    proposals += parsed.proposals.take(room)
                    dropped += parsed.dropped + (parsed.proposals.size - room).coerceAtLeast(0)
                    taken += parsed.proposals.take(room).mapNotNull { (it as? MemoryTidy.Proposal.New)?.topic }
                }
            }
        }
        val failed = noAnswer + malformed
        return when {
            failed < plan.batches.size -> SuggestOutcome.Proposals(plan, proposals, dropped, failed)
            malformed > 0 -> SuggestOutcome.Failed(TidyFailure.MALFORMED)
            else -> SuggestOutcome.Failed(TidyFailure.NO_ANSWER)
        }
    }

    /** Saves only [accepted]; a suggestion whose name was taken meanwhile is skipped, never overwriting a note. */
    suspend fun apply(accepted: List<MemoryTidy.Proposal>): MemoryTidy.Applied = tidy.apply(accepted)

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val TIMEOUT_MS = 180_000L
    }
}
