package dev.locus.core.data.updates

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * What the update check remembers between runs, in its own file (`updates.json`, not in backups):
 * the last release and its ETag, when it last asked, and the user's answers.
 */
@Serializable
data class UpdateCache(
    val etag: String? = null,
    /** The latest release as last fetched; a 304 means it still is. */
    val release: Release? = null,
    /** When GitHub last answered (epoch ms); 0 for never. */
    val checkedAt: Long = 0,
    /** Don't ask GitHub again before this (epoch ms): its rate limit for this address is used up. */
    val rateLimitedUntil: Long = 0,
    /** "Skip this version": no notification and no card for this version. */
    val skippedVersion: String? = null,
    /** The version a notification was last posted for, so each version notifies once. */
    val notifiedVersion: String? = null,
)

/** Reads and changes the [UpdateCache]; a DataStore in the app, a variable in tests. */
interface UpdateCacheStore {
    val data: Flow<UpdateCache>
    suspend fun update(transform: (UpdateCache) -> UpdateCache): UpdateCache
    suspend fun current(): UpdateCache = data.first()
}

internal class DataStoreUpdateCache(private val store: DataStore<UpdateCache>) : UpdateCacheStore {
    override val data: Flow<UpdateCache> = store.data
    override suspend fun update(transform: (UpdateCache) -> UpdateCache): UpdateCache = store.updateData(transform)
}

internal object UpdateCacheSerializer : Serializer<UpdateCache> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    override val defaultValue = UpdateCache()

    override suspend fun readFrom(input: InputStream): UpdateCache {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        return try {
            json.decodeFromString(UpdateCache.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Unreadable update cache", e)
        }
    }

    override suspend fun writeTo(t: UpdateCache, output: OutputStream) {
        output.write(json.encodeToString(UpdateCache.serializer(), t).encodeToByteArray())
    }
}
