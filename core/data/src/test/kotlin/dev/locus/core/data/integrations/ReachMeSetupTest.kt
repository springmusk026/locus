package dev.locus.core.data.integrations

import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.ReachMe
import dev.locus.core.agent.integrations.Webhook
import dev.locus.core.data.EnglishResources
import dev.locus.core.data.R
import dev.locus.core.data.connectors.ServiceSetupException
import dev.locus.core.datastore.SecretStore
import dev.locus.core.linux.EngineText
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings → Integrations → Gotify and Webhook: what's saved, kept, tested and refused. */
class ReachMeSetupTest {
    private val token = "AbCdEf1234567.8"
    private val secret = "a-very-long-signing-secret-42"
    private val gotify = SecretStore.connectorSecret(Integrations.gotify.secret)
    private val webhook = SecretStore.connectorSecret(Integrations.webhook.secret)

    private fun problem(e: ServiceSetupException) = (e.text as EngineText.Res).id

    @Test
    fun `gotify is saved as address and token, read back without the token, and removed`() = runTest {
        val g = TestGraph(backgroundScope)
        assertNull(g.reachMe.savedUrl(ReachMeChannel.GOTIFY))
        g.reachMe.save(ReachMeChannel.GOTIFY, "push.example.com/", token)
        assertEquals("https://push.example.com $token", g.secrets.get(gotify))
        assertEquals("https://push.example.com", g.reachMe.savedUrl(ReachMeChannel.GOTIFY))
        assertTrue(g.reachMe.hasSecret(ReachMeChannel.GOTIFY))
        // notify_me and scheduled tasks read the same vault value.
        assertEquals(listOf("Gotify"), ReachMe.send(g.httpClient, g.secretResolver, null, "x").map { it.channel })

        g.reachMe.remove(ReachMeChannel.GOTIFY)
        assertNull(g.secrets.get(gotify))
        assertNull(g.reachMe.savedUrl(ReachMeChannel.GOTIFY))
    }

    @Test
    fun `a blank token keeps the saved one only for the same server`() = runTest {
        val g = TestGraph(backgroundScope)
        g.reachMe.save(ReachMeChannel.GOTIFY, "https://push.example.com", token)
        g.reachMe.save(ReachMeChannel.GOTIFY, "https://push.example.com/gotify", "")
        assertEquals("https://push.example.com/gotify $token", g.secrets.get(gotify))

        val moved = assertFailsWith<ServiceSetupException> { g.reachMe.save(ReachMeChannel.GOTIFY, "https://evil.example.com", "") }
        assertEquals(R.string.data_reachme_token_again, problem(moved))
        assertEquals("https://push.example.com/gotify $token", g.secrets.get(gotify))
    }

    @Test
    fun `bad input is refused in the user's words`() = runTest {
        val g = TestGraph(backgroundScope)
        assertEquals(R.string.data_reachme_token_missing, problem(assertFailsWith { g.reachMe.save(ReachMeChannel.GOTIFY, "https://push.example.com", "") }))
        assertEquals(R.string.data_reachme_token_invalid, problem(assertFailsWith { g.reachMe.save(ReachMeChannel.GOTIFY, "https://push.example.com", "short") }))
        assertEquals(R.string.data_reachme_secret_invalid, problem(assertFailsWith { g.reachMe.save(ReachMeChannel.WEBHOOK, "https://h.example.com/x", "too short") }))
        // The address rule is the self-hosted one: http only on this phone or the local network.
        val http = assertFailsWith<ServiceSetupException> { g.reachMe.save(ReachMeChannel.WEBHOOK, "http://h.example.com/x", null) }
        // In the user's words too (docs/design/localization.md): the address check's own text, in English its message.
        assertEquals(EngineText.Res(R.string.data_address_http_public), http.text)
        assertNull(g.secrets.get(webhook))
    }

    @Test
    fun `a webhook may be unsigned, keeps its secret for the same origin, and asks again for another`() = runTest {
        val g = TestGraph(backgroundScope)
        g.reachMe.save(ReachMeChannel.WEBHOOK, "https://h.example.com/x", null)
        assertEquals("https://h.example.com/x", g.secrets.get(webhook))
        assertFalse(g.reachMe.hasSecret(ReachMeChannel.WEBHOOK))

        g.reachMe.save(ReachMeChannel.WEBHOOK, "https://h.example.com/x", secret)
        assertTrue(g.reachMe.hasSecret(ReachMeChannel.WEBHOOK))
        g.reachMe.save(ReachMeChannel.WEBHOOK, "https://h.example.com/y?k=1", "")
        assertEquals("https://h.example.com/y?k=1 $secret", g.secrets.get(webhook))
        assertEquals(R.string.data_reachme_secret_again, problem(assertFailsWith { g.reachMe.save(ReachMeChannel.WEBHOOK, "https://other.example.com/y", "") }))

        // Turning signing off drops the secret.
        g.reachMe.save(ReachMeChannel.WEBHOOK, "https://other.example.com/y", null)
        assertEquals("https://other.example.com/y", g.secrets.get(webhook))
    }

    @Test
    fun `send a test posts a signed message and reports failures without the secret`() = runTest {
        val g = TestGraph(backgroundScope)
        g.http = { respond("", HttpStatusCode.NoContent) }
        g.reachMe.sendTest(ReachMeChannel.WEBHOOK, "https://h.example.com/x", secret)
        val req = g.requests.single()
        val body = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals(Webhook.signature(secret, req.headers[Webhook.TIMESTAMP_HEADER]!!.toLong(), body), req.headers[Webhook.SIGNATURE_HEADER])
        assertFalse(secret in body)
        // Testing saves nothing.
        assertNull(g.secrets.get(webhook))

        g.http = { respond("", HttpStatusCode.Unauthorized) }
        val refused = assertFailsWith<ServiceSetupException> { g.reachMe.sendTest(ReachMeChannel.GOTIFY, "https://push.example.com", token) }
        assertEquals("push.example.com refused the token (HTTP 401). Use an application token (Apps), not a client token, and copy it whole.", EnglishResources.render(refused.text))
        assertFalse(token in EnglishResources.render(refused.text))

        g.http = { respond("", HttpStatusCode.Found, io.ktor.http.headersOf("Location", "https://elsewhere.example.com/")) }
        val moved = assertFailsWith<ServiceSetupException> { g.reachMe.sendTest(ReachMeChannel.WEBHOOK, "https://h.example.com/x", null) }
        assertEquals(R.string.data_reachme_redirect, problem(moved))
        assertEquals(1, g.requests.count { it.url.host == "h.example.com" && it.headers[Webhook.SIGNATURE_HEADER] == null })
        assertTrue(g.requests.none { it.url.host == "elsewhere.example.com" })
    }

    // ---- Matrix ------------------------------------------------------------------------------------

    private val matrixToken = "syt_Ym90_AbCdEfGhIjKlMnOpQrSt_0a1b2c"
    private val roomId = "!AbCdEf:example.org"
    private val matrix = SecretStore.connectorSecret(Integrations.matrix.secret)

    /** A homeserver where the bot is @locus:example.org, #alerts:example.org is [roomId], and the bot has joined [joined]. */
    private fun TestGraph.homeserver(joined: List<String> = listOf(roomId), encrypted: Boolean = false, sendStatus: HttpStatusCode = HttpStatusCode.OK) {
        http = { req ->
            val path = req.url.encodedPath
            when {
                req.headers["Authorization"] != "Bearer $matrixToken" ->
                    respond("""{"errcode":"M_UNKNOWN_TOKEN","error":"Unrecognised access token."}""", HttpStatusCode.Unauthorized)
                path.endsWith("/account/whoami") -> respond("""{"user_id":"@locus:example.org","device_id":"ABC"}""")
                path.endsWith("/directory/room/%23alerts%3Aexample.org") -> respond("""{"room_id":"$roomId","servers":["example.org"]}""")
                "/directory/room/" in path -> respond("""{"errcode":"M_NOT_FOUND","error":"Room alias not found"}""", HttpStatusCode.NotFound)
                path.endsWith("/joined_rooms") -> respond("""{"joined_rooms":[${joined.joinToString(",") { "\"$it\"" }}]}""")
                path.endsWith("/state/m.room.encryption/") ->
                    if (encrypted) respond("""{"algorithm":"m.megolm.v1.aes-sha2"}""") else respond("""{"errcode":"M_NOT_FOUND","error":"Event not found."}""", HttpStatusCode.NotFound)
                "/send/m.room.message/" in path -> respond(if (sendStatus == HttpStatusCode.OK) """{"event_id":"${'$'}e"}""" else """{"errcode":"M_FORBIDDEN","error":"nope"}""", sendStatus)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    @Test
    fun `matrix is checked, saved with the alias resolved to its room ID, read back without the token, and removed`() = runTest {
        val g = TestGraph(backgroundScope)
        g.homeserver()
        g.reachMe.save(ReachMeChannel.MATRIX, "matrix.example.org", matrixToken, "#alerts:example.org")
        assertEquals("https://matrix.example.org $matrixToken $roomId", g.secrets.get(matrix))
        assertEquals("https://matrix.example.org", g.reachMe.savedUrl(ReachMeChannel.MATRIX))
        assertEquals(roomId, g.reachMe.savedRoom())
        assertTrue(g.reachMe.hasSecret(ReachMeChannel.MATRIX))
        // Saving checks but sends nothing into the room.
        assertTrue(g.requests.none { "/send/" in it.url.encodedPath })
        // notify_me and scheduled tasks read the same vault value.
        assertEquals(listOf("Matrix" to null), ReachMe.send(g.httpClient, g.secretResolver, null, "x").map { it.channel to it.error })

        // A blank token keeps the saved one for the same server, never for another.
        g.reachMe.save(ReachMeChannel.MATRIX, "https://matrix.example.org", "", roomId)
        assertEquals(R.string.data_reachme_token_again, problem(assertFailsWith { g.reachMe.save(ReachMeChannel.MATRIX, "https://evil.example.com", "", roomId) }))

        g.reachMe.remove(ReachMeChannel.MATRIX)
        assertNull(g.secrets.get(matrix))
        assertNull(g.reachMe.savedRoom())
    }

    @Test
    fun `an encrypted room, a room the bot isn't in and an unknown alias are refused and nothing is saved`() = runTest {
        val g = TestGraph(backgroundScope)
        g.homeserver(encrypted = true)
        assertEquals(R.string.data_reachme_matrix_encrypted, problem(assertFailsWith { g.reachMe.save(ReachMeChannel.MATRIX, "https://matrix.example.org", matrixToken, roomId) }))
        assertEquals(R.string.data_reachme_matrix_encrypted, problem(assertFailsWith { g.reachMe.sendTest(ReachMeChannel.MATRIX, "https://matrix.example.org", matrixToken, roomId) }))
        assertTrue(g.requests.none { "/send/" in it.url.encodedPath }, "nothing is posted into an encrypted room")

        g.homeserver(joined = emptyList())
        val out = assertFailsWith<ServiceSetupException> { g.reachMe.save(ReachMeChannel.MATRIX, "https://matrix.example.org", matrixToken, roomId) }
        assertEquals(R.string.data_reachme_matrix_not_joined, problem(out))
        assertEquals("@locus:example.org isn't in that room yet. Invite it, then accept the invite from the bot's account.", EnglishResources.render(out.text))

        g.homeserver()
        assertEquals(R.string.data_reachme_matrix_alias_unknown, problem(assertFailsWith { g.reachMe.save(ReachMeChannel.MATRIX, "https://matrix.example.org", matrixToken, "#nope:example.org") }))
        assertEquals(R.string.data_reachme_matrix_room_invalid, problem(assertFailsWith { g.reachMe.save(ReachMeChannel.MATRIX, "https://matrix.example.org", matrixToken, "alerts") }))
        assertEquals(R.string.data_reachme_matrix_token_missing, problem(assertFailsWith { g.reachMe.save(ReachMeChannel.MATRIX, "https://matrix.example.org", "", roomId) }))
        assertNull(g.secrets.get(matrix))
    }

    @Test
    fun `a matrix test posts into the room as the bot and failures never show the token`() = runTest {
        val g = TestGraph(backgroundScope)
        g.homeserver()
        assertEquals("@locus:example.org", g.reachMe.sendTest(ReachMeChannel.MATRIX, "https://matrix.example.org", matrixToken, "#alerts:example.org"))
        val sent = g.requests.single { "/send/m.room.message/" in it.url.encodedPath }
        assertTrue(sent.url.encodedPath.startsWith("/_matrix/client/v3/rooms/%21AbCdEf%3Aexample.org/send/m.room.message/"))
        assertTrue("Matrix is connected" in (sent.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        assertNull(g.secrets.get(matrix), "testing saves nothing")

        val refused = assertFailsWith<ServiceSetupException> { g.reachMe.sendTest(ReachMeChannel.MATRIX, "https://matrix.example.org", "syt_wrong_token_0123456789", roomId) }
        assertEquals(R.string.data_reachme_matrix_token_refused, problem(refused))
        assertFalse("syt_wrong_token_0123456789" in EnglishResources.render(refused.text))
        assertFalse("syt_wrong_token_0123456789" in refused.message.orEmpty())

        g.homeserver(sendStatus = HttpStatusCode.Forbidden)
        assertEquals(R.string.data_reachme_matrix_forbidden, problem(assertFailsWith { g.reachMe.sendTest(ReachMeChannel.MATRIX, "https://matrix.example.org", matrixToken, roomId) }))

        g.http = { respond("""{"errcode":"M_LIMIT_EXCEEDED","error":"Too many requests","retry_after_ms":90000}""", HttpStatusCode.TooManyRequests) }
        assertEquals(R.string.data_reachme_matrix_rate_limited, problem(assertFailsWith { g.reachMe.sendTest(ReachMeChannel.MATRIX, "https://matrix.example.org", matrixToken, roomId) }))

        g.http = { throw java.net.UnknownHostException("matrix.example.org: $matrixToken") }
        val offline = assertFailsWith<ServiceSetupException> { g.reachMe.sendTest(ReachMeChannel.MATRIX, "https://matrix.example.org", matrixToken, roomId) }
        assertEquals(R.string.data_service_unknown_host, problem(offline))
        assertFalse(matrixToken in EnglishResources.render(offline.text))
    }

    @Test
    fun `a new signing secret is 32 URL-safe characters and passes the check`() {
        val s = ReachMeSetup.newSecret()
        assertEquals(32, s.length)
        assertTrue(Webhook.isSecret(s))
        assertTrue(s.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
