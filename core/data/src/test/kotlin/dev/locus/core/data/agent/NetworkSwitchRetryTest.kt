package dev.locus.core.data.agent

import dev.locus.core.model.AppSettings
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reply streaming when the phone switches networks (Wi-Fi to mobile data) is ended at once and sent
 * again on the new network, instead of hanging on a socket that died silently (docs/design/stream-timeouts.md).
 */
class NetworkSwitchRetryTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    @Test
    fun `a switch mid-stream retries the reply on the new network`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        val firstStarted = CompletableDeferred<Unit>()
        var calls = 0
        g.http = { request ->
            if (request.url.host == "llm.test") {
                calls++
                if (calls == 1) {
                    // The first answer starts, then its socket goes quiet for good.
                    val body = ByteChannel()
                    body.writeStringUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n")
                    body.flush()
                    firstStarted.complete(Unit)
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                } else {
                    respond(ScriptedProvider.text("Hello again."), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        g.networkSwitches.available("wifi")
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        runner.send(chat, "hi")
        firstStarted.await()
        g.networkSwitches.available("mobile")
        val done = runner.states.first { it[chat]?.running == false && calls == 2 }.getValue(chat)

        assertEquals("Hello again.", g.conversations.history(chat).last().text)
        assertNull(done.error)
        assertTrue(calls == 2)
    }
}
