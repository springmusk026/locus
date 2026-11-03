package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every shape a failed Responses stream comes in (see [ResponsesError] for the sources), what the user
 * reads for each, and what is kept for the details sheet: never an opaque "the response failed".
 */
class ResponsesFailureTest {
    private val plan = ProviderConfig("p", "ChatGPT", ApiFormat.CHATGPT, "https://chatgpt.com/backend-api/codex", "gpt-6.1-sol", requiresKey = false)
    private val platform = ProviderConfig("o", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6.1-sol")
    private val request = ChatRequest("You are Locus.", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())

    private fun engine(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg headers: Pair<String, String>) = HttpClient(MockEngine {
        val all = (listOf(HttpHeaders.ContentType to "text/event-stream") + headers).groupBy({ it.first }, { it.second })
        respond(ByteReadChannel(body), status, headersOf(*all.map { (k, v) -> k to v }.toTypedArray()))
    })

    private fun fails(config: ProviderConfig, body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg headers: Pair<String, String>): LlmException {
        val model = OpenAiResponsesModel(engine(body, status, *headers), config, apiKey = "sk-test-key-123456", bearer = if (config.format == ApiFormat.CHATGPT) BearerSource { "at-1" } else null)
        return assertFailsWith<LlmException> { runTest { model.stream(request).toList() } }
    }

    private val created = """{"type":"response.created","response":{"id":"resp_abc123","status":"in_progress","instructions":"You are Locus.","output":[]}}"""

    @Test
    fun `a response failed with no code and no message says so, with the request id, and is retried`() {
        val failed = """{"type":"response.failed","sequence_number":7,"response":{"id":"resp_abc123","status":"failed","error":null,"instructions":"You are Locus.","output":[{"type":"message","content":[{"type":"output_text","text":"secret plan of the user"}]}]}}"""
        val e = fails(plan, sse(null to created, null to failed), HttpStatusCode.OK, "x-request-id" to "req_42")
        assertEquals("ChatGPT ended the response with an error and gave no reason (request id req_42).", e.message)
        assertTrue(e.retryable)
        assertEquals(LlmFailure.TRANSIENT, e.kind)
        assertEquals("req_42", e.requestId)
        assertEquals("resp_abc123", e.responseId)
        assertNull(e.errorCode)
        val raw = e.rawBody!!
        assertTrue("response.failed" in raw && "resp_abc123" in raw && "\"sequence_number\":7" in raw, raw)
        // Only the failure: never what the model wrote or the system prompt.
        assertFalse("secret plan" in raw, raw)
        assertFalse("You are Locus" in raw, raw)
    }

    @Test
    fun `a response failed with an empty error object is just as clear`() {
        val e = fails(plan, sse(null to """{"type":"response.failed","response":{"id":"resp_1","error":{}}}"""))
        assertEquals("ChatGPT ended the response with an error and gave no reason.", e.message)
    }

    @Test
    fun `the platform shape of an error event is read from the top level`() {
        val e = fails(platform, sse(null to """{"type":"error","code":"server_error","message":"The server had an error while processing your request.","param":null,"sequence_number":3}"""))
        assertEquals("OpenAI: The server had an error while processing your request.", e.message)
        assertTrue(e.retryable)
        assertEquals("server_error", e.errorCode)
    }

    @Test
    fun `the ChatGPT backend's nested error event with only a type is a usage limit`() {
        val event = """{"type":"error","status":429,"error":{"type":"usage_limit_reached","plan_type":"plus","resets_at":1893456000},"headers":{"x-codex-primary-used-percent":"100.0","authorization":"Bearer abcdefghijklmnop"}}"""
        val e = fails(plan, sse(null to event))
        assertEquals(LlmFailure.QUOTA, e.kind)
        assertTrue(e.message!!.startsWith(ChatGptErrors.LIMIT_MESSAGE), e.message)
        assertTrue("It resets at" in e.message!!, e.message)
        assertEquals(429, e.httpStatus)
        assertEquals("usage_limit_reached", e.errorCode)
        assertFalse("abcdefghijklmnop" in e.rawBody!!, e.rawBody)
        assertTrue("x-codex-primary-used-percent" in e.rawBody!!)
    }

    @Test
    fun `a response failed whose error has a type and no code or message is classified by the type`() {
        val e = fails(plan, sse(null to """{"type":"response.failed","response":{"id":"resp_9","status":"failed","error":{"type":"usage_limit_reached","plan_type":"plus"}}}"""))
        assertEquals(LlmFailure.QUOTA, e.kind)
        assertEquals("usage_limit_reached", e.errorCode)
    }

    @Test
    fun `context overflow in a response failed is recognised on both routes`() {
        val failed = """{"type":"response.failed","response":{"id":"resp_5","status":"failed","error":{"code":"context_length_exceeded","message":"Your input exceeds the context window of this model. Please adjust your input and try again."}}}"""
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, fails(plan, sse(null to failed)).kind)
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, fails(platform, sse(null to failed)).kind)
    }

    @Test
    fun `a code with no message names the code rather than saying nothing`() {
        val e = fails(plan, sse(null to """{"type":"response.failed","response":{"error":{"code":"invalid_prompt"}}}"""))
        assertEquals("ChatGPT: the response failed with “invalid_prompt”.", e.message)
        assertFalse(e.retryable)
        assertEquals("invalid_prompt", e.errorCode)
    }

    @Test
    fun `request ids come from x-request-id, then x-oai-request-id, then cf-ray`() {
        val failed = sse(null to """{"type":"response.failed","response":{"error":{"code":"server_error","message":"boom"}}}""")
        assertEquals("req_a", fails(plan, failed, HttpStatusCode.OK, "x-request-id" to "req_a", "cf-ray" to "ray").requestId)
        assertEquals("oai_b", fails(plan, failed, HttpStatusCode.OK, "x-oai-request-id" to "oai_b", "cf-ray" to "ray").requestId)
        assertEquals("ray_c", fails(plan, failed, HttpStatusCode.OK, "cf-ray" to "ray_c").requestId)
    }

    @Test
    fun `a refused request before the stream keeps its request id and body`() {
        val body = """{"error":{"type":"usage_limit_reached","message":"The usage limit has been reached","plan_type":"plus","resets_at":1893456000}}"""
        val e = fails(plan, body, HttpStatusCode.TooManyRequests, "x-request-id" to "req_http")
        assertEquals(LlmFailure.QUOTA, e.kind)
        assertEquals("req_http", e.requestId)
        assertEquals(body, e.rawBody)
        val p = fails(platform, """{"error":{"message":"Invalid input","type":"invalid_request_error","code":null}}""", HttpStatusCode.BadRequest, "x-request-id" to "req_p")
        assertEquals("req_p", p.requestId)
    }

    @Test
    fun `an incomplete response with nothing in it fails with its reason`() {
        val incomplete = """{"type":"response.incomplete","response":{"id":"resp_i","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"reasoning","encrypted_content":"ENC","summary":[]}]}}"""
        val e = fails(plan, sse(null to incomplete))
        assertEquals("ChatGPT stopped the response early: it reached its output limit before writing anything (its reasoning used it up).", e.message)
        assertEquals("incomplete:max_output_tokens", e.errorCode)
        assertEquals("resp_i", e.responseId)
        assertFalse("ENC" in e.rawBody!!)
    }

    @Test
    fun `an incomplete response with text is kept as a turn cut at the limit`() = runTest {
        val incomplete = """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Partial"}]}]}}"""
        val model = OpenAiResponsesModel(engine(sse(null to incomplete)), plan, apiKey = null, bearer = BearerSource { "at-1" })
        val turn = model.stream(request).toList().filterIsInstance<ChatEvent.Completed>().single().turn
        assertEquals("Partial", turn.text)
        assertEquals(StopReason.MAX_TOKENS, turn.stopReason)
    }

    @Test
    fun `scrubbing keeps the failure and drops tokens, keys, output and long blobs`() {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEyMyJ9.c2lnbmF0dXJlLXNpZ25hdHVyZQ"
        val event = LlmJson.parseToJsonElement(
            """{"type":"response.failed","response":{"id":"resp_s","status":"failed","model":"gpt-6.1-sol","tools":[{"name":"x"}],"input":[{"text":"user words"}],
               "error":{"code":"bad","message":"token $jwt and Bearer abcdefghijklmnopq and sk-proj-ABCDEFGHIJKLMNOP and ${"A".repeat(300)}"}}}""",
        ).jsonObject
        val raw = scrubbedFailureEvent(event)
        assertFalse(jwt in raw, raw)
        assertFalse("abcdefghijklmnopq" in raw, raw)
        assertFalse("sk-proj-ABCDEFGHIJKLMNOP" in raw, raw)
        assertFalse("A".repeat(300) in raw, raw)
        assertFalse("user words" in raw, raw)
        assertFalse("\"tools\"" in raw, raw)
        assertTrue("resp_s" in raw && "gpt-6.1-sol" in raw && "\"code\":\"bad\"" in raw, raw)
        val long = LlmJson.parseToJsonElement("""{"type":"error","message":"${"word ".repeat(2_000)}"}""") as JsonObject
        assertTrue(scrubbedFailureEvent(long).length <= FailureText.MAX_CHARS + 40)
    }

    @Test
    fun `a usage limit says when it resets`() {
        val noon = java.time.LocalDate.of(2030, 1, 1).atTime(12, 30).toEpochSecond(ZoneOffset.UTC)
        val morning = (noon - 3_600) * 1000
        assertEquals(" It resets at 12:30.", ChatGptErrors.resetText(noon, ZoneOffset.UTC, morning))
        assertEquals(" It resets at 1 Jan, 12:30.", ChatGptErrors.resetText(noon, ZoneOffset.UTC, morning - 86_400_000))
        assertEquals("", ChatGptErrors.resetText(null))
    }
}
