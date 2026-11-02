package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ModelPrice
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCatalogTest {
    /** Four entries copied verbatim from https://models.dev/api.json on 2026-10-31. */
    private val sample = javaClass.getResource("/models-dev-sample.json")!!.readText()

    @Test
    fun `parses models dev entries into the facts the sheet shows`() {
        val index = ModelCatalogIndex.parseModelsDev(sample)
        val opus = index.providers.getValue("anthropic").models.getValue("claude-opus-4-5")
        assertEquals(
            CatalogModel("Claude Opus 4.5 (latest)", 200_000, vision = true, tools = true, reasoning = true, structuredOutput = true, price = ModelPrice(5.0, 25.0, 0.5, 6.25)),
            opus,
        )
        val gpt = index.providers.getValue("openai").models.getValue("gpt-5")
        assertEquals(400_000, gpt.context)
        assertEquals(true, gpt.vision)
        assertEquals("https://openrouter.ai/api/v1", index.providers.getValue("openrouter").api)
        assertEquals(1_048_576, index.providers.getValue("deepseek").models.getValue("deepseek-v4-flash").context)
    }

    @Test
    fun `fields of an unexpected type count as unknown instead of failing the catalogue`() {
        val index = ModelCatalogIndex.parseModelsDev(
            """{"x":{"id":"x","models":{"m":{"name":7,"tool_call":"yes","reasoning":{"a":1},"modalities":"text","limit":{"context":"big"}}}}}""",
        )
        assertEquals(CatalogModel(name = "7"), index.providers.getValue("x").models.getValue("m"))
    }

    @Test
    fun `lookup prefers the endpoint's own listing, then the maker's, then a vendor-less id`() {
        val index = ModelCatalogIndex.parseModelsDev(sample)
        assertEquals("Claude Opus 4.5 (latest)", index.lookup("https://api.anthropic.com", ApiFormat.ANTHROPIC_MESSAGES, "claude-opus-4-5")?.name)
        // OpenRouter is matched through the catalogue's own `api` URL.
        assertEquals(listOf("openrouter"), index.providersFor("https://openrouter.ai/api/v1", ApiFormat.OPENAI_CHAT))
        assertNotNull(index.lookup("https://openrouter.ai/api/v1", ApiFormat.OPENAI_CHAT, "anthropic/claude-opus-4.5"))
        // A proxy nobody lists serves models under their makers' ids.
        assertEquals(200_000, index.lookup("https://proxy.example/v1", ApiFormat.OPENAI_CHAT, "Claude-Opus-4-5")?.context)
        assertEquals(400_000, index.lookup("https://proxy.example/v1", ApiFormat.OPENAI_CHAT, "openai/gpt-5")?.context)
        assertEquals(400_000, index.lookup("https://chatgpt.com/backend-api/codex", ApiFormat.CHATGPT, "gpt-5")?.context)
        assertNull(index.lookup("https://proxy.example/v1", ApiFormat.OPENAI_CHAT, "no-such-model"))
    }

    @Test
    fun `local servers and on-device models get no guesses from the endpoint`() {
        val index = ModelCatalogIndex.parseModelsDev(sample)
        assertEquals(emptyList(), index.providersFor("http://192.168.1.5:1234/v1", ApiFormat.OPENAI_CHAT))
        assertEquals(emptyList(), index.providersFor("http://localhost:11434/v1", ApiFormat.OPENAI_CHAT))
        assertNull(index.lookup("", ApiFormat.ON_DEVICE, "gpt-5"))
    }

    private val requests = mutableListOf<HttpRequestData>()
    private var status = HttpStatusCode.OK
    private val http = HttpClient(
        MockEngine { req ->
            requests += req
            when (status) {
                HttpStatusCode.OK -> respond(sample, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"v1\""))
                else -> respondError(status)
            }
        },
    )
    private var clock = 1_000_000L
    private fun catalog(file: File) = ModelCatalog(http, file, now = { clock })
    private fun tempFile() = File(Files.createTempDirectory("catalog").toFile(), "models-dev.json")

    @Test
    fun `fetches once, then serves the cache for a day without a request`() = runTest {
        val file = tempFile()
        val catalog = catalog(file)
        assertNotNull(catalog.get())
        clock += ModelCatalog.DAY_MS - 1
        assertNotNull(catalog.get())
        assertEquals(1, requests.size)
        // The request carries nothing about the user: no credentials, cookies or ids.
        val headers = requests.single().headers
        assertNull(headers[HttpHeaders.Authorization])
        assertNull(headers[HttpHeaders.Cookie])
        assertEquals(ModelCatalog.URL, requests.single().url.toString())
        assertTrue(file.isFile)
    }

    @Test
    fun `after a day it revalidates with the etag, and a 304 keeps the cache`() = runTest {
        val file = tempFile()
        catalog(file).get()
        clock += ModelCatalog.DAY_MS
        status = HttpStatusCode.NotModified
        // A new instance: the copy on disk is what's used, as after an app restart.
        val again = catalog(file)
        assertEquals(200_000, again.get()?.lookup("https://api.anthropic.com", ApiFormat.ANTHROPIC_MESSAGES, "claude-opus-4-5")?.context)
        assertEquals("\"v1\"", requests.last().headers[HttpHeaders.IfNoneMatch])
        // The 304 counts as a check: no further request today.
        again.get()
        assertEquals(2, requests.size)
    }

    @Test
    fun `offline it uses the cached copy, or nothing, and backs off`() = runTest {
        status = HttpStatusCode.ServiceUnavailable
        val empty = catalog(tempFile())
        assertNull(empty.get())
        empty.get()
        assertEquals(1, requests.size, "a failed fetch isn't retried at once")
        clock += ModelCatalog.HOUR_MS
        assertNull(empty.get())
        assertEquals(2, requests.size)

        status = HttpStatusCode.OK
        val file = tempFile()
        catalog(file).get()
        clock += ModelCatalog.DAY_MS
        status = HttpStatusCode.ServiceUnavailable
        assertNotNull(catalog(file).get(), "the stale copy beats nothing")
    }

    @Test
    fun `cached reads the disk copy without the network`() = runTest {
        val file = tempFile()
        assertNull(catalog(file).cached())
        catalog(file).get()
        val before = requests.size
        assertNotNull(catalog(file).cached())
        assertEquals(before, requests.size)
    }
}
