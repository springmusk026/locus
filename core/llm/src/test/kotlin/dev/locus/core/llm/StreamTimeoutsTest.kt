package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The stream idle timeout and the network-switch interrupt, on virtual time (docs/design/stream-timeouts.md). */
class StreamTimeoutsTest {
    /** The timer on the test scheduler: virtual time, no real waits. */
    private fun TestScope.idle() = StreamIdle(
        silentMs = 300_000,
        keepaliveMs = 180_000,
        clock = IdleClock { ms, action ->
            val timer = launch(StandardTestDispatcher(testScheduler)) { delay(ms); action() }
            return@IdleClock { timer.cancel() }
        },
    )

    /** Reads [body] as SSE while [write] feeds it; returns the events and when reading ended. */
    private fun TestScope.readWhile(write: suspend ByteChannel.() -> Unit): Pair<List<SseEvent>, Result<Unit>> {
        val body = ByteChannel()
        val events = mutableListOf<SseEvent>()
        val writer = launch { body.write() }
        var result: Result<Unit>? = null
        launch { result = runCatching { body.forEachSseEvent(idle()) { events += it } }; writer.cancel() }
        testScheduler.advanceUntilIdle()
        return events to result!!
    }

    private suspend fun ByteChannel.line(text: String) {
        writeStringUtf8(text)
        flush()
    }

    @Test
    fun `a silent stream ends after the silent limit, not before`() = runTest {
        val (_, result) = readWhile {
            line("data: {\"x\":1}\n\n")
            awaitCancellation()
        }
        val endedAt = currentTime
        val e = assertFailsWith<StreamIdleException> { result.getOrThrow() }
        assertEquals(300_000, e.idleMs)
        assertEquals(300_000, endedAt)
        // An IOException, so the agent loop retries it as a dropped connection.
        assertTrue(e is java.io.IOException)
    }

    @Test
    fun `a model thinking silently for four minutes still finishes`() = runTest {
        val (events, result) = readWhile {
            line("data: {\"x\":1}\n\n")
            delay(240_000)
            line("data: {\"x\":2}\n\n")
            close()
        }
        result.getOrThrow()
        assertEquals(2, events.size)
    }

    @Test
    fun `pings keep a long-thinking stream alive, and a stream that pinged gets the shorter limit`() = runTest {
        // Ten minutes of thinking with a ping every 15 s, then the answer: never cut off.
        val (events, result) = readWhile {
            line("event: message_start\ndata: {\"type\":\"message_start\"}\n\n")
            repeat(40) {
                delay(15_000)
                line("event: ping\ndata: {\"type\": \"ping\"}\n\n")
            }
            line("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
            close()
        }
        result.getOrThrow()
        assertEquals("message_stop", events.last().event)

        // Once pings have been seen, silence ends the stream after three minutes.
        val (_, dead) = readWhile {
            line("event: ping\ndata: {\"type\": \"ping\"}\n\n")
            awaitCancellation()
        }
        assertEquals(180_000, assertFailsWith<StreamIdleException> { dead.getOrThrow() }.idleMs)
    }

    @Test
    fun `SSE comments and Responses keepalive events count as keepalives`() = runTest {
        val (_, comment) = readWhile {
            line(": OPENROUTER PROCESSING\n\n")
            awaitCancellation()
        }
        assertEquals(180_000, assertFailsWith<StreamIdleException> { comment.getOrThrow() }.idleMs)

        val (_, keepalive) = readWhile {
            line("data: {\"type\":\"keepalive\",\"sequence_number\":3}\n\n")
            awaitCancellation()
        }
        assertEquals(180_000, assertFailsWith<StreamIdleException> { keepalive.getOrThrow() }.idleMs)
    }

    // ---- Network switches ------------------------------------------------------------------------

    private val request = ChatRequest("sys", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())

    /** A model that streams one delta, then waits on [release] (a stalled socket) before completing. */
    private class StalledModel(val release: CompletableDeferred<Unit> = CompletableDeferred()) : ChatModel {
        var cancelled = false
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            emit(ChatEvent.TextDelta("Hel"))
            try {
                release.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            emit(ChatEvent.TextDelta("lo"))
        }
    }

    @Test
    fun `the first network reported is recorded, and only a different one is a switch`() {
        val switches = NetworkSwitches()
        var evicted = 0
        switches.onSwitch { evicted++ }
        switches.available("wifi")
        assertEquals(0, switches.generation.value)
        switches.available("wifi")
        assertEquals(0, switches.generation.value)
        switches.available("mobile")
        assertEquals(1, switches.generation.value)
        switches.available("wifi")
        assertEquals(2, switches.generation.value)
        assertEquals(2, evicted)
    }

    @Test
    fun `a switch ends a stream in flight with a retryable error and cancels the call`() = runTest {
        val switches = NetworkSwitches().apply { available("wifi") }
        val inner = StalledModel()
        val model = InterruptOnNetworkSwitch(inner, switches)
        val seen = mutableListOf<ChatEvent>()
        val run = async { runCatching { model.stream(request).collect { seen += it } } }
        runCurrent()
        assertEquals(1, seen.size)
        switches.available("mobile")
        runCurrent()
        val e = run.await().exceptionOrNull()
        assertTrue(e is NetworkSwitchedException, "got $e")
        assertTrue(e is java.io.IOException)
        assertTrue(inner.cancelled)
    }

    @Test
    fun `a stream started after a switch, or with no switch, runs to the end`() = runTest {
        val switches = NetworkSwitches().apply { available("wifi"); available("mobile") }
        val inner = StalledModel()
        val model = InterruptOnNetworkSwitch(inner, switches)
        val run = async { model.stream(request).toList() }
        runCurrent()
        advanceTimeBy(60_000)
        inner.release.complete(Unit)
        assertEquals(2, run.await().size)
        assertFalse(inner.cancelled)
        // The same network reported again (a capability change) doesn't interrupt anything.
        switches.available("mobile")
        assertEquals(1, switches.generation.value)
    }
}
