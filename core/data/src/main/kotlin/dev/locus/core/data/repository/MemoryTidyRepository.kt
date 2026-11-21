package dev.locus.core.data.repository

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.MemoryTidy
import dev.locus.core.data.agent.HelperModel
import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.di.IoDispatcher
import dev.locus.core.model.UsageSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether Tidy up can run now, and if not, why (the button says so). */
enum class TidyAvailability {
    READY,

    /** No model provider is set up at all: Settings → Models. */
    NO_MODEL,

    /** The helper model can't be used: on-device (too slow for this), or its provider has no key or sign-in: Settings → Helper model & extras. */
    HELPER_UNAVAILABLE,
}

/** What a tidy-up came to. */
sealed interface TidyOutcome {
    /** The proposals to review (possibly none), how many notes were read and left out, and items dropped from the answer. */
    data class Proposals(val snapshot: MemoryTidy.Snapshot, val proposals: List<MemoryTidy.Proposal>, val dropped: Int) : TidyOutcome

    /** Why it failed; nothing was changed. */
    data class Failed(val reason: TidyFailure) : TidyOutcome
}

enum class TidyFailure {
    /** The helper model couldn't be used (see [TidyAvailability]). */
    UNAVAILABLE,

    /** The request failed or timed out, or the answer was empty. */
    NO_ANSWER,

    /** The answer held no list of changes Locus could read. */
    MALFORMED,

    /** There are no notes to tidy. */
    NOTHING,
}

/**
 * Library → Memory → Tidy up (docs/design/memory-approval.md): one helper-model request over every memory
 * (general and each project's), proposals the user reviews, and only accepted ones applied through the
 * memory stores Library's own Edit and Forget use. Never runs on its own.
 */
@Singleton
class MemoryTidyRepository @Inject constructor(
    private val home: AgentHome,
    private val helper: HelperModel,
    private val settings: SettingsRepository,
    private val access: ProviderAccess,
    private val projects: dev.locus.core.database.ProjectDao,
    private val library: LibraryRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Whether a tidy-up can run, from the helper model the settings pick (as for titles and suggestions). */
    suspend fun availability(): TidyAvailability {
        val s = settings.current()
        val base = s.provider(s.defaultProviderId) ?: return TidyAvailability.NO_MODEL
        val provider = helper.providerFor(base) ?: return TidyAvailability.HELPER_UNAVAILABLE
        return if (access.missing(provider) != null) TidyAvailability.HELPER_UNAVAILABLE else TidyAvailability.READY
    }

    /** Every memory with notes: general memory first, then each project's, by name. */
    private suspend fun groups(): List<MemoryTidy.Group> = withContext(io) {
        val general = MemoryTidy.Group(null, null, home.memoryTopics())
        val own = projects.all().mapNotNull { p ->
            home.projectMemoryTopics(p.id).takeIf { it.isNotEmpty() }?.let { MemoryTidy.Group(p.id, p.name.ifBlank { null }, it) }
        }.sortedBy { it.projectName.orEmpty().lowercase() }
        (listOf(general) + own).filter { it.topics.isNotEmpty() }
    }

    /**
     * Reads the notes and asks the helper model for proposals. Changes nothing. Its tokens are recorded under
     * [UsageSource.MEMORY_TIDY], so Insights shows what it cost.
     */
    suspend fun propose(now: Long = System.currentTimeMillis()): TidyOutcome {
        if (availability() != TidyAvailability.READY) return TidyOutcome.Failed(TidyFailure.UNAVAILABLE)
        val snapshot = MemoryTidy.snapshot(groups())
        if (snapshot.entries.isEmpty()) return TidyOutcome.Failed(TidyFailure.NOTHING)
        val s = settings.current()
        val answer = helper.complete(
            s.provider(s.defaultProviderId), MemoryTidy.prompt(snapshot, now),
            maxOutputTokens = MAX_OUTPUT_TOKENS, timeoutMs = TIMEOUT_MS, source = UsageSource.MEMORY_TIDY,
        ) ?: return TidyOutcome.Failed(TidyFailure.NO_ANSWER)
        return when (val parsed = MemoryTidy.parse(answer, snapshot)) {
            MemoryTidy.Parsed.Malformed -> TidyOutcome.Failed(TidyFailure.MALFORMED)
            is MemoryTidy.Parsed.Proposals -> TidyOutcome.Proposals(snapshot, parsed.proposals, parsed.dropped)
        }
    }

    /** Applies only [accepted]; a proposal whose notes changed since is skipped. Library shows the result. */
    suspend fun apply(accepted: List<MemoryTidy.Proposal>): MemoryTidy.Applied {
        if (accepted.isEmpty()) return MemoryTidy.Applied(0, emptyList())
        val result = withContext(io) {
            MemoryTidy.apply(accepted) { projectId -> if (projectId == null) home.globalMemory else home.projectMemory(projectId) }
        }
        library.refresh()
        return result
    }

    private companion object {
        /** Merged notes are written out in full. */
        const val MAX_OUTPUT_TOKENS = 6_000
        const val TIMEOUT_MS = 180_000L
    }
}
