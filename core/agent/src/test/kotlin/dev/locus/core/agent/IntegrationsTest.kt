package dev.locus.core.agent

import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.integrationTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntegrationsTest {
    private val seen = mutableListOf<HttpRequestData>()

    private fun tools(body: String, status: HttpStatusCode = HttpStatusCode.OK, key: String? = "fc-key") =
        ToolRegistry(integrationTools(HttpClient(MockEngine { seen += it; respond(body, status) }), SecretResolver { key }))

    /** Only [service]'s key is set, so web_search must route to it. */
    private fun only(service: String, body: String) =
        ToolRegistry(integrationTools(HttpClient(MockEngine { seen += it; respond(body) }), SecretResolver { if (it == "integration-$service") "$service-key" else null }))

    private fun search(registry: ToolRegistry, vararg args: Pair<String, String>) = runTestResult {
        registry["web_search"]!!.execute(JsonObject(mapOf("query" to JsonPrimitive("pokhara")) + args.associate { (k, v) -> k to JsonPrimitive(v) }))
    }

    private fun <T> runTestResult(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

    @Test
    fun `tavily is used when it is the only key`() {
        val r = search(only("tavily", """{"results":[{"title":"Phewa","url":"https://t.test","content":"A lake","score":0.9}]}"""), "time" to "week")
        val req = seen.single()
        assertEquals("https://api.tavily.com/search", req.url.toString())
        assertEquals("Bearer tavily-key", req.headers["Authorization"])
        assertTrue("\"time_range\":\"week\"" in (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        assertTrue("Results from Tavily" in r.content && "1. Phewa" in r.content && "A lake" in r.content)
    }

    @Test
    fun `brave sends token and freshness as query parameters`() {
        val r = search(only("brave", """{"web":{"results":[{"title":"B","url":"https://b.test","description":"<strong>Lake</strong> city"}]}}"""), "time" to "month", "country" to "NP")
        val url = seen.single().url
        assertEquals("api.search.brave.com", url.host)
        assertEquals("pm", url.parameters["freshness"])
        assertEquals("np", url.parameters["country"])
        assertEquals("brave-key", seen.single().headers["X-Subscription-Token"])
        assertTrue("Lake city" in r.content)
    }

    @Test
    fun `exa uses highlights as the snippet`() {
        val r = search(only("exa", """{"requestId":"r","results":[{"title":"E","url":"https://e.test","highlights":["Best lake view"]}]}"""))
        assertEquals("https://api.exa.ai/search", seen.single().url.toString())
        assertEquals("exa-key", seen.single().headers["x-api-key"])
        assertTrue("Best lake view" in r.content)
    }

    @Test
    fun `search posts v2 request and formats web results`() = runTest {
        val t = tools("""{"success":true,"data":{"web":[{"title":"Pokhara","url":"https://a.test","description":"Lake city"}]},"creditsUsed":2}""")
        val r = t["web_search"]!!.execute(JsonObject(mapOf("query" to JsonPrimitive("pokhara"), "time" to JsonPrimitive("week"))))
        assertEquals("https://api.firecrawl.dev/v2/search", seen.single().url.toString())
        assertEquals("Bearer fc-key", seen.single().headers["Authorization"])
        val body = (seen.single().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue("\"tbs\":\"qdr:w\"" in body && "\"limit\":5" in body)
        assertTrue("Results from Firecrawl" in r.content && "1. Pokhara" in r.content && "https://a.test" in r.content && "untrusted_web_content" in r.content)
    }

    @Test
    fun `scrape returns markdown with title`() = runTest {
        val t = tools("""{"success":true,"data":{"markdown":"Hello **world**","metadata":{"title":"Doc","url":"https://b.test"}}}""")
        val r = t["web_read"]!!.execute(JsonObject(mapOf("url" to JsonPrimitive("https://b.test"))))
        assertTrue("# Doc" in r.content && "Hello **world**" in r.content)
    }

    @Test
    fun `missing key and quota errors explain what to do`() = runTest {
        assertTrue("Settings → Integrations" in assertFailsWith<ToolException> { tools("{}", key = null)["web_search"]!!.execute(JsonObject(mapOf("query" to JsonPrimitive("x")))) }.message!!)
        assertTrue("out of credits" in assertFailsWith<ToolException> { tools("""{"success":false,"error":"x"}""", HttpStatusCode.PaymentRequired)["web_read"]!!.execute(JsonObject(mapOf("url" to JsonPrimitive("https://c.test")))) }.message!!)
    }

    @Test
    fun `github sends token and version and decodes files`() {
        val content = java.util.Base64.getMimeEncoder().encodeToString("hello\nworld".toByteArray())
        val r = runTestResult {
            only("github", """{"type":"file","encoding":"base64","path":"README.md","content":"$content"}""")["github_read"]!!
                .execute(JsonObject(mapOf("repo" to JsonPrimitive("octo/hi"), "path" to JsonPrimitive("README.md"))))
        }
        val req = seen.single()
        assertEquals("https://api.github.com/repos/octo/hi/contents/README.md", req.url.toString())
        assertEquals("Bearer github-key", req.headers["Authorization"])
        assertEquals("2026-03-10", req.headers["X-GitHub-Api-Version"])
        assertTrue("hello\nworld" in r.content && "untrusted_web_content" in r.content)
    }

    @Test
    fun `github rejects malformed repo names`() {
        assertFailsWith<ToolException> {
            runTestResult { only("github", "{}")["github_issues"]!!.execute(JsonObject(mapOf("repo" to JsonPrimitive("../../user")))) }
        }
    }

    @Test
    fun `github issue list marks pull requests`() {
        val r = runTestResult {
            only("github", """[{"number":1,"title":"Bug","state":"open","comments":2},{"number":2,"title":"Fix","state":"open","comments":0,"pull_request":{"url":"u"}}]""")["github_issues"]!!
                .execute(JsonObject(mapOf("repo" to JsonPrimitive("octo/hi"))))
        }
        assertTrue("#1 [issue, open] Bug" in r.content && "#2 [PR, open] Fix" in r.content)
    }

    @Test
    fun `ntfy posts to the user's topic with headers and token`() {
        assertEquals(null, dev.locus.core.agent.integrations.Ntfy.parse("not a url"))
        assertEquals(null, dev.locus.core.agent.integrations.Ntfy.parse("https://ntfy.sh/bad topic!"))
        val r = runTestResult {
            only("ntfy", "{}")
            ToolRegistry(integrationTools(HttpClient(MockEngine { seen += it; respond("""{"id":"x"}""") }), SecretResolver { if (it == "integration-ntfy") "https://ntfy.sh/my_topic-1 tk_abc" else null }))["notify_me"]!!
                .execute(JsonObject(mapOf("title" to JsonPrimitive("Build done"), "message" to JsonPrimitive("All **green**"), "priority" to JsonPrimitive(4))))
        }
        val req = seen.last()
        assertEquals("https://ntfy.sh/my_topic-1", req.url.toString())
        assertEquals("Bearer tk_abc", req.headers["Authorization"])
        assertEquals("Build done", req.headers["Title"])
        assertEquals("4", req.headers["Priority"])
        assertEquals("Sent to ntfy.", r.content)
    }
}
