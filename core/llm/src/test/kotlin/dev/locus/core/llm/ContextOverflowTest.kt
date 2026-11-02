package dev.locus.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Each provider's "doesn't fit the context window" error, as documented or observed (see [ContextOverflow]). */
class ContextOverflowTest {
    private fun kind(status: Int, body: String) = httpError("P", status, body).kind

    @Test
    fun `anthropic prompt is too long`() {
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 208310 tokens > 200000 maximum"},"request_id":"req_1"}"""
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, body))
        assertFalse(httpError("Anthropic", 400, body).retryable)
        // Other invalid requests are not overflow.
        assertEquals(LlmFailure.OTHER, kind(400, """{"type":"error","error":{"type":"invalid_request_error","message":"messages: roles must alternate"}}"""))
    }

    @Test
    fun `openai context_length_exceeded, by code even when the wording changes`() {
        val classic = """{"error":{"message":"This model's maximum context length is 4096 tokens. However, your messages resulted in 4239 tokens. Please reduce the length of the messages.","type":"invalid_request_error","param":"messages","code":"context_length_exceeded"}}"""
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, classic))
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, """{"error":{"message":"Your input exceeds the context window of this model.","type":"invalid_request_error","code":"context_length_exceeded"}}"""))
        // A per-field limit isn't overflow.
        assertEquals(LlmFailure.OTHER, kind(400, """{"error":{"message":"Invalid 'user': string too long. Expected a string with maximum length 64","type":"invalid_request_error","code":"string_above_max_length"}}"""))
    }

    @Test
    fun `openai responses streams it as response failed`() {
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, responsesFailure("context_length_exceeded", "Your input exceeds the context window of this model. Please adjust your input and try again."))
        assertEquals(LlmFailure.RATE_LIMIT, responsesFailure("rate_limit_exceeded", "slow down"))
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, ChatGptErrors.fromCode("context_length_exceeded", "Your input exceeds the context window of this model.").kind)
    }

    @Test
    fun `gemini input token count exceeds the maximum, native and openai-compatible`() {
        val native = """{"error":{"code":400,"message":"The input token count (35868) exceeds the maximum number of tokens allowed (32768).","status":"INVALID_ARGUMENT"}}"""
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, native))
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, "[$native]"))
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, """{"error":{"code":400,"message":"The input token count exceeds the maximum number of tokens allowed 1237083.","status":"INVALID_ARGUMENT"}}"""))
    }

    @Test
    fun `openai-compatible servers`() {
        // OpenRouter's typed error_type.
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, """{"error":{"code":400,"message":"Upstream error","metadata":{"error_type":"context_length_exceeded"}}}"""))
        // DeepSeek / Groq: OpenAI wording, sometimes without the code.
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, """{"error":{"message":"This model's maximum context length is 131072 tokens. However, you requested 134142 tokens (70142 in the messages, 64000 in the completion). Please reduce the length of the messages or completion.","type":"invalid_request_error","code":"invalid_request_error"}}"""))
        // vLLM: the code is the HTTP status.
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, """{"error":{"message":"This model's maximum context length is 8192 tokens. However, your prompt contains 9000 input tokens, which exceeds this limit even without any output tokens.","type":"BadRequestError","param":null,"code":400}}"""))
        // llama.cpp server.
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, """{"error":{"code":400,"message":"request (9000 tokens) exceeds the available context size (8192 tokens), try increasing it","type":"exceed_context_size_error","n_prompt_tokens":9000,"n_ctx":8192}}"""))
        // Mistral.
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, """{"object":"error","message":"Prompt contains 65673 tokens, too large for model with 32768 maximum context length","type":"invalid_request_error"}"""))
        // Ollama with context shift off: a plain string error.
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(400, """{"error":"the prompt is longer than the context length currently available to the model; shorten the prompt"}"""))
        // Cloudflare-style 413.
        assertEquals(LlmFailure.CONTEXT_OVERFLOW, kind(413, """{"error":{"message":"input exceeds the context window"}}"""))
    }

    @Test
    fun `rate limits that mention tokens are not overflow`() {
        val tpm = """{"error":{"message":"Rate limit reached for gpt-x on tokens per min (TPM): Limit 30000, Used 29000, Requested 2000.","type":"tokens","code":"rate_limit_exceeded"}}"""
        assertEquals(LlmFailure.RATE_LIMIT, kind(429, tpm))
        assertFalse(ContextOverflow.matches(null, "Too many tokens per day, please wait"))
        assertNotEquals(LlmFailure.CONTEXT_OVERFLOW, kind(500, "maximum context length"))
    }

    @Test
    fun `an unclassified exception is recognised by its message`() {
        assertTrue(ContextOverflow.isOverflow(LlmException("Anthropic stream error (invalid_request_error): prompt is too long: 1 tokens > 0 maximum")))
        assertTrue(ContextOverflow.isOverflow(LlmException("x", kind = LlmFailure.CONTEXT_OVERFLOW)))
        assertFalse(ContextOverflow.isOverflow(LlmException("prompt is too long", kind = LlmFailure.RATE_LIMIT)))
        assertFalse(ContextOverflow.isOverflow(IllegalStateException("prompt is too long")))
    }
}
