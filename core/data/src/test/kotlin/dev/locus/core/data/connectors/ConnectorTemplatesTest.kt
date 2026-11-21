package dev.locus.core.data.connectors

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.ConnectorKeys
import dev.locus.core.data.mcp.McpTemplates
import java.net.URI
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The service catalogue (Library → Connectors → Add a service) is well formed and matches the docs it cites. */
class ConnectorTemplatesTest {
    private val all = ConnectorTemplates.all

    @Test
    fun `ids are unique slugs that don't clash with MCP templates`() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach { assertTrue(AgentHome.SKILL_NAME.matches(it.id), it.id) }
        assertTrue(all.none { McpTemplates.byId(it.id) != null }, "an id is used by an MCP template too")
    }

    @Test
    fun `every template makes a valid connector for a user's server`() {
        all.forEach { t ->
            val c = t.connector(t.id, "https://svc.example.com", "${t.id}-token")
            c.validate()
            assertEquals("http", c.kind)
            assertEquals(t.id, c.template)
            assertEquals("user", c.createdBy)
            assertTrue(c.baseUrl.startsWith("https://svc.example.com/") || c.baseUrl == "https://svc.example.com", c.baseUrl)
            assertFalse("{host}" in c.baseUrl, t.id)
            assertEquals(listOf("${t.id}-token"), c.secretNames)
        }
    }

    @Test
    fun `docs are https, paths are relative, names are unique, and test paths only read`() {
        all.forEach { t ->
            val docs = URI(t.docsUrl)
            assertEquals("https", docs.scheme, t.id)
            assertTrue(t.baseUrl.startsWith("{host}"), t.id)
            assertTrue(t.testPath.startsWith("/") && "://" !in t.testPath, t.id)
            assertTrue(t.hostHint.isNotBlank() && t.auth.where.isNotBlank() && t.auth.label.isNotBlank(), t.id)
            assertEquals(t.operations.size, t.operations.map { it.name }.toSet().size, t.id)
            assertTrue(t.operations.any { it.sideEffect == "read" }, "${t.id} has nothing to read")
            t.operations.forEach { op ->
                assertTrue(op.sideEffect in Connector.SIDE_EFFECTS, "${t.id}.${op.name}")
                // A GET only reads; anything else must say what it changes (or be a search that only reads).
                if (op.method == "GET") assertEquals("read", op.sideEffect, "${t.id}.${op.name}")
                op.params.filter { it.location == "path" }.forEach { p -> assertTrue("{${p.name}}" in op.path, "${t.id}.${op.name}: {${p.name}}") }
                Regex("\\{([a-z_]+)\\}").findAll(op.path).forEach { m -> assertTrue(op.params.any { it.name == m.groupValues[1] && it.location == "path" }, "${t.id}.${op.name}") }
            }
        }
    }

    @Test
    fun `auth matches each service's documented scheme`() {
        fun auth(id: String) = ConnectorTemplates.byId(id)!!.connector(id, "https://x.example", "k").auth
        assertEquals(Connector.Auth("header", "X-Auth-Token", "k"), auth("miniflux"))
        assertEquals(Connector.Auth("header", "Authorization", "k", prefix = "Token "), auth("paperless"))
        assertEquals(Connector.Auth("header", "x-api-key", "k"), auth("immich"))
        assertEquals(Connector.Auth("bearer", secret = "k"), auth("mastodon"))
        assertEquals(Connector.Auth("bearer", secret = "k"), auth("vikunja"))
        assertEquals(Connector.Auth("header", "Authorization", "k", prefix = "GoogleLogin auth="), auth("freshrss"))
        assertEquals(ConnectorTemplate.Login.GOOGLE_READER, ConnectorTemplates.byId("freshrss")!!.auth.login)
        // Jellyfin: the Authorization header's MediaBrowser scheme with the key quoted (legacy X-Emby-Token is off by default from 12.0).
        val jellyfin = auth("jellyfin")
        assertEquals(Connector.Auth("header", "Authorization", "k", prefix = "MediaBrowser Token=\"", suffix = "\""), jellyfin)
        assertEquals("Authorization" to "MediaBrowser Token=\"abc123\"", ConnectorKeys.header(jellyfin, "abc123"))
        assertEquals("the Authorization header (Authorization: MediaBrowser Token=\"…\")", ConnectorTemplates.byId("jellyfin")!!.auth.sentAs)
    }

    @Test
    fun `jellyfin only reads, with bounded searches, from the documented endpoints`() {
        val t = ConnectorTemplates.byId("jellyfin")!!
        assertEquals(listOf("search", "recently_added", "item", "now_playing"), t.operations.map { it.name })
        assertTrue(t.operations.all { it.method == "GET" && it.sideEffect == "read" })
        assertTrue(t.operations.all { it.path.startsWith("/Items?") || it.path.startsWith("/Sessions?") })
        // Listing the whole library would be huge: the model has to say how many.
        listOf("search", "recently_added").forEach { name -> assertTrue(t.operations.first { it.name == name }.params.first { it.name == "limit" }.required, name) }
        assertEquals("/Items?limit=1&enableImages=false", t.testPath)
        assertEquals("https://media.example.com", t.connector("jellyfin", "https://media.example.com", "k").baseUrl)
    }

    @Test
    fun `a header suffix is short, one line, and only for header auth`() {
        fun valid(auth: Connector.Auth) = runCatching {
            Connector(id = "x", name = "X", description = "x", baseUrl = "https://x.example", auth = auth, operations = listOf(Connector.Operation("a", "a", path = "/a"))).validate()
        }.isSuccess
        assertTrue(valid(Connector.Auth("header", "Authorization", "k", prefix = "MediaBrowser Token=\"", suffix = "\"")))
        assertFalse(valid(Connector.Auth("bearer", secret = "k", suffix = "\"")))
        assertFalse(valid(Connector.Auth("header", "Authorization", "k", suffix = "\r\nX: 1")))
        assertFalse(valid(Connector.Auth("header", "Authorization", "k", suffix = "123456789")))
    }

    @Test
    fun `only posting is outward, and the read-only services change nothing`() {
        assertEquals(mapOf("mastodon" to listOf("post")), all.filter { it.outward.isNotEmpty() }.associate { it.id to it.outward })
        listOf("freshrss", "paperless", "immich", "jellyfin").forEach { id ->
            assertTrue(ConnectorTemplates.byId(id)!!.operations.all { it.sideEffect == "read" }, id)
        }
        assertTrue(all.flatMap { it.operations }.none { it.sideEffect == "destructive" }, "no template deletes anything")
    }

    @Test
    fun `the address a connector was made for can be recovered for Replace key`() {
        val t = ConnectorTemplates.byId("miniflux")!!
        val c = t.connector("miniflux", "https://home.example.com/rss", "k")
        assertEquals("https://home.example.com/rss/v1", c.baseUrl)
        assertEquals("https://home.example.com/rss", c.baseUrl.removeSuffix(t.baseUrl.removePrefix("{host}")))
    }
}
