package dev.locus.core.agent

import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.PublishHost
import dev.locus.core.agent.integrations.PublishedSite
import dev.locus.core.agent.integrations.SitePublisher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Publish to your own host (docs/design/publish.md) goes through the deploy tools' own code, against a fake HTTP server. */
class SitePublisherTest {
    private val base = Files.createTempDirectory("publish").toFile()
    private val seen = mutableListOf<HttpRequestData>()
    private val page = "<!doctype html><title>Fix tests</title><p>hello</p>"
    private val siteId = "11111111-2222-3333-4444-555555555555"

    private fun folder(): File = File(base, "build/site").apply {
        mkdirs()
        File(this, "index.html").writeText(page)
    }

    private fun publisher(secrets: Map<String, String>, route: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SitePublisher(HttpClient(MockEngine { req -> seen += req; route(req) }), SecretResolver { secrets[it] })

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf("Content-Type", "application/json"))

    private fun HttpRequestData.text() = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `a new netlify page creates its own site and makes a production deploy, in that exact shape`() = runBlocking<Unit> {
        val publisher = publisher(mapOf("integration-netlify" to "ntok")) { req ->
            val path = req.url.encodedPath
            when {
                path == "/api/v1/sites" && req.method == HttpMethod.Get -> json("[]")
                path == "/api/v1/sites" && req.method == HttpMethod.Post ->
                    json("""{"id":"$siteId","name":"locus-fix-tests-abc234","ssl_url":"https://locus-fix-tests-abc234.netlify.app"}""")
                path == "/api/v1/sites/$siteId/deploys" -> json("""{"id":"d1","state":"uploading","required":["${sha1(page)}"]}""")
                path == "/api/v1/deploys/d1/files/index.html" -> json("""{"id":"f"}""")
                path == "/api/v1/deploys/d1" -> json("""{"id":"d1","state":"ready","ssl_url":"https://locus-fix-tests-abc234.netlify.app"}""")
                else -> error("unexpected ${req.method} ${req.url}")
            }
        }
        val name = SitePublisher.newSiteName("Fix tests", suffix = "abc234")
        assertEquals("locus-fix-tests-abc234", name)

        val site = publisher.publish(PublishHost.NETLIFY, folder(), name, update = false)

        assertEquals(PublishedSite(PublishHost.NETLIFY, siteId, "https://locus-fix-tests-abc234.netlify.app"), site)
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Post, HttpMethod.Put, HttpMethod.Get), seen.map { it.method })
        // The lookup by name, then the site, the digests (production, so not a draft), the one file, the final state.
        assertEquals(name, seen[0].url.parameters["name"])
        assertEquals("""{"name":"$name"}""", seen[1].text())
        assertEquals("Published from Locus", seen[2].url.parameters["title"])
        assertEquals(
            mapOf("/index.html" to sha1(page)),
            Json.parseToJsonElement(seen[2].text()).jsonObject["files"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals("false", Json.parseToJsonElement(seen[2].text()).jsonObject["draft"]!!.jsonPrimitive.content)
        assertEquals(page, (seen[3].body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        assertTrue(seen.all { it.headers["Authorization"] == "Bearer ntok" && it.url.host == "api.netlify.com" })
    }

    @Test
    fun `an update deploys to the recorded site without creating one`() = runBlocking<Unit> {
        val publisher = publisher(mapOf("integration-netlify" to "ntok")) { req ->
            when (req.url.encodedPath) {
                "/api/v1/sites/$siteId" -> json("""{"id":"$siteId","name":"locus-x","ssl_url":"https://locus-x.netlify.app"}""")
                "/api/v1/sites/$siteId/deploys" -> json("""{"id":"d2","state":"ready","required":[],"ssl_url":"https://locus-x.netlify.app"}""")
                else -> error("unexpected ${req.method} ${req.url}")
            }
        }
        val site = publisher.publish(PublishHost.NETLIFY, folder(), siteId, update = true)
        assertEquals("https://locus-x.netlify.app", site.url)
        assertTrue(seen.none { it.method == HttpMethod.Post && it.url.encodedPath == "/api/v1/sites" })
    }

    @Test
    fun `unpublish deletes what publish made and a page already gone counts as removed`() = runBlocking<Unit> {
        val publisher = publisher(mapOf("integration-netlify" to "ntok", "integration-vercel" to "vtok")) { req ->
            when {
                req.method == HttpMethod.Delete && req.url.encodedPath == "/api/v1/sites/$siteId" -> respond("", HttpStatusCode.NoContent)
                req.method == HttpMethod.Delete && req.url.encodedPath == "/v9/projects/locus-gone-abc234" ->
                    json("""{"error":{"code":"not_found","message":"Project not found"}}""", HttpStatusCode.NotFound)
                else -> error("unexpected ${req.method} ${req.url}")
            }
        }
        publisher.unpublish(PublishedSite(PublishHost.NETLIFY, siteId, null))
        publisher.unpublish(PublishedSite(PublishHost.VERCEL, "locus-gone-abc234", null))
        assertEquals(2, seen.size)
        // A name that isn't an ID is never sent anywhere.
        assertFailsWith<ToolException> { publisher.unpublish(PublishedSite(PublishHost.NETLIFY, "../accounts", null)) }
        assertEquals(2, seen.size)
    }

    @Test
    fun `cloudflare unpublish never deletes a worker locus didn't tag`() = runBlocking<Unit> {
        val publisher = publisher(mapOf("integration-cloudflare" to "ctok 0123456789abcdef0123456789abcdef")) { req ->
            when {
                req.url.encodedPath.endsWith("/workers/scripts") ->
                    json("""{"success":true,"result":[{"id":"locus-mine-abc234","tags":["locus-static"]},{"id":"their-api","tags":[]}]}""")
                req.method == HttpMethod.Delete -> json("""{"success":true,"result":null}""")
                else -> error("unexpected ${req.method} ${req.url}")
            }
        }
        publisher.unpublish(PublishedSite(PublishHost.CLOUDFLARE, "locus-mine-abc234", null))
        assertEquals("/client/v4/accounts/0123456789abcdef0123456789abcdef/workers/scripts/locus-mine-abc234", seen.last().url.encodedPath)
        assertFailsWith<ToolException> { publisher.unpublish(PublishedSite(PublishHost.CLOUDFLARE, "their-api", null)) }
        assertTrue(seen.none { it.method == HttpMethod.Delete && it.url.encodedPath.endsWith("their-api") })
    }

    @Test
    fun `vercel publishes to production with no framework and reports the production domain`() = runBlocking<Unit> {
        val publisher = publisher(mapOf("integration-vercel" to "vtok")) { req ->
            when (req.url.encodedPath) {
                "/v13/deployments" -> json("""{"id":"dpl_1","url":"locus-a-abc234-me.vercel.app","readyState":"QUEUED"}""")
                "/v9/projects/locus-a-abc234/domains" -> json("""{"domains":[{"name":"locus-a-abc234.vercel.app","verified":true}],"pagination":{}}""")
                else -> error("unexpected ${req.method} ${req.url}")
            }
        }
        val site = publisher.publish(PublishHost.VERCEL, folder(), "locus-a-abc234", update = false)
        val body = Json.parseToJsonElement(seen[0].text()).jsonObject
        assertEquals("production", body["target"]!!.jsonPrimitive.content)
        assertTrue("\"framework\":null" in seen[0].text())
        assertEquals("true", seen[1].url.parameters["production"])
        // The production domain, which Standard Protection leaves public, not the generated deployment URL.
        assertEquals("https://locus-a-abc234.vercel.app", site.url)
    }

    @Test
    fun `only hosts with a key are offered, and new names suit every host`() = runBlocking<Unit> {
        val publisher = publisher(mapOf("integration-cloudflare" to "c", "integration-vercel" to " ")) { error("no calls") }
        assertEquals(listOf(PublishHost.CLOUDFLARE), publisher.configured())
        val name = SitePublisher.newSiteName("Ünïcode & <script> — a very long title that goes on and on")
        assertTrue(Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$").matches(name), name)
        assertTrue(name.startsWith("locus-n-code-script-a-very-long"), name)
        assertTrue(Regex("^locus-[a-z0-9]{6}$").matches(SitePublisher.newSiteName("日本語")))
    }
}
