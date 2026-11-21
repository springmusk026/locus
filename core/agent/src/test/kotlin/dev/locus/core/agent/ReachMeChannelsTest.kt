package dev.locus.core.agent

import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.Gotify
import dev.locus.core.agent.integrations.ReachMe
import dev.locus.core.agent.integrations.Webhook
import dev.locus.core.agent.integrations.integrationTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Gotify and the webhook as reach-me channels (docs/design/reach-me-channels.md). */
class ReachMeChannelsTest {
    private val token = "AbCdEf1234567.8"
    private val secret = "whsec_test_secret_0123456789"
    private val seen = mutableListOf<HttpRequestData>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}") =
        HttpClient(MockEngine { seen += it; respond(body, status) })

    private fun HttpRequestData.text() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private fun notify(http: HttpClient, secrets: SecretResolver, vararg args: Pair<String, Any>) = kotlinx.coroutines.runBlocking {
        ToolRegistry(integrationTools(http, secrets))["notify_me"]!!.execute(
            JsonObject(args.associate { (k, v) -> k to if (v is Int) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
        )
    }

    // ---- Gotify ------------------------------------------------------------------------------------

    @Test
    fun `gotify values parse only with a usable address and a token`() {
        assertEquals(Gotify.Target("https://push.example.com", token), Gotify.parse("https://push.example.com $token"))
        assertEquals(Gotify.Target("https://home.example.com/gotify", token), Gotify.parse(Gotify.format("home.example.com/gotify/", token)))
        // http only on this phone or the local network, as for every self-hosted address.
        assertNotNull(Gotify.parse("http://192.168.1.20:8080 $token"))
        assertNotNull(Gotify.parse("http://gotify.local $token"))
        assertNull(Gotify.parse("http://push.example.com $token"))
        assertNull(Gotify.parse("ftp://push.example.com $token"))
        assertNull(Gotify.parse("https://push.example.com"))
        assertNull(Gotify.parse("https://push.example.com short"))
        assertNull(Gotify.parse("https://user:pw@push.example.com $token"))
    }

    @Test
    fun `gotify posts JSON to message with the token in X-Gotify-Key, never in the URL`() = runTest {
        Gotify.publish(client(), Gotify.Target("https://push.example.com", token), "Backup", "**Done**", priority = 4, click = "https://example.com/r")
        val req = seen.single()
        assertEquals("https://push.example.com/message", req.url.toString())
        assertEquals("POST", req.method.value)
        assertEquals(token, req.headers["X-Gotify-Key"])
        assertNull(req.headers["Authorization"])
        assertFalse(token in req.url.toString())
        assertEquals("application/json", req.body.contentType?.withoutParameters().toString())
        val body = Json.parseToJsonElement(req.text()).jsonObject
        assertEquals("Backup", body["title"]!!.jsonPrimitive.content)
        assertEquals("**Done**", body["message"]!!.jsonPrimitive.content)
        assertEquals(8, body["priority"]!!.jsonPrimitive.int)
        val extras = body["extras"]!!.jsonObject
        assertEquals("text/markdown", extras["client::display"]!!.jsonObject["contentType"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/r", extras["client::notification"]!!.jsonObject["click"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertFalse(token in req.text())
    }

    @Test
    fun `gotify priority maps 1 to 5 onto its 0 to 10, and none leaves the app's default`() {
        assertEquals(listOf(1, 3, 5, 8, 10), (1..5).map { Gotify.priority(it) })
        assertNull(Gotify.priority(null))
        assertFalse("priority" in Gotify.body(null, "x", null, null))
        assertFalse("title" in Gotify.body(" ", "x", null, null))
        assertFalse("client::notification" in Gotify.body(null, "x", null, "javascript:alert(1)"))
    }

    @Test
    fun `notify_me reaches Gotify, and a refused token is reported without it`() {
        val secrets = SecretResolver { if (it == "integration-gotify") "https://push.example.com $token" else null }
        assertEquals("Sent to Gotify.", notify(client(), secrets, "message" to "hi").content)
        val outcomes = kotlinx.coroutines.runBlocking {
            ReachMe.send(client(HttpStatusCode.Unauthorized, """{"error":"Unauthorized","errorCode":401,"errorDescription":"you need to provide a valid access token or user credentials to access this api"}"""), secrets, null, "hi")
        }
        val error = outcomes.single().error!!
        assertTrue("refused the application token" in error, error)
        assertFalse(token in error)
    }

    // ---- Webhook -----------------------------------------------------------------------------------

    @Test
    fun `webhook URLs follow the server-address rule but keep their path and query`() {
        assertEquals("https://hooks.example.com/api/webhook/locus-1", Webhook.check("hooks.example.com/api/webhook/locus-1").url)
        assertEquals("https://n8n.example.com/webhook/abc/?key=1&b=2", Webhook.check("https://n8n.example.com/webhook/abc/?key=1&b=2").url)
        assertEquals("http://192.168.1.10:8123/api/webhook/x", Webhook.check("http://192.168.1.10:8123/api/webhook/x").url)
        assertNotNull(Webhook.check("http://192.168.1.10:8123/api/webhook/x").warning)
        assertNull(Webhook.check("http://hooks.example.com/x").url)
        assertNull(Webhook.check("https://hooks.example.com/x#frag").url)
        assertNull(Webhook.check("https://hooks.example.com/x?a=1#frag").url)
        assertNull(Webhook.check("https://u:p@hooks.example.com/x").url)
        assertNull(Webhook.check("javascript:alert(1)").url)
        assertNull(Webhook.check("https://hooks.example.com/x?a b").url)
    }

    @Test
    fun `webhook values parse with an optional secret of at least 16 characters`() {
        assertEquals(Webhook.Target("https://h.example.com/x", null), Webhook.parse("https://h.example.com/x"))
        assertEquals(Webhook.Target("https://h.example.com/x", secret), Webhook.parse(Webhook.format("h.example.com/x", secret)))
        assertNull(Webhook.parse("https://h.example.com/x short"))
        assertNull(Webhook.parse("http://h.example.com/x"))
        assertEquals("https://h.example.com/x", Webhook.format("https://h.example.com/x", "  "))
    }

    @Test
    fun `GitHub's documented HMAC-SHA256 vector`() {
        // docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries
        assertEquals("757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17", Webhook.hmacSha256Hex("It's a Secret to Everybody", "Hello, World!"))
    }

    @Test
    fun `webhook body is the documented JSON, signed over timestamp dot body`() {
        val body = Webhook.body("Daily digest", "3 new *items*", null, null, 1_795_000_000_123)
        assertEquals("""{"version":1,"title":"Daily digest","message":"3 new *items*","priority":3,"click":null,"sentAt":"2026-11-18T11:06:40Z"}""", body)
        // HMAC-SHA256(secret, "1795000000." + body), computed independently.
        assertEquals("sha256=30ebc855c89d6786961a863b049d9fe38aa25a48b4d464d8c6b28c34797fc518", Webhook.signature(secret, 1_795_000_000, body))
        val full = Json.parseToJsonElement(Webhook.body(null, "x", 9, "https://e.example/a", 0)).jsonObject
        assertEquals(5, full["priority"]!!.jsonPrimitive.int)
        assertEquals("https://e.example/a", full["click"]!!.jsonPrimitive.content)
        assertEquals("null", full["title"].toString())
    }

    @Test
    fun `webhook posts with timestamp and signature headers and never the secret`() = runTest {
        Webhook.send(client(), Webhook.Target("https://h.example.com/x?k=1", secret), "Daily digest", "3 new *items*", now = { 1_795_000_000_123 })
        val req = seen.single()
        assertEquals("https://h.example.com/x?k=1", req.url.toString())
        assertEquals("POST", req.method.value)
        assertEquals("application/json", req.body.contentType?.withoutParameters().toString())
        assertEquals("1795000000", req.headers[Webhook.TIMESTAMP_HEADER])
        val body = req.text()
        assertEquals(Webhook.signature(secret, 1_795_000_000, body), req.headers[Webhook.SIGNATURE_HEADER])
        assertEquals("sha256=30ebc855c89d6786961a863b049d9fe38aa25a48b4d464d8c6b28c34797fc518", req.headers[Webhook.SIGNATURE_HEADER])
        assertFalse(secret in body)
        assertTrue(req.headers.entries().none { (_, v) -> v.any { secret in it } })
        assertFalse(secret in req.url.toString())
    }

    @Test
    fun `an unsigned webhook has no signature header`() = runTest {
        Webhook.send(client(), Webhook.Target("https://h.example.com/x", null), null, "hi", now = { 0 })
        assertNull(seen.single().headers[Webhook.SIGNATURE_HEADER])
        assertEquals("0", seen.single().headers[Webhook.TIMESTAMP_HEADER])
    }

    @Test
    fun `notify_me goes to every configured channel, and a failing webhook doesn't stop the others`() {
        val secrets = SecretResolver {
            when (it) {
                "integration-ntfy" -> "https://ntfy.sh/my_topic-1"
                "integration-gotify" -> "https://push.example.com $token"
                "integration-webhook" -> "https://h.example.com/x $secret"
                else -> null
            }
        }
        val http = HttpClient(MockEngine { r -> seen += r; if (r.url.host == "h.example.com") respond("", HttpStatusCode.InternalServerError) else respond("{}") })
        val result = notify(http, secrets, "message" to "hi", "title" to "T").content
        assertEquals("Sent to ntfy and Gotify. Webhook failed: The webhook answered HTTP 500.", result)
        assertEquals(listOf("ntfy.sh", "push.example.com", "h.example.com"), seen.map { it.url.host })
        assertFalse(secret in result)
        assertFalse(token in result)
    }
}
