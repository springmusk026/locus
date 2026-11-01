package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class KeyPoolTest {
    private var now = 0L
    private val pool = KeyPool { now }
    private val keys = listOf("key-a", "key-b", "key-c")
    private val request = ChatRequest("sys", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())

    /** Each key behaves as scripted: null answers, an exception fails before anything streams. */
    private inner class Keys(val failures: MutableMap<String, LlmException?>, val failAfterText: Set<String> = emptySet()) {
        val used = mutableListOf<String>()
        fun model() = KeyPoolChatModel(ApiFormat.OPENAI_CHAT, "p", keys, pool) { key ->
            object : ChatModel {
                override val format = ApiFormat.OPENAI_CHAT
                override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
                    used += key
                    if (key in failAfterText) {
                        emit(ChatEvent.TextDelta("partial"))
                        throw LlmException("quota", 429, kind = LlmFailure.QUOTA)
                    }
                    failures[key]?.let { throw it }
                    emit(ChatEvent.TextDelta("hi from $key"))
                }
            }
        }
    }

    private val quota = LlmException("quota", 429, kind = LlmFailure.QUOTA)
    private val refused = LlmException("refused", 401, kind = LlmFailure.AUTH)

    @Test
    fun `a key out of quota hands over to the next, which then sticks`() = runTest {
        val k = Keys(mutableMapOf("key-a" to quota))
        val events = k.model().stream(request).toList()
        assertEquals<List<ChatEvent>>(listOf(ChatEvent.TextDelta("hi from key-b")), events)
        // Sticky: the next request goes straight to the key that worked, not back to key-a.
        k.model().stream(request).toList()
        assertEquals(listOf("key-a", "key-b", "key-b"), k.used)
    }

    @Test
    fun `a resting key comes back once its rest is over, but only when it's its turn`() = runTest {
        val k = Keys(mutableMapOf("key-a" to LlmException("slow", 429, retryable = true, kind = LlmFailure.RATE_LIMIT, retryAfterMs = 30_000)))
        k.model().stream(request).toList()
        k.failures.clear()
        now = 31_000
        // key-b answered last and stays in use; key-a is available again should key-b fail.
        k.model().stream(request).toList()
        assertEquals(listOf("key-a", "key-b", "key-b"), k.used)
        k.failures["key-b"] = quota
        k.model().stream(request).toList()
        assertEquals("key-c", k.used.last())
        k.failures["key-c"] = quota
        k.model().stream(request).toList()
        assertEquals("key-a", k.used.last())
    }

    @Test
    fun `overload and bad requests don't rotate`() = runTest {
        val overloaded = LlmException("busy", 529, retryable = true, kind = LlmFailure.OVERLOADED)
        val k = Keys(mutableMapOf("key-a" to overloaded))
        assertSame(overloaded, assertFailsWith<LlmException> { k.model().stream(request).toList() })
        k.failures["key-a"] = LlmException("bad", 400)
        assertFailsWith<LlmException> { k.model().stream(request).toList() }
        assertEquals(listOf("key-a", "key-a"), k.used)
    }

    @Test
    fun `no key is switched once output streamed, so a reply never mixes two keys`() = runTest {
        val k = Keys(mutableMapOf(), failAfterText = setOf("key-a"))
        val events = mutableListOf<ChatEvent>()
        assertFailsWith<LlmException> { k.model().stream(request).collect { events += it } }
        assertEquals(listOf("key-a"), k.used)
        assertEquals<List<ChatEvent>>(listOf(ChatEvent.TextDelta("partial")), events)
    }

    @Test
    fun `when every key fails the last error surfaces, and refused keys stay out until the keys change`() = runTest {
        val k = Keys(mutableMapOf("key-a" to refused, "key-b" to quota, "key-c" to quota))
        val e = assertFailsWith<LlmException> { k.model().stream(request).toList() }
        assertEquals("quota", e.message)
        assertEquals(listOf("key-a", "key-b", "key-c"), k.used)
        // An hour later the quota keys are tried again, the refused one never.
        now = Failover.QUOTA_REST_MS + 1
        k.failures.clear()
        k.used.clear()
        k.model().stream(request).toList()
        assertEquals(1, k.used.size)
        assertEquals(true, k.used.single() != "key-a")
        // Editing the keys starts over.
        assertEquals(0, pool.pick("p", listOf("key-a", "key-d")))
    }

    @Test
    fun `with every key resting a request still goes out, on the one that rests shortest`() {
        pool.failed("p", keys, 0, LlmException("r", 429, kind = LlmFailure.RATE_LIMIT, retryAfterMs = 50_000))
        pool.failed("p", keys, 1, LlmException("r", 429, kind = LlmFailure.RATE_LIMIT, retryAfterMs = 10_000))
        pool.failed("p", keys, 2, LlmException("r", 429, kind = LlmFailure.RATE_LIMIT, retryAfterMs = 20_000))
        assertEquals(1, pool.pick("p", keys))
        assertNull(pool.pick("p", keys, tried = setOf(1)))
    }

    @Test
    fun `backup keys are split on commas, spaces and new lines`() {
        assertEquals(listOf("a", "b", "c"), parseApiKeys(" a, b\nc  a ,"))
    }
}
