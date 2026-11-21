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
        assertTrue(http.text is EngineText.Plain)
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

    @Test
    fun `a new signing secret is 32 URL-safe characters and passes the check`() {
        val s = ReachMeSetup.newSecret()
        assertEquals(32, s.length)
        assertTrue(Webhook.isSecret(s))
        assertTrue(s.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
