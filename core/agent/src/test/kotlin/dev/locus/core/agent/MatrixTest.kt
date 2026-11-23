package dev.locus.core.agent

import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.Matrix
import dev.locus.core.agent.integrations.ReachMe
import dev.locus.core.agent.integrations.integrationTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Matrix bot as a reach-me channel (docs/design/reach-me-channels.md#matrix). */
class MatrixTest {
    private val token = "syt_Ym90_AbCdEfGhIjKlMnOpQrSt_0a1b2c"
    private val room = "!AbCdEf:example.org"
    private val target = Matrix.Target("https://matrix.example.org", token, room)
    private val seen = mutableListOf<HttpRequestData>()
    private val waits = mutableListOf<Long>()
    private val pause: suspend (Long) -> Unit = { waits += it }
    private var txn = 0
    private val txnId: () -> String = { "t${++txn}" }

    private val notEncrypted = """{"errcode":"M_NOT_FOUND","error":"Event not found."}"""

    /** A homeserver: the encryption state lookup answers [state] (404 = none), sends go to [send]. */
    private fun server(
        state: Pair<HttpStatusCode, String> = HttpStatusCode.NotFound to notEncrypted,
        send: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("""{"event_id":"${'$'}e1"}""") },
    ) = HttpClient(
        MockEngine { req ->
            seen += req
            if (req.method == HttpMethod.Get && "/state/m.room.encryption/" in req.url.encodedPath) respond(state.second, state.first) else send(req)
        },
    )

    private fun HttpRequestData.json() = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
    private val sends get() = seen.filter { it.method == HttpMethod.Put }

    @Test
    fun `values parse only with a usable homeserver, a token and a room ID`() {
        assertEquals(target, Matrix.parse("https://matrix.example.org $token $room"))
        assertEquals(target, Matrix.parse(Matrix.format("matrix.example.org/", token, room)))
        // Room version 12 room IDs have no server part.
        assertNotNull(Matrix.parse("https://matrix.example.org $token !31hneApxJ_1o-63DmFrpeqnkFfWppnzWso1JvH3ogLM"))
        assertNotNull(Matrix.parse("http://192.168.1.20:8008 $token $room"))
        assertNull(Matrix.parse("http://matrix.example.org $token $room"))
        assertNull(Matrix.parse("https://matrix.example.org $token #alias:example.org"))
        assertNull(Matrix.parse("https://matrix.example.org $token"))
        assertNull(Matrix.parse("https://matrix.example.org short $room"))
        assertTrue(Matrix.isAlias("#alerts:example.org"))
        assertFalse(Matrix.isAlias("#alerts"))
        assertFalse(Matrix.isRoomId("!"))
    }

    @Test
    fun `send checks encryption, then PUTs m_room_message with a bearer token and a transaction ID`() = runTest {
        Matrix.send(server(), target, "Backup", "All **green** & `a<b>`", click = "https://ci.example.com/r?a=1&b=2", pause = pause, txnId = txnId)
        val check = seen.first()
        assertEquals(HttpMethod.Get, check.method)
        assertEquals("/_matrix/client/v3/rooms/%21AbCdEf%3Aexample.org/state/m.room.encryption/", check.url.encodedPath)
        val put = sends.single()
        assertEquals("/_matrix/client/v3/rooms/%21AbCdEf%3Aexample.org/send/m.room.message/t1", put.url.encodedPath)
        assertEquals("matrix.example.org", put.url.host)
        assertEquals("Bearer $token", put.headers["Authorization"])
        assertEquals("application/json", put.body.contentType?.withoutParameters().toString())
        seen.forEach { assertFalse(token in it.url.toString()) }

        val body = put.json()
        assertEquals("m.text", body.str("msgtype"))
        assertEquals("Backup\n\nAll **green** & `a<b>`\n\nhttps://ci.example.com/r?a=1&b=2", body.str("body"))
        assertEquals("org.matrix.custom.html", body.str("format"))
        assertEquals(
            "<b>Backup</b><br>All <b>green</b> &amp; <code>a&lt;b&gt;</code><br><br><a href=\"https://ci.example.com/r?a=1&amp;b=2\">https://ci.example.com/r?a=1&amp;b=2</a>",
            body.str("formatted_body"),
        )
        assertFalse(token in put.json().toString())
    }

    @Test
    fun `html keeps code blocks as they are and breaks other lines with br`() {
        assertEquals("a<br><i>b</i><br><pre>x\ny</pre>c", Matrix.html("a\n*b*\n```\nx\ny\n```\nc"))
        assertEquals("<blockquote>q1\nq2</blockquote>after".replace("\n", "<br>"), Matrix.html("> q1\n> q2\nafter"))
        assertEquals("• one<br>• two", Matrix.html("- one\n- two"))
    }

    @Test
    fun `a low priority goes as m_notice, which the default push rules don't notify for`() = runTest {
        Matrix.send(server(), target, null, "quiet", silent = true, pause = pause, txnId = txnId)
        assertEquals("m.notice", sends.single().json().str("msgtype"))
    }

    @Test
    fun `long text is split into at most five messages, each with its own transaction`() = runTest {
        val text = (1..4_000).joinToString("\n") { "line $it with some words" }
        Matrix.send(server(), target, "Report", text, pause = pause, txnId = txnId)
        assertEquals(5, sends.size)
        assertEquals((1..5).map { "t$it" }, sends.map { it.url.encodedPath.substringAfterLast('/') })
        assertTrue(sends.first().json().str("body")!!.startsWith("Report\n\n"))
        assertTrue(sends.last().json().str("body")!!.endsWith("…(truncated)"))
        sends.forEach { assertTrue(it.json().toString().toByteArray().size < 65_536) }
    }

    @Test
    fun `a formatted event the server refuses is sent again as plain text`() = runTest {
        val http = server(send = { req ->
            if ("formatted_body" in req.json()) respond("""{"errcode":"M_TOO_LARGE","error":"Event too large"}""", HttpStatusCode.PayloadTooLarge)
            else respond("""{"event_id":"${'$'}e2"}""")
        })
        Matrix.send(http, target, null, "**hi**", pause = pause, txnId = txnId)
        assertEquals(2, sends.size)
        val plain = sends.last().json()
        assertEquals("**hi**", plain.str("body"))
        assertNull(plain["format"])
        assertNull(plain["formatted_body"])
        // A new transaction: the refused one was a different event.
        assertEquals(listOf("t1", "t2"), sends.map { it.url.encodedPath.substringAfterLast('/') })
    }

    @Test
    fun `a worst-case chunk fits the event budget, and the plain version has no html`() {
        // Every character escaped (4 bytes as &lt;) and every one a line break (<br>): still under the budget.
        val text = "<".repeat(Matrix.CHUNK)
        val rich = Matrix.content("T".repeat(250), text, "https://e.example/" + "a".repeat(250), notice = false, html = true)
        assertTrue(rich.toString().toByteArray().size <= Matrix.MAX_CONTENT_BYTES)
        val lines = Matrix.content(null, "中\n".repeat(Matrix.CHUNK / 2), null, notice = false, html = true)
        assertTrue(lines.toString().toByteArray().size <= Matrix.MAX_CONTENT_BYTES)
        val plain = Matrix.content(null, text, null, notice = false, html = false)
        assertNull(plain["formatted_body"])
        assertEquals(text, plain.str("body"))
    }

    @Test
    fun `M_LIMIT_EXCEEDED waits and retries the same transaction`() = runTest {
        var calls = 0
        val http = server(send = {
            calls++
            when (calls) {
                1 -> respond("""{"errcode":"M_LIMIT_EXCEEDED","error":"Too many requests","retry_after_ms":1500}""", HttpStatusCode.TooManyRequests)
                2 -> respond("""{"errcode":"M_LIMIT_EXCEEDED","error":"Too many requests"}""", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "2"))
                else -> respond("""{"event_id":"${'$'}e3"}""")
            }
        })
        Matrix.send(http, target, null, "hi", pause = pause, txnId = txnId)
        assertEquals(listOf(1_500L, 2_000L), waits)
        assertEquals(listOf("t1", "t1", "t1"), sends.map { it.url.encodedPath.substringAfterLast('/') })
    }

    @Test
    fun `a long rate limit is reported instead of waited out`() = runTest {
        val http = server(send = { respond("""{"errcode":"M_LIMIT_EXCEEDED","error":"Too many requests","retry_after_ms":120000}""", HttpStatusCode.TooManyRequests) })
        val e = assertFailsWith<Matrix.Failure> { Matrix.send(http, target, null, "hi", pause = pause, txnId = txnId) }
        assertEquals(Matrix.Problem.RATE_LIMITED, e.problem)
        assertEquals(120_000L, e.retryAfterMs)
        assertTrue("120 seconds" in e.message!!, e.message)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `an encrypted room is refused before anything is sent`() = runTest {
        val http = server(state = HttpStatusCode.OK to """{"algorithm":"m.megolm.v1.aes-sha2"}""")
        val e = assertFailsWith<Matrix.Failure> { Matrix.send(http, target, null, "secret plans", pause = pause, txnId = txnId) }
        assertEquals(Matrix.Problem.ENCRYPTED, e.problem)
        assertTrue("end-to-end encrypted" in e.message!!)
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `not being in the room, a bad token and posting without permission are told apart`() = runTest {
        val out = assertFailsWith<Matrix.Failure> {
            Matrix.send(server(state = HttpStatusCode.Forbidden to """{"errcode":"M_FORBIDDEN","error":"User not in room"}"""), target, null, "hi", pause = pause)
        }
        assertEquals(Matrix.Problem.NOT_JOINED, out.problem)
        val bad = assertFailsWith<Matrix.Failure> {
            Matrix.send(server(state = HttpStatusCode.Unauthorized to """{"errcode":"M_UNKNOWN_TOKEN","error":"Unknown token $token"}"""), target, null, "hi", pause = pause)
        }
        assertEquals(Matrix.Problem.TOKEN, bad.problem)
        assertFalse(token in bad.message!!)
        val muted = assertFailsWith<Matrix.Failure> {
            Matrix.send(server(send = { respond("""{"errcode":"M_FORBIDDEN","error":"You don't have permission to post that to the room. user_level (0) < send_level (50)"}""", HttpStatusCode.Forbidden) }), target, null, "hi", pause = pause)
        }
        assertEquals(Matrix.Problem.FORBIDDEN, muted.problem)
    }

    @Test
    fun `whoami, aliases and joined rooms`() = runTest {
        val http = HttpClient(
            MockEngine { req ->
                seen += req
                when {
                    req.url.encodedPath.endsWith("/account/whoami") -> respond("""{"user_id":"@locus:example.org","device_id":"ABC"}""")
                    req.url.encodedPath.endsWith("/directory/room/%23alerts%3Aexample.org") -> respond("""{"room_id":"$room","servers":["example.org"]}""")
                    req.url.encodedPath.contains("/directory/room/") -> respond("""{"errcode":"M_NOT_FOUND","error":"Room alias not found"}""", HttpStatusCode.NotFound)
                    req.url.encodedPath.endsWith("/joined_rooms") -> respond("""{"joined_rooms":["$room"]}""")
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        assertEquals("@locus:example.org", Matrix.whoami(http, target.url, token, pause))
        assertEquals(room, Matrix.resolveAlias(http, target.url, token, "#alerts:example.org", pause))
        assertEquals(Matrix.Problem.ALIAS_UNKNOWN, assertFailsWith<Matrix.Failure> { Matrix.resolveAlias(http, target.url, token, "#nope:example.org", pause) }.problem)
        assertEquals(listOf(room), Matrix.joinedRooms(http, target.url, token, pause))
        seen.forEach { assertEquals("Bearer $token", it.headers["Authorization"]) }
        // A web server that isn't a homeserver.
        val web = HttpClient(MockEngine { respond("<html>Not found</html>", HttpStatusCode.NotFound) })
        assertEquals(Matrix.Problem.NOT_MATRIX, assertFailsWith<Matrix.Failure> { Matrix.whoami(web, target.url, token, pause) }.problem)
    }

    @Test
    fun `notify_me reaches Matrix with the others, and failures never show the token or room`() {
        val secrets = SecretResolver {
            when (it) {
                "integration-ntfy" -> "https://ntfy.sh/my_topic-1"
                "integration-matrix" -> "https://matrix.example.org $token $room"
                else -> null
            }
        }
        val ok = kotlinx.coroutines.runBlocking {
            ToolRegistry(integrationTools(server(), secrets))["notify_me"]!!.execute(JsonObject(mapOf("message" to JsonPrimitive("hi"))))
        }
        assertEquals("Sent to ntfy and Matrix.", ok.content)

        val echo = server(send = { respond("""{"errcode":"M_UNKNOWN","error":"bad request from $token in $room"}""", HttpStatusCode.BadRequest) })
        val failed = kotlinx.coroutines.runBlocking { ReachMe.send(echo, secrets, null, "hi", pause = pause) }.single { it.channel == "Matrix" }.error!!
        assertFalse(token in failed, failed)
        assertFalse(room in failed, failed)

        val down = HttpClient(MockEngine { throw IOException("connect to https://matrix.example.org failed with Bearer $token") })
        val network = kotlinx.coroutines.runBlocking { ReachMe.send(down, secrets, null, "hi", pause = pause) }.single { it.channel == "Matrix" }.error!!
        assertFalse(token in network, network)
    }
}
