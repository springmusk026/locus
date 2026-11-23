package dev.locus.core.data.needsyou

import dev.locus.core.agent.ProposalDraft
import dev.locus.core.agent.ProposalReceipt
import dev.locus.core.agent.ProposalSink
import dev.locus.core.agent.Proposals
import dev.locus.core.database.ProposalDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proposals from unattended runs (docs/design/needs-you.md): recorded by a run, listed in Needs you, and moved on
 * by the user. Every change of state is one guarded statement ([dev.locus.core.database.CLAIM_PROPOSAL] and the
 * DAO's updates), so a proposal runs at most once whatever taps, notifications or restarts happen.
 */
@Singleton
class ProposalRepository(
    private val dao: ProposalDao,
    private val clock: () -> Long,
    private val ids: () -> String,
) {
    @Inject constructor(dao: ProposalDao) : this(dao, System::currentTimeMillis, { UUID.randomUUID().toString() })

    /** Every proposal not dismissed, newest first; decided ones are left to the caller to hide. */
    val open: Flow<List<Proposal>> = dao.observeOpen().map { rows -> rows.map(Proposal::of) }

    /** The sink an unattended run of [origin] records into. */
    fun sink(origin: ProposalOrigin): ProposalSink = ProposalSink { draft -> record(origin, draft) }

    suspend fun record(origin: ProposalOrigin, draft: ProposalDraft): ProposalReceipt {
        val now = clock()
        dao.pendingDuplicate(draft.conversationId, draft.toolName, draft.argumentsJson, now)?.let { return ProposalReceipt.Recorded(it, duplicate = true) }
        if (dao.pendingCount(now) >= Proposals.MAX_PENDING) {
            return ProposalReceipt.Refused("${Proposals.MAX_PENDING} proposals are already waiting for the user, the most Locus keeps.")
        }
        val id = ids()
        dao.insert(Proposal.entity(id, draft, origin, now, now + Proposals.EXPIRY_MS))
        return ProposalReceipt.Recorded(id)
    }

    /** Whether anything still waiting was proposed at or after [since]: a run that just proposed (TaskWorker folds its result notification into Needs you's). */
    suspend fun proposedSince(since: Long): Boolean {
        val now = clock()
        return open.first().any { it.approvable(now) && it.createdAt >= since }
    }

    /** The time proposals are stamped and expired by. */
    fun now(): Long = clock()

    suspend fun get(id: String): Proposal? = dao.get(id)?.let(Proposal::of)

    /** Takes [id] to run it: true for exactly one caller, and only while it's waiting and not expired. */
    suspend fun claim(id: String): Boolean = dao.claim(id, clock()) == 1

    /** How a claimed proposal went. */
    suspend fun finish(id: String, ran: Boolean, outcome: String) {
        dao.finish(id, if (ran) ProposalStatus.DONE.name else ProposalStatus.FAILED.name, outcome.take(500))
    }

    /** Declines a waiting one; false when it had already been decided. */
    suspend fun decline(id: String): Boolean = dao.decline(id, clock()) == 1

    /** Clears it from Needs you (an expired, failed, interrupted or restored one). */
    suspend fun dismiss(id: String) {
        dao.dismiss(id)
    }

    /** At startup: a proposal a dead process was running is never run again. */
    suspend fun recoverInterrupted() {
        dao.interruptRunning("Locus was closed while it ran, so it may or may not have happened.")
    }
}
