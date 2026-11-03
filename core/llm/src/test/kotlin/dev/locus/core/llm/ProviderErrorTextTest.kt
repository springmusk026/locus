package dev.locus.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderErrorTextTest {
    private val nginx504 = """
        <html>
        <head><title>504 Gateway Time-out</title></head>
        <body>
        <center><h1>504 Gateway Time-out</h1></center>
        <hr><center>nginx</center>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun `an html 504 page becomes plain words and stays retryable`() {
        val e = httpError("OpenAI", 504, nginx504)
        assertEquals("OpenAI's gateway timed out (HTTP 504). This is usually temporary. Try again.", e.message)
        assertFalse('<' in e.message!!)
        assertTrue(e.retryable)
        assertEquals(LlmFailure.TRANSIENT, e.kind)
        // The page itself is kept for Diagnostics, never shown.
        assertEquals(nginx504, e.rawBody)
    }

    @Test
    fun `a json 400 keeps the provider's message`() {
        val body = """{"error":{"message":"Invalid value for 'temperature': must be between 0 and 2.","type":"invalid_request_error","param":"temperature","code":null}}"""
        val e = httpError("OpenAI", 400, body)
        assertEquals("OpenAI returned HTTP 400: Invalid value for 'temperature': must be between 0 and 2.", e.message)
        assertFalse(e.retryable)
        assertEquals(body, e.rawBody)
    }

    @Test
    fun `an empty body says the status in plain words`() {
        val e = httpError("OpenAI", 502, "")
        assertEquals("OpenAI's gateway got no valid answer from the model server (HTTP 502). This is usually temporary. Try again.", e.message)
        assertTrue(e.retryable)
        assertNull(e.rawBody)
        assertEquals("OpenAI is limiting how often requests can be made (HTTP 429). Wait a moment and try again.", httpError("OpenAI", 429, "  ").message)
    }

    @Test
    fun `cloudflare 524 and other gateway statuses read as timeouts or outages`() {
        val cloudflare = "<!DOCTYPE html><html><head><title>example.com | 524: A timeout occurred</title></head><body>...</body></html>"
        assertEquals("OpenRouter's gateway timed out waiting for the model (HTTP 524). This is usually temporary. Try again.", httpError("OpenRouter", 524, cloudflare).message)
        assertTrue(httpError("OpenRouter", 524, cloudflare).retryable)
        assertTrue(httpError("X", 503, "<html>Service Unavailable</html>").message!!.startsWith("X is unavailable or overloaded right now (HTTP 503)."))
        assertTrue(httpError("X", 401, "<html>401</html>").message!!.contains("Settings → Models"))
    }

    @Test
    fun `long non-json bodies are not shown, short plain ones are`() {
        val dump = "upstream connect error or disconnect/reset before headers. ".repeat(10)
        assertEquals("Groq had an internal error (HTTP 500). This is usually temporary. Try again.", httpError("Groq", 500, dump).message)
        assertEquals("Groq returned HTTP 500: model is loading", httpError("Groq", 500, "model is loading").message)
    }

    @Test
    fun `other json shapes keep their message`() {
        assertEquals("model 'llama9' not found", ProviderErrorText.providerWords("""{"error":"model 'llama9' not found"}"""))
        assertEquals("Not Found", ProviderErrorText.providerWords("""{"detail":"Not Found"}"""))
        assertEquals("Quota exceeded", ProviderErrorText.providerWords("""[{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}]"""))
        // JSON with nothing to say falls back to the status.
        assertNull(ProviderErrorText.providerWords("""{"ok":false}"""))
        // A message that is itself markup isn't shown.
        assertNull(ProviderErrorText.providerWords("""{"error":{"message":"<html><body>bad gateway</body></html>"}}"""))
    }

    @Test
    fun `an unknown status names the page title, without markup`() {
        assertEquals("X returned HTTP 418 (I'm a teapot).", httpError("X", 418, "<html><head><title>I&#39;m a teapot</title></head></html>").message)
        assertEquals("X returned HTTP 418.", httpError("X", 418, "<html><body>no title</body></html>").message)
    }

    @Test
    fun `chatgpt plan errors never show markup either`() {
        val e = ChatGptErrors.fromHttp(504, nginx504)
        assertEquals("ChatGPT's gateway timed out (HTTP 504). This is usually temporary. Try again.", e.message)
        assertTrue(e.retryable)
        assertEquals(nginx504, e.rawBody)
        assertEquals("ChatGPT refused the request (HTTP 403).", ChatGptErrors.fromHttp(403, "<html>Forbidden</html>").message)
        assertEquals("ChatGPT refused the request: nope", ChatGptErrors.fromHttp(403, """{"detail":"nope"}""").message)
    }
}
