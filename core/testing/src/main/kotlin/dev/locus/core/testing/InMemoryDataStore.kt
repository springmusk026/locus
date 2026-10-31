package dev.locus.core.testing

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A DataStore kept in memory: updates are serialised like the real one, and [data] replays the latest value. */
class InMemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val lock = Mutex()

    override val data: Flow<T> = state

    /** The current value, for assertions. */
    val value: T get() = state.value

    override suspend fun updateData(transform: suspend (t: T) -> T): T = lock.withLock {
        transform(state.value).also { state.value = it }
    }
}
