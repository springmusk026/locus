package dev.locus.core.data.repository

import dev.locus.core.database.UsageEventDao
import dev.locus.core.database.UsageEventEntity
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.UsageSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records model requests that aren't stored as messages (titles, suggestions, compaction, the safety
 * reviewer, reflection, other helper-model jobs), so Insights can show where tokens went. Counts and
 * timing only, never content. Recording never fails the request it describes.
 */
@Singleton
class UsageLog @Inject constructor(private val events: UsageEventDao) {
    suspend fun record(
        source: UsageSource,
        conversationId: String?,
        providerId: String?,
        model: String?,
        usage: TokenUsage?,
        totalMs: Long?,
        at: Long = System.currentTimeMillis(),
    ) {
        try {
            events.insert(
                UsageEventEntity(
                    conversationId = conversationId,
                    source = source.wire,
                    createdAt = at,
                    providerId = providerId,
                    model = model,
                    usageJson = usage?.let { json.encodeToString(TokenUsage.serializer(), it) },
                    totalMs = totalMs,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The chat was deleted meanwhile (the foreign key refuses the row), or the disk is full: skip it.
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
