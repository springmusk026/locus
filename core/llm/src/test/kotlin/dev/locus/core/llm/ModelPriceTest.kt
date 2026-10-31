package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.TokenUsage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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

/** Prices from models.dev's `cost` (US dollars per million tokens), for task budgets' cost estimate. */
class ModelPriceTest {
    private val sample = javaClass.getResource("/models-dev-sample.json")!!.readText()

    @Test
    fun `prices are read with their cache rates, and a missing or partial cost is unknown`() {
        val index = ModelCatalogIndex.parseModelsDev(sample)
        assertEquals(ModelPrice(1.25, 10.0, 0.125), index.lookup("https://api.openai.com/v1", ApiFormat.OPENAI_CHAT, "gpt-5")?.price)
        val odd = ModelCatalogIndex.parseModelsDev(
            """{"x":{"models":{
              "free":{"cost":{"input":0,"output":0}},
              "half":{"cost":{"input":1}},
              "none":{},
              "long":{"cost":{"input":2,"output":12,"context_over_200k":{"input":4,"output":18}}}
            }}}""",
        ).providers.getValue("x").models
        assertEquals(ModelPrice(0.0, 0.0), odd.getValue("free").price)
        assertNull(odd.getValue("half").price)
        assertNull(odd.getValue("none").price)
        assertEquals(ModelPrice(4.0, 18.0), odd.getValue("long").price?.over200k)
    }

    @Test
    fun `cost charges fresh input, cache reads, cache writes and output at their own rates`() {
        val opus = ModelPrice(5.0, 25.0, cacheRead = 0.5, cacheWrite = 6.25)
        // input is the whole prompt: 100k fresh + 800k read + 100k written.
        val usage = TokenUsage(input = 1_000_000, output = 10_000, cacheRead = 800_000, cacheWrite = 100_000)
        assertEquals(0.5 + 0.4 + 0.625 + 0.25, opus.cost(usage), 1e-9)
        // No cache prices listed: cached tokens are charged as input (an upper bound, never an undercount).
        assertEquals(5.0 + 0.25, ModelPrice(5.0, 25.0).cost(usage), 1e-9)
        val tiered = ModelPrice(2.0, 12.0, over200k = ModelPrice(4.0, 18.0))
        assertEquals(0.2 + 0.012, tiered.cost(TokenUsage(100_000, 1_000)), 1e-9)
        assertEquals(1.0 + 0.018, tiered.cost(TokenUsage(250_000, 1_000)), 1e-9)
    }

    @Test
    fun `a cached catalogue from before prices is fetched again without its etag`() = runTest {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val http = HttpClient(MockEngine { req -> requests += req; respond(sample, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"v2\"")) })
        val file = File(Files.createTempDirectory("catalog").toFile(), "models-dev.json")
        // As an older Locus wrote it: no version, no prices, checked a minute ago.
        file.writeText("""{"checkedAt":999940000,"etag":"\"v1\"","providers":{"openai":{"models":{"gpt-5":{"context":400000}}}}}""")
        val catalog = ModelCatalog(http, file, now = { 1_000_000_000L })
        assertNull(catalog.cached()?.lookup("https://api.openai.com/v1", ApiFormat.OPENAI_CHAT, "gpt-5")?.price)
        val fresh = catalog.get()
        assertEquals(1, requests.size)
        assertNull(requests.single().headers["If-None-Match"])
        assertNotNull(fresh?.lookup("https://api.openai.com/v1", ApiFormat.OPENAI_CHAT, "gpt-5")?.price)
        // Written with the current version: the next call within a day uses it without asking again.
        catalog.get()
        assertEquals(1, requests.size)
    }
}
