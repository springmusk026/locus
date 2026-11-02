package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpErrorsTest {
    private fun openAi(code: String, type: String = "requests") =
        """{"error":{"message":"Limit hit for $code.","type":"$type","param":null,"code":"$code"}}"""

    @Test
    fun `openai spend, credit and usage limits are not retried`() {
        for (code in listOf("credit_balance_exhausted", "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded")) {
            val e = httpError("OpenAI", 429, openAi(code))
            assertFalse(e.retryable, code)
            assertEquals(429, e.httpStatus)
            assertTrue("won't retry" in e.message!!, code)
            assertTrue("Limit hit for $code." in e.message!!, code)
        }
        // The classic quota error carries insufficient_quota as both type and code.
        assertFalse(httpError("OpenAI", 429, openAi("insufficient_quota", type = "insufficient_quota")).retryable)
    }

    @Test
    fun `openai rate limits, slow_down and overload stay retryable`() {
        assertTrue(httpError("OpenAI", 429, openAi("rate_limit_exceeded")).retryable)
        assertTrue(httpError("OpenAI", 429, """{"error":{"message":"Your request rate increased too quickly.","type":"rate_limit_error","code":"slow_down"}}""").retryable)
        assertTrue(httpError("OpenAI", 503, """{"error":{"message":"Overloaded","type":"service_unavailable_error","code":"server_is_overloaded"}}""").retryable)
        assertTrue(httpError("OpenAI", 500, "").retryable)
        assertTrue(httpError("Groq", 429, "not json").retryable)
    }

    @Test
    fun `anthropic spend cap is told apart from a rate limit`() {
        val cap = """{"type":"error","error":{"type":"rate_limit_error","message":"You have reached your API usage limits.","details":{"error_code":"enforced_spend_limit_reached"}}}"""
        assertFalse(httpError("Anthropic", 429, cap).retryable)
        val limit = """{"type":"error","error":{"type":"rate_limit_error","message":"Number of request tokens has exceeded your per-minute rate limit"}}"""
        assertTrue(httpError("Anthropic", 429, limit).retryable)
        assertTrue(httpError("Anthropic", 529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""").retryable)
    }

    @Test
    fun `other statuses keep their usual retryability`() {
        assertFalse(httpError("OpenAI", 401, openAi("invalid_api_key")).retryable)
        // A quota code on a non-429 status is left to the status rule.
        assertFalse(httpError("OpenAI", 400, openAi("insufficient_quota")).retryable)
        assertTrue(httpError("OpenAI", 502, openAi("insufficient_quota")).retryable)
    }

    @Test
    fun `chat models surface a spend limit as not retryable`() = runTest {
        val engine = RecordingEngine(HttpStatusCode.TooManyRequests, openAi("project_spend_limit_exceeded"))
        val config = ProviderConfig("p", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-x")
        val request = ChatRequest("s", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())
        val e = assertFailsWith<LlmException> { OpenAiChatModel(engine.client, config, "k").stream(request).toList() }
        assertFalse(e.retryable)
        assertEquals(429, e.httpStatus)
    }
}
