package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Which of a provider's API keys to use. Sticky until error: every request uses the key that last
 * worked, so the provider's prompt cache (scoped to the account behind the key) keeps hitting. A key
 * that fails with a quota, auth or rate-limit error rests ([Failover.keyRestMs]) and the next one
 * takes over; a refused key rests until the keys change. Round-robin would spread load, but every
 * switch is a cache miss on a new account, which costs more than it saves for one person's chats.
 *
 * Only indexes and timestamps are kept, in memory: never the keys themselves.
 */
class KeyPool(private val clock: () -> Long = System::currentTimeMillis) {
    private class State(val fingerprint: Int, size: Int) {
        var current = 0
        val restUntil = LongArray(size)
    }

    private val states = HashMap<String, State>()

    private fun state(providerId: String, keys: List<String>): State {
        val fingerprint = keys.hashCode()
        val existing = states[providerId]
        if (existing != null && existing.fingerprint == fingerprint) return existing
        // New or edited keys: start over from the first.
        return State(fingerprint, keys.size).also { states[providerId] = it }
    }

    /**
     * The key to try next, skipping [tried]: the current one if it isn't resting, else the next that
     * isn't. On the first try of a request (nothing [tried]) with every key resting, the one that rests
     * shortest is used anyway, so a request is always attempted. Null when nothing is left to try.
     */
    @Synchronized
    fun pick(providerId: String, keys: List<String>, tried: Set<Int> = emptySet()): Int? {
        if (keys.isEmpty()) return null
        val s = state(providerId, keys)
        val now = clock()
        val order = keys.indices.map { (s.current + it) % keys.size }.filter { it !in tried }
        order.firstOrNull { s.restUntil[it] <= now }?.let { return it }
        if (tried.isNotEmpty()) return null
        return order.minByOrNull { s.restUntil[it] }
    }

    /** The key at [index] answered: it stays in use. */
    @Synchronized
    fun succeeded(providerId: String, keys: List<String>, index: Int) {
        state(providerId, keys).current = index
    }

    /** The key at [index] failed with [e]: it rests, and the next one becomes current. */
    @Synchronized
    fun failed(providerId: String, keys: List<String>, index: Int, e: LlmException) {
        val s = state(providerId, keys)
        val rest = Failover.keyRestMs(e)
        val now = clock()
        s.restUntil[index] = if (rest > Long.MAX_VALUE - now) Long.MAX_VALUE else now + rest
        if (s.current == index) s.current = (index + 1) % keys.size
    }
}

/**
 * A provider with several API keys. Each request goes out on the [KeyPool]'s current key; when that
 * key fails before anything streamed with an error another key may not have ([Failover.rotatesKey]),
 * the same request goes out at once on the next key. Once output has streamed, errors pass through:
 * a reply is never stitched together from two attempts, and the agent's own retry decides what's safe.
 */
class KeyPoolChatModel(
    override val format: ApiFormat,
    private val providerId: String,
    private val keys: List<String>,
    private val pool: KeyPool,
    private val create: (apiKey: String) -> ChatModel,
) : ChatModel {
    init {
        require(keys.isNotEmpty()) { "A key pool needs at least one key." }
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val tried = mutableSetOf<Int>()
        var last: LlmException? = null
        while (true) {
            val index = pool.pick(providerId, keys, tried) ?: throw last ?: LlmException("No API key to try.")
            tried += index
            var emitted = false
            try {
                create(keys[index]).stream(request).collect { event ->
                    emitted = true
                    emit(event)
                }
                pool.succeeded(providerId, keys, index)
                return@flow
            } catch (e: LlmException) {
                if (emitted || !Failover.rotatesKey(e)) throw e
                pool.failed(providerId, keys, index, e)
                last = e
            }
        }
    }
}

/** Splits what the user typed into the backup-keys field: one key per line, comma or space. */
fun parseApiKeys(text: String): List<String> = text.split(',', '\n', ' ', '\t', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
