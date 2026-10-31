package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelListerTest {
    private val seen = mutableListOf<HttpRequestData>()
    private fun client(body: String) = HttpClient(MockEngine { seen += it; respond(body) })

    @Test
    fun `openai-compatible lists chat models and hides embeddings`() = runTest {
        val config = ProviderConfig("p", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "")
        val models = ModelLister.list(client("""{"data":[{"id":"gpt-x"},{"id":"text-embedding-3"},{"id":"models/gemini-y"}]}"""), config, "k")
        assertEquals(listOf("gemini-y", "gpt-x"), models.map { it.id })
        assertEquals("https://api.openai.com/v1/models", seen.single().url.toString())
        assertEquals("Bearer k", seen.single().headers["Authorization"])
    }

    @Test
    fun `anthropic uses its headers and display names`() = runTest {
        val config = ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "")
        val models = ModelLister.list(client("""{"data":[{"id":"claude-opus-5-5","display_name":"Claude Opus 5.5"}],"has_more":false}"""), config, "k")
        assertEquals("Claude Opus 5.5", models.single().displayName)
        assertEquals("k", seen.single().headers["x-api-key"])
    }
}
