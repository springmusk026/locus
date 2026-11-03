package dev.locus.core.data.agent

import dev.locus.core.model.AppSettings
import dev.locus.core.model.Role
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chat has at most one agent run. The UI, the service, scheduled tasks, Telegram, the widget, the
 * intent API, voice mode and helper reports can all start one at the same moment from different
 * threads: exactly one wins, and the rest are refused (or queued), never a second agent on the chat.
 */
class OneRunPerChatTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private fun TestGraph.chatRequests() = synchronized(requests) { requests.count { it.url.host == "llm.test" } }

    /** Until the chat shows idle and no run holds any slot (its cleanup finished too). */
    private suspend fun AgentRunner.idle(chat: String) {
        states.first { it[chat]?.running != true }
        while (activeCount > 0) delay(5)
    }

    /** Answers every chat request with a short reply, from any thread; once [hold] gives a gate, after it opens. */
    private fun TestGraph.answerAll(hold: () -> CompletableDeferred<Unit>? = { null }) {
        http = { request ->
            if (request.url.host == "llm.test") {
                hold()?.await()
                respond(ScriptedProvider.text("Done."), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
    }

    @Test
    fun `parallel starts on real threads run exactly one agent per chat`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val g = TestGraph(scope, settings)
            // The winning run's reply waits until every caller has tried. Unheld, a run that takes a few
            // milliseconds can end before a slow thread even calls, and that thread then rightly starts
            // the chat's next run: one at a time, yet two in the round (as on CI's busier runners).
            val reply = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>>()
            g.answerAll { reply.get() }
            val runner = g.runner
            repeat(ROUNDS) { round ->
                val chat = runBlocking { g.conversations.create(ScriptedProvider.config.id).id }
                val barrier = CyclicBarrier(THREADS)
                val started = java.util.concurrent.ConcurrentLinkedQueue<String>()
                val held = CompletableDeferred<Unit>().also(reply::set)
                val futures = (1..THREADS).map {
                    pool.submit {
                        barrier.await(10, TimeUnit.SECONDS)
                        runner.sendFromApp(chat, "go $round", "[note]")?.let(started::add)
                    }
                }
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, started.size, "round $round: exactly one caller starts the run")
                // Still held: the chat shows its one run, and a latecomer is refused as well.
                assertTrue(runner.states.value.getValue(chat).running, "round $round: shown as running")
                assertNull(runner.sendFromApp(chat, "late $round", "[note]"), "round $round: a second run started")
                held.complete(Unit)
                runBlocking {
                    withTimeout(30_000) {
                        assertEquals("Done.", runner.awaitRun(started.single())?.answer)
                        runner.idle(chat)
                    }
                }
                val history = runBlocking { g.conversations.history(chat) }
                assertEquals(1, history.count { it.role == Role.USER }, "round $round: one user message")
                assertEquals(1, history.count { it.role == Role.ASSISTANT }, "round $round: one reply")
                assertFalse(runner.states.value.getValue(chat).running)
            }
            assertEquals(ROUNDS, g.chatRequests())
        } finally {
            pool.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun `parallel sends on real threads run one agent and queue the rest`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val g = TestGraph(scope, settings)
            g.answerAll()
            val runner = g.runner
            val chat = runBlocking { g.conversations.create(ScriptedProvider.config.id).id }
            val barrier = CyclicBarrier(THREADS)
            val futures = (1..THREADS).map { i ->
                pool.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    runner.send(chat, "message $i")
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            // Every message gets in, each exactly once; never two turns streaming at once.
            runBlocking {
                withTimeout(30_000) {
                    runner.states.first { st ->
                        st[chat]?.running == false && st[chat]?.queued.isNullOrEmpty() &&
                            g.conversations.history(chat).count { it.role == Role.USER } == THREADS
                    }
                    runner.idle(chat)
                }
            }
            val history = runBlocking { g.conversations.history(chat) }
            val users = history.filter { it.role == Role.USER }.map { it.text }
            assertEquals((1..THREADS).map { "message $it" }.toSet(), users.toSet())
            assertEquals(THREADS, users.size)
            // A single run's transcript alternates: no two replies were streamed into one turn side by side.
            history.zipWithNext().forEach { (a, b) -> assertFalse(a.role == Role.ASSISTANT && b.role == Role.ASSISTANT, "two replies in a row: $history") }
        } finally {
            pool.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun `an old run finishing never clears the slot or state of a newer run`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val g = TestGraph(scope, settings)
            // Every request waits forever: each run here ends by Stop.
            val asked = Channel<Unit>(Channel.UNLIMITED)
            g.http = { request ->
                if (request.url.host == "llm.test") {
                    asked.send(Unit)
                    CompletableDeferred<Unit>().await()
                    respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                } else {
                    respond("", HttpStatusCode.NotFound)
                }
            }
            val runner = g.runner
            val chat = g.conversations.create(ScriptedProvider.config.id).id
            withTimeout(60_000) {
                assertTrue(runner.sendFromApp(chat, "run 0", "[note]") != null)
                asked.receive()
                repeat(20) { round ->
                    // Stop the run, and start the next one the moment the chat is free, while the old
                    // run's cleanup may still be finishing on another thread.
                    runner.cancel(chat)
                    while (runner.sendFromApp(chat, "run ${round + 1}", "[note]") == null) Thread.onSpinWait()
                    asked.receive()
                    // The new run holds the chat: the old one's end neither freed it nor marked it idle.
                    assertTrue(runner.states.value.getValue(chat).running, "round $round: shown as running")
                    assertNull(runner.sendFromApp(chat, "extra", "[note]"), "round $round: a second run started")
                    assertEquals(1, runner.activeCount, "round $round")
                }
                runner.cancel(chat)
                runner.idle(chat)
            }
            assertEquals(0, runner.activeCount)
            assertEquals((0..20).map { "run $it" }, g.conversations.history(chat).filter { it.role == Role.USER }.map { it.text.lineSequence().first() })
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val THREADS = 16
        const val ROUNDS = 25
    }
}
