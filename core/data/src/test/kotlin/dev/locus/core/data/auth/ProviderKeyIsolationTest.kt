package dev.locus.core.data.auth

import dev.locus.core.llm.BearerSource
import dev.locus.core.llm.ChatRequest
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chat that switches between providers (nanoMuse#580: one key slot shared by every provider) must
 * only ever send each provider its own credential. [ProviderCredentials] is what [ProviderAccess]
 * builds every cloud model with.
 */
class ProviderKeyIsolationTest {
    private val anthropic = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-x")
    private val openAi = ProviderConfig("b", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-x")
    private val groq = ProviderConfig("c", "Groq", ApiFormat.OPENAI_CHAT, "https://api.groq.com/openai/v1", "llama-x")
    private val ollama = ProviderConfig("d", "Ollama", ApiFormat.OPENAI_CHAT, "http://192.168.1.10:11434/v1", "qwen", requiresKey = false)
    private val chatGpt = ProviderConfig("e", "ChatGPT", ApiFormat.CHATGPT, "https://chatgpt.example/v1", "gpt-y", requiresKey = false)

    private val keys = mapOf("a" to "sk-ant-AAAA", "b" to "sk-proj-BBBB", "c" to "gsk_CCCC")
    private val tokens = mapOf("e" to "at-EEEE")
    private val allSecrets = keys.values + tokens.values

    private class Sent(val host: String, val path: String, val headers: Map<String, List<String>>, val body: String)

    @Test
    fun `switching a chat between providers never sends one provider's key to another`() = runTest {
        val sent = mutableListOf<Sent>()
        val http = HttpClient(MockEngine { req ->
            val body = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            sent += Sent(req.url.host, req.url.encodedPath, req.headers.entries().associate { it.key to it.value }, body)
            respond("", HttpStatusCode.OK)
        })
        val lookedUp = mutableListOf<String>()
        val credentials = ProviderCredentials(
            http,
            apiKey = { id -> lookedUp += id; keys[id] },
            bearer = { id -> BearerSource { tokens.getValue(id) } },
        )
        val history = listOf(Message("m", "chat", Role.USER, listOf(Part.Text("hello")), 0))
        // The order a chat might switch in, including back and forth and a per-chat model override.
        val switches = listOf(anthropic, openAi, groq, ollama, chatGpt, anthropic.copy(model = "claude-y"), ollama, openAi, chatGpt, groq)
        switches.forEach { provider ->
            runCatching { credentials.chatModel(provider).stream(ChatRequest("sys", history, emptyList())).toList() }
            val request = sent.last()
            val own = keys[provider.id] ?: tokens[provider.id]
            val everything = request.headers.toString() + request.body
            (allSecrets - listOfNotNull(own)).forEach { other ->
                assertFalse(other in everything, "${provider.name} request carried another provider's secret")
            }
            when (provider.format) {
                ApiFormat.ANTHROPIC_MESSAGES -> assertEquals<List<String>?>(listOfNotNull(own), request.headers["x-api-key"])
                else -> if (own == null) assertNull(request.headers["Authorization"]) else assertEquals<List<String>?>(listOf("Bearer $own"), request.headers["Authorization"])
            }
            if (own != null) assertFalse(request.body.contains(own), "keys travel in headers, never in the body")
        }
        // Anthropic also looks up the model's capabilities (GET /v1/models/{id}) at most once per model;
        // those carry only Anthropic's own key too.
        val lookups = sent.filter { it.path.startsWith("/v1/models/") }
        lookups.forEach { assertEquals("api.anthropic.com", it.host) }
        lookups.forEach { l -> (allSecrets - keys.getValue("a")).forEach { assertFalse(it in l.headers.toString()) } }
        assertEquals(switches.size, sent.size - lookups.size)
        // Keys are looked up by the provider's own id, fresh for every model (nothing is carried over).
        assertEquals(switches.filterNot { it.signsIn }.map { it.id }, lookedUp)
    }
}
