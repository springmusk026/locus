package dev.locus.core.data.publish

import dev.locus.core.agent.integrations.PublishHost
import dev.locus.core.agent.integrations.PublishedSite
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The list of published pages (docs/design/publish.md), kept in a small file of its own. */
class PublishedPagesTest {
    private val dir = Files.createTempDirectory("published").toFile()
    private val file = File(dir, "published-pages.json")
    private var now = 1_000L

    private fun store() = PublishedPagesFile(file) { now }

    @Test
    fun `pages survive a restart, newest first`() = runTest {
        val first = store()
        val chat = first.record(PublishedSite(PublishHost.NETLIFY, "site-1", "https://a.netlify.app"), "Fix tests", PublishedPage.Kind.CHAT, "chat:c1")
        now = 2_000L
        first.record(PublishedSite(PublishHost.CLOUDFLARE, "locus-chart-abc234", "https://locus-chart-abc234.me.workers.dev"), "Chart", PublishedPage.Kind.ARTIFACT, "artifact:c1:k")

        val reopened = store().pages.value
        assertEquals(listOf("Chart", "Fix tests"), reopened.map { it.title })
        assertEquals(chat, reopened.last())
        assertEquals(PublishHost.CLOUDFLARE, reopened.first().publishHost)
    }

    @Test
    fun `an update keeps the page and its site, with the new time and address`() = runTest {
        val pages = store()
        val page = pages.record(PublishedSite(PublishHost.VERCEL, "locus-x-abc234", null), "Report", PublishedPage.Kind.CHAT, "chat:c2")
        now = 5_000L
        val updated = pages.record(PublishedSite(PublishHost.VERCEL, "locus-x-abc234", "https://locus-x-abc234.vercel.app"), "Report v2", PublishedPage.Kind.CHAT, "chat:c2", existing = page)

        assertEquals(page.id, updated.id)
        assertEquals(1_000L, updated.publishedAt)
        assertEquals(5_000L, updated.updatedAt)
        assertEquals("https://locus-x-abc234.vercel.app", updated.url)
        assertEquals(listOf(updated), store().pages.value)
    }

    @Test
    fun `removing forgets the page, and an unreadable file counts as empty`() = runTest {
        val pages = store()
        val page = pages.record(PublishedSite(PublishHost.NETLIFY, "s", "https://s.netlify.app"), "Page", PublishedPage.Kind.CHAT, "chat:c3")
        pages.remove(page.id)
        assertTrue(store().pages.value.isEmpty())

        file.writeText("{not json")
        assertTrue(store().pages.value.isEmpty())
        // And writing over it works.
        store().record(PublishedSite(PublishHost.NETLIFY, "t", null), "Again", PublishedPage.Kind.CHAT, "chat:c4")
        assertEquals(listOf("Again"), store().pages.value.map { it.title })
    }
}
