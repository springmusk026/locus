package dev.locus.core.agent

import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.ReachMe
import dev.locus.core.agent.integrations.Telegram
import dev.locus.core.agent.integrations.integrationTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramTest {
    private val token = "123456789:AAH-dEf1234ghIkl_zyx57W2v1u123ew11"
    private val target = Telegram.Target(token, 987654321L)
    private val seen = mutableListOf<HttpRequestData>()
    private val waits = mutableListOf<Long>()
    private val pause: suspend (Long) -> Unit = { waits += it }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine { seen += it; handler(it) })

    private val ok = """{"ok":true,"result":{"message_id":1}}"""

    private fun HttpRequestData.json() = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
    private fun HttpRequestData.text() = json()["text"]!!.jsonPrimitive.content

    @Test
    fun `parse accepts token and numeric chat id only`() {
        assertEquals(target, Telegram.parse("$token 987654321"))
        assertEquals(-1001234567890L, Telegram.parse("$token -1001234567890")?.chatId)
        assertNull(Telegram.parse(token))
        assertNull(Telegram.parse("$token @channel"))
        assertNull(Telegram.parse("not-a-token 1"))
        assertNull(Telegram.parse("$token 1 extra"))
    }

    @Test
    fun `sendMessage posts html to the bot api with the chat id`() = runTest {
        val http = client { respond(ok) }
        Telegram.send(http, target, "Build <done>", "All **green** & `a<b>`", silent = true, click = "https://ci.test/run?id=1&x=2", pause = pause)
        val req = seen.single()
        assertEquals("https://api.telegram.org/bot$token/sendMessage", req.url.toString())
        val body = req.json()
        assertEquals(987654321L, body["chat_id"]!!.jsonPrimitive.long)
        assertEquals("HTML", body["parse_mode"]!!.jsonPrimitive.content)
        assertEquals("true", body["disable_notification"]!!.jsonPrimitive.content)
        assertEquals("true", body["link_preview_options"]!!.jsonObject["is_disabled"]!!.jsonPrimitive.content)
        assertEquals(
            "<b>Build &lt;done&gt;</b>\nAll <b>green</b> &amp; <code>a&lt;b&gt;</code>\n\n" +
                "<a href=\"https://ci.test/run?id=1&amp;x=2\">https://ci.test/run?id=1&amp;x=2</a>",
            body["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `markdown converts to telegram html and escapes the rest`() {
        assertEquals("<b>Title</b>\n• one &lt;tag&gt;\n• <i>two</i>", Telegram.toHtml("## Title\n- one <tag>\n* _two_"))
        assertEquals("<pre><code class=\"language-kotlin\">val a = b &amp;&amp; c &lt; d</code></pre>", Telegram.toHtml("```kotlin\nval a = b && c < d\n```"))
        assertEquals("see <a href=\"https://x.test/a_b\">the *docs*</a>", Telegram.toHtml("see [the *docs*](https://x.test/a_b)"))
        // snake_case and URLs with underscores or asterisks aren't emphasis.
        assertEquals("my_var_name at https://x.test/a_b_c*d*", Telegram.toHtml("my_var_name at https://x.test/a_b_c*d*"))
        assertEquals("<blockquote>quoted &amp; <s>gone</s></blockquote>", Telegram.toHtml("> quoted & ~~gone~~"))
        // An unclosed fence still closes.
        assertEquals("<pre>x</pre>", Telegram.toHtml("```\nx"))
    }

    @Test
    fun `long text is split at lines, under the limit, with code fences reopened`() {
        val lines = (1..400).map { "line $it " + "x".repeat(20) }
        val text = "intro\n```\n" + lines.joinToString("\n") + "\n```\noutro"
        val parts = Telegram.split(text, 3_500)
        assertTrue(parts.size > 1)
        parts.forEach { p ->
            assertTrue(p.length <= 3_500, "part of ${p.length}")
            assertEquals(0, Regex("(?m)^```").findAll(p).count() % 2, "unbalanced fence in part")
        }
        // Nothing lost: every line appears once, in order.
        val joined = parts.joinToString("\n")
        assertEquals(lines, joined.lines().filter { it.startsWith("line ") })
        // One huge line is hard-wrapped.
        assertTrue(Telegram.split("y".repeat(10_000), 3_500).all { it.length <= 3_500 })
    }

    @Test
    fun `long message goes out in several messages, paced, and capped`() = runTest {
        val http = client { respond(ok) }
        val message = (1..2_000).joinToString("\n") { "row $it with some words in it" }
        Telegram.send(http, target, "Report", message, pause = pause)
        assertEquals(Telegram.MAX_PARTS, seen.size)
        seen.forEach { assertTrue(it.text().length <= 4_096) }
        assertTrue(seen.first().text().startsWith("<b>Report</b>\n"))
        assertFalse(seen[1].text().contains("Report"))
        assertTrue(seen.last().text().endsWith("…(truncated)"))
        assertEquals(List(Telegram.MAX_PARTS - 1) { 1_000L }, waits)
    }

    @Test
    fun `429 waits retry_after then retries`() = runTest {
        var calls = 0
        val http = client {
            if (calls++ == 0) respond("""{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 3","parameters":{"retry_after":3}}""", HttpStatusCode.TooManyRequests)
            else respond(ok)
        }
        Telegram.send(http, target, null, "hi", pause = pause)
        assertEquals(2, seen.size)
        assertEquals(listOf(3_000L), waits)
    }

    @Test
    fun `429 with a long wait fails without waiting`() = runTest {
        val http = client { respond("""{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 600","parameters":{"retry_after":600}}""", HttpStatusCode.TooManyRequests) }
        val e = assertFailsWith<Telegram.Failure> { Telegram.send(http, target, null, "hi", pause = pause) }
        assertTrue("600 seconds" in e.message!!)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `unparseable html falls back to plain text`() = runTest {
        val http = client { req ->
            if (req.json()["parse_mode"] != null) respond("""{"ok":false,"error_code":400,"description":"Bad Request: can't parse entities: unexpected end tag"}""", HttpStatusCode.BadRequest)
            else respond(ok)
        }
        Telegram.send(http, target, "T", "**a *b** c*", pause = pause)
        assertEquals(2, seen.size)
        assertNull(seen.last().json()["parse_mode"])
        assertEquals("T\n**a *b** c*", seen.last().text())
    }

    @Test
    fun `find my chat checks the token and lists chats that messaged the bot`() = runTest {
        val http = client { req ->
            when (req.url.encodedPath.substringAfterLast('/')) {
                "getMe" -> respond("""{"ok":true,"result":{"id":123456789,"is_bot":true,"first_name":"Locus","username":"my_locus_bot"}}""")
                "getUpdates" -> respond(
                    """{"ok":true,"result":[
                    {"update_id":1,"message":{"message_id":1,"date":0,"chat":{"id":42,"type":"private","first_name":"Old"},"text":"x"}},
                    {"update_id":2,"message":{"message_id":2,"date":0,"chat":{"id":-1009999999999,"type":"supergroup","title":"Team"},"text":"y"}},
                    {"update_id":3,"message":{"message_id":3,"date":0,"chat":{"id":987654321,"type":"private","first_name":"Basanta","last_name":"K"},"text":"/start"}}
                    ]}""",
                )
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val found = Telegram.findChats(http, token, pause)
        assertEquals("my_locus_bot", found.botUsername)
        assertEquals(listOf(987654321L, -1009999999999L, 42L), found.chats.map { it.id })
        assertEquals("Basanta K", found.chats.first().name)
        assertEquals("Team", found.chats[1].name)
        assertEquals(listOf("getMe", "getUpdates"), seen.map { it.url.encodedPath.substringAfterLast('/') })
        // Updates are read, not confirmed: no offset is sent.
        assertNull(seen.last().json()["offset"])
    }

    @Test
    fun `errors never contain the token`() = runTest {
        // A bad token, a webhook conflict, a network error that quotes the URL, and an error body echoing the path.
        val bodies = listOf(
            """{"ok":false,"error_code":401,"description":"Unauthorized"}""" to HttpStatusCode.Unauthorized,
            """{"ok":false,"error_code":409,"description":"Conflict: can't use getUpdates method while webhook is active"}""" to HttpStatusCode.Conflict,
            """<html>Bad gateway for /bot$token/sendMessage</html>""" to HttpStatusCode.BadGateway,
        )
        for ((body, status) in bodies) {
            val e = assertFailsWith<Telegram.Failure> { Telegram.findChats(client { respond(body, status) }, token, pause) }
            assertFalse(token in e.message!!, e.message)
            assertFalse(token.substringAfter(':') in e.message!!, e.message)
        }
        val network = assertFailsWith<Telegram.Failure> {
            Telegram.send(client { throw IOException("timeout calling https://api.telegram.org/bot$token/sendMessage") }, target, null, "hi", pause = pause)
        }
        assertFalse(token in network.message!!)
        assertTrue("<token>" in network.message!!)
    }

    @Test
    fun `notify_me goes to every configured channel and never returns the token`() = runTest {
        val secrets = SecretResolver {
            when (it) {
                "integration-ntfy" -> "https://ntfy.sh/my_topic-1"
                "integration-telegram" -> "$token 987654321"
                else -> null
            }
        }
        val http = client { req -> if (req.url.host == "ntfy.sh") respond("""{"id":"x"}""") else respond(ok) }
        val r = ToolRegistry(integrationTools(http, secrets))["notify_me"]!!
            .execute(JsonObject(mapOf("title" to JsonPrimitive("Done"), "message" to JsonPrimitive("All good"), "priority" to JsonPrimitive(2))))
        assertEquals("Sent to ntfy and Telegram.", r.content)
        assertEquals(listOf("ntfy.sh", "api.telegram.org"), seen.map { it.url.host })
        assertEquals("true", seen.last().json()["disable_notification"]!!.jsonPrimitive.content)
    }

    @Test
    fun `one failing channel is reported without its secret while the other still delivers`() = runTest {
        val secrets = SecretResolver {
            when (it) {
                "integration-ntfy" -> "https://ntfy.sh/my_topic-1"
                "integration-telegram" -> "$token 987654321"
                else -> null
            }
        }
        val http = client { req ->
            if (req.url.host == "ntfy.sh") respond("""{"id":"x"}""")
            else respond("""{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}""", HttpStatusCode.Forbidden)
        }
        val r = ToolRegistry(integrationTools(http, secrets))["notify_me"]!!.execute(JsonObject(mapOf("message" to JsonPrimitive("hi"))))
        assertTrue(r.content.startsWith("Sent to ntfy. Telegram failed: Telegram can't reach that chat"), r.content)
        assertFalse(token in r.content)

        val onlyTelegram = SecretResolver { if (it == "integration-telegram") "$token 987654321" else null }
        val e = assertFailsWith<ToolException> { ToolRegistry(integrationTools(http, onlyTelegram))["notify_me"]!!.execute(JsonObject(mapOf("message" to JsonPrimitive("hi")))) }
        assertFalse(token in e.message!!)
    }

    @Test
    fun `notify_me without any channel asks for setup`() = runTest {
        val e = assertFailsWith<ToolException> {
            ToolRegistry(integrationTools(client { respond(ok) }, SecretResolver { null }))["notify_me"]!!.execute(JsonObject(mapOf("message" to JsonPrimitive("hi"))))
        }
        assertTrue("ntfy or Telegram" in e.message!!)
        assertTrue(seen.isEmpty())
        assertTrue(ReachMe.send(client { respond(ok) }, SecretResolver { null }, null, "x").isEmpty())
    }
}
