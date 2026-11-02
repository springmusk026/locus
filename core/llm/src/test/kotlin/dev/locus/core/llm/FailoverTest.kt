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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Error classification per provider, `retry-after` parsing, and the failover rules built on them. */
class FailoverTest {
    @Test
    fun `anthropic errors`() {
        assertEquals(LlmFailure.OVERLOADED, httpError("Anthropic", 529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""").kind)
        assertEquals(LlmFailure.RATE_LIMIT, httpError("Anthropic", 429, """{"type":"error","error":{"type":"rate_limit_error","message":"Slow"}}""").kind)
        val cap = httpError("Anthropic", 429, """{"type":"error","error":{"type":"rate_limit_error","message":"cap","details":{"error_code":"enforced_spend_limit_reached"}}}""")
        assertEquals(LlmFailure.QUOTA, cap.kind)
        assertEquals(LlmFailure.QUOTA, httpError("Anthropic", 402, """{"type":"error","error":{"type":"billing_error","message":"Billing"}}""").kind)
        val ownLimit = httpError("Anthropic", 400, """{"type":"error","error":{"type":"invalid_request_error","message":"You have reached your specified API usage limits. You will regain access on 2026-12-01."}}""")
        assertEquals(LlmFailure.QUOTA, ownLimit.kind)
        assertEquals(LlmFailure.AUTH, httpError("Anthropic", 401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""").kind)
        assertEquals(LlmFailure.OTHER, httpError("Anthropic", 400, """{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}""").kind)
        assertEquals(LlmFailure.TRANSIENT, httpError("Anthropic", 500, """{"type":"error","error":{"type":"api_error","message":"x"}}""").kind)
    }

    @Test
    fun `openai errors`() {
        val quota = httpError("OpenAI", 429, """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""")
        assertEquals(LlmFailure.QUOTA, quota.kind)
        assertFalse(quota.retryable)
        val rate = httpError("OpenAI", 429, """{"error":{"message":"Rate limit reached","type":"requests","code":"rate_limit_exceeded"}}""", retryAfterMs = 1_500)
        assertEquals(LlmFailure.RATE_LIMIT, rate.kind)
        assertTrue(rate.retryable)
        assertEquals(1_500, rate.retryAfterMs)
        assertEquals(LlmFailure.OVERLOADED, httpError("OpenAI", 503, """{"error":{"type":"service_unavailable_error","code":"server_is_overloaded"}}""").kind)
        assertEquals(LlmFailure.AUTH, httpError("OpenAI", 401, """{"error":{"code":"invalid_api_key"}}""").kind)
    }

    private val geminiPerMinute = """{"error":{"code":429,"message":"Resource exhausted. Please retry in 37s.","status":"RESOURCE_EXHAUSTED","details":[""" +
        """{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests","quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},""" +
        """{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}"""

    @Test
    fun `gemini errors, also wrapped in an array`() {
        val perMinute = httpError("Google Gemini", 429, geminiPerMinute)
        assertEquals(LlmFailure.RATE_LIMIT, perMinute.kind)
        assertEquals(37_000, perMinute.retryAfterMs)
        assertTrue(perMinute.retryable)
        val perDay = httpError("Google Gemini", 429, geminiPerMinute.replace("PerMinute", "PerDay"))
        assertEquals(LlmFailure.QUOTA, perDay.kind)
        assertFalse(perDay.retryable)
        // The OpenAI-compatible endpoint has been seen to wrap the body in an array.
        val wrapped = httpError("Google Gemini", 429, "[$geminiPerMinute]")
        assertEquals(37_000, wrapped.retryAfterMs)
        assertEquals(LlmFailure.OVERLOADED, httpError("Google Gemini", 503, """{"error":{"code":503,"message":"The model is overloaded.","status":"UNAVAILABLE"}}""").kind)
        assertEquals(LlmFailure.AUTH, httpError("Google Gemini", 403, """{"error":{"code":403,"status":"PERMISSION_DENIED"}}""").kind)
        // A header wins over the body's delay.
        assertEquals(5_000, httpError("Google Gemini", 429, geminiPerMinute, retryAfterMs = 5_000).retryAfterMs)
    }

    @Test
    fun `openrouter, deepseek and groq errors`() {
        assertEquals(LlmFailure.QUOTA, httpError("OpenRouter", 402, """{"error":{"code":402,"message":"Insufficient credits"}}""").kind)
        assertEquals(LlmFailure.QUOTA, httpError("DeepSeek", 402, """{"error":{"message":"Insufficient Balance"}}""").kind)
        assertEquals(LlmFailure.OVERLOADED, httpError("DeepSeek", 503, "Server Overloaded").kind)
        val flex = httpError("Groq", 498, """{"error":{"message":"Flex tier capacity exceeded"}}""")
        assertEquals(LlmFailure.OVERLOADED, flex.kind)
        assertTrue(flex.retryable)
        assertEquals(LlmFailure.TRANSIENT, httpError("OpenRouter", 502, """{"error":{"code":502,"message":"down"}}""").kind)
    }

    @Test
    fun `retry-after headers, in milliseconds, seconds or a date`() {
        assertEquals(250, retryAfterMs("250", "9"))
        assertEquals(9_000, retryAfterMs(null, "9"))
        assertEquals(1_500, retryAfterMs(null, "1.5"))
        val now = java.time.ZonedDateTime.parse("Sun, 01 Nov 2026 10:00:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        assertEquals(120_000, retryAfterMs(null, "Sun, 01 Nov 2026 10:02:00 GMT") { now })
        assertEquals(0, retryAfterMs(null, "Sun, 01 Nov 2026 09:00:00 GMT") { now })
        assertNull(retryAfterMs(null, "soon"))
        assertNull(retryAfterMs(null, null))
        assertNull(retryAfterMs("-1", null))
    }

    @Test
    fun `adapters read retry-after from the response`() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"error":{"code":"rate_limit_exceeded"}}""", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "12"))
        })
        val config = ProviderConfig("p", "Groq", ApiFormat.OPENAI_CHAT, "https://api.groq.com/openai/v1", "m")
        val e = assertFailsWith<LlmException> { OpenAiChatModel(client, config, "k").stream(request).toList() }
        assertEquals(12_000, e.retryAfterMs)
        assertEquals(LlmFailure.RATE_LIMIT, e.kind)
    }

    @Test
    fun `failover rules`() {
        val quota = LlmException("q", 429, kind = LlmFailure.QUOTA)
        val auth = LlmException("a", 401, kind = LlmFailure.AUTH)
        val rate = LlmException("r", 429, retryable = true, kind = LlmFailure.RATE_LIMIT)
        val overloaded = LlmException("o", 529, retryable = true, kind = LlmFailure.OVERLOADED)
        val bad = LlmException("b", 400)
        assertTrue(Failover.switchesNow(quota))
        assertTrue(Failover.switchesNow(auth))
        assertFalse(Failover.switchesNow(rate))
        assertTrue(Failover.switchesNow(LlmException("r", 429, retryable = true, kind = LlmFailure.RATE_LIMIT, retryAfterMs = 10 * 60_000)))
        assertTrue(Failover.switchesAfterRetries(overloaded))
        assertFalse(Failover.switchesAfterRetries(bad))
        assertFalse(Failover.switchesNow(bad))
        // Keys rotate on errors that are about the key or its account, never on the provider's own trouble.
        assertTrue(Failover.rotatesKey(quota) && Failover.rotatesKey(auth) && Failover.rotatesKey(rate))
        assertFalse(Failover.rotatesKey(overloaded) || Failover.rotatesKey(bad))
        // Backoff: the provider's wait when it gave one (capped), else 2^attempt seconds.
        assertEquals(2_000, Failover.backoffMs(1, overloaded))
        assertEquals(8_000, Failover.backoffMs(3, null))
        assertEquals(37_000, Failover.backoffMs(1, LlmException("r", 429, retryable = true, retryAfterMs = 37_000)))
        assertEquals(Failover.MAX_WAIT_MS, Failover.backoffMs(1, LlmException("r", 429, retryable = true, retryAfterMs = 600_000)))
    }

    @Test
    fun `older call sites keep their meaning`() {
        assertEquals(LlmFailure.TRANSIENT, LlmException("x", retryable = true).kind)
        assertEquals(LlmFailure.OTHER, LlmException("x").kind)
    }

    private val request = ChatRequest("sys", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())
}
