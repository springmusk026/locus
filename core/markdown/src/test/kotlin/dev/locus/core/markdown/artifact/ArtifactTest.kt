package dev.locus.core.markdown.artifact

import dev.locus.core.markdown.FenceKind
import dev.locus.core.markdown.fenceKind
import dev.locus.core.markdown.artifact.ArtifactSandbox.Decision
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactDetectionTest {
    @Test
    fun `only html and svg fences marked artifact are artifacts`() {
        assertEquals(ArtifactFence(ArtifactKind.Html, null), parseArtifactFence("html artifact"))
        assertEquals(ArtifactFence(ArtifactKind.Svg, null), parseArtifactFence(" SVG  Artifact "))
        assertEquals(ArtifactFence(ArtifactKind.Html, "Sales by month"), parseArtifactFence("html artifact title=\"Sales by month\""))
        assertEquals(ArtifactFence(ArtifactKind.Html, "Clock"), parseArtifactFence("html title=Clock artifact"))
        assertNull(parseArtifactFence("html"))
        assertNull(parseArtifactFence("svg"))
        assertNull(parseArtifactFence("artifact"))
        assertNull(parseArtifactFence("javascript artifact"))
        assertNull(parseArtifactFence("html artifacts"))
        assertNull(parseArtifactFence(null))
        assertNull(parseArtifactFence(""))
    }

    @Test
    fun `fence kind routes artifacts and leaves plain html as code`() {
        assertEquals(FenceKind.Artifact, fenceKind("html artifact"))
        assertEquals(FenceKind.Artifact, fenceKind("svg artifact title=x"))
        assertEquals(FenceKind.Code, fenceKind("html"))
        assertEquals(FenceKind.Mermaid, fenceKind("mermaid"))
    }

    @Test
    fun `artifacts are found in a reply`() {
        val reply = """
            Here is the chart:

            ```html artifact title="Chart"
            <canvas id="c"></canvas>
            <script>draw()</script>
            ```

            And the markup as an example, not to run:

            ```html
            <p>example</p>
            ```

            ~~~~svg artifact
            <svg viewBox="0 0 10 10"><circle r="4"/></svg>
            ```
            still inside
            ~~~~
        """.trimIndent()
        val found = findArtifacts(reply)
        assertEquals(2, found.size)
        assertEquals(ArtifactKind.Html, found[0].kind)
        assertEquals("Chart", found[0].title)
        assertEquals("<canvas id=\"c\"></canvas>\n<script>draw()</script>", found[0].content)
        assertEquals(ArtifactKind.Svg, found[1].kind)
        assertTrue(found[1].content.endsWith("still inside"))
    }

    @Test
    fun `an unclosed artifact fence is not run`() {
        assertTrue(findArtifacts("```html artifact\n<p>half").isEmpty())
        assertTrue(findArtifacts("Text only").isEmpty())
    }

    @Test
    fun `sent files are artifacts by extension`() {
        assertEquals(ArtifactKind.Html, artifactKindForFile("report.HTML"))
        assertEquals(ArtifactKind.Html, artifactKindForFile("a.htm"))
        assertEquals(ArtifactKind.Svg, artifactKindForFile("logo.svg"))
        assertNull(artifactKindForFile("notes.md"))
        assertNull(artifactKindForFile("html"))
    }

    @Test
    fun `key depends on kind and content, not on line endings`() {
        val a = Artifact(ArtifactKind.Html, "<p>a</p>\n<p>b</p>")
        assertEquals(64, a.key.length)
        assertEquals(a.key, Artifact(ArtifactKind.Html, "<p>a</p>\r\n<p>b</p>", title = "other title").key)
        assertNotEquals(a.key, Artifact(ArtifactKind.Svg, a.content).key)
        assertNotEquals(a.key, Artifact(ArtifactKind.Html, "<p>a</p>").key)
    }

    @Test
    fun `file names come from the title`() {
        assertEquals("sales-by-month.html", Artifact(ArtifactKind.Html, "x", "Sales by Month!").fileName())
        assertEquals("artifact.svg", Artifact(ArtifactKind.Svg, "x", "  ").fileName())
        assertEquals("artifact.html", Artifact(ArtifactKind.Html, "x").fileName())
    }

    @Test
    fun `size cap`() {
        assertTrue(withinArtifactCap(1))
        assertTrue(withinArtifactCap(MAX_ARTIFACT_BYTES.toLong()))
        assertFalse(withinArtifactCap(MAX_ARTIFACT_BYTES + 1L))
        assertFalse(withinArtifactCap(0))
        // The cap counts UTF-8 bytes, not characters.
        val wide = Artifact(ArtifactKind.Html, "界".repeat(MAX_ARTIFACT_BYTES / 3 + 1))
        assertFalse(withinArtifactCap(wide.bytes.toLong()))
    }
}

class ArtifactSandboxTest {
    private val id = "abc123"
    private val base = "https://appassets.androidplatform.net/artifact/$id/"

    @Test
    fun `only this preview's two documents are served`() {
        assertEquals(Decision.Host, ArtifactSandbox.decide(base + "index.html", id))
        assertEquals(Decision.Content, ArtifactSandbox.decide(base + "content.html", id))
        assertEquals(base + "index.html", ArtifactSandbox.hostUrl(id))
    }

    @Test
    fun `data and blob stay local`() {
        assertEquals(Decision.Local, ArtifactSandbox.decide("data:image/png;base64,AAAA", id))
        assertEquals(Decision.Local, ArtifactSandbox.decide("blob:https://appassets.androidplatform.net/1234", id))
    }

    @Test
    fun `everything else is blocked`() {
        listOf(
            "https://cdn.jsdelivr.net/npm/chart.js",
            "http://appassets.androidplatform.net/artifact/$id/index.html",
            "https://appassets.androidplatform.net/artifact/other/index.html",
            "https://appassets.androidplatform.net/assets/mermaid/render.html",
            "https://appassets.androidplatform.net/artifact/$id/other.js",
            "https://appassets.androidplatform.net/artifact/$id/%2e%2e/index.html",
            "https://appassets.androidplatform.net/artifact/$id/./index.html",
            "https://appassets.androidplatform.net:443/artifact/$id/index.html",
            "https://user@appassets.androidplatform.net/artifact/$id/index.html",
            "https://appassets.androidplatform.net/artifact/$id/index.html?x=1",
            "https://appassets.androidplatform.net.evil.com/artifact/$id/index.html",
            "file:///data/data/dev.locus/files/vault",
            "content://dev.locus.workspace/document/a",
            "wss://example.com/socket",
            "javascript:alert(1)",
            "not a url",
            "",
        ).forEach { assertEquals(Decision.Block, ArtifactSandbox.decide(it, id), it) }
    }

    @Test
    fun `links are offered only for http(s) taps`() {
        assertEquals("https://example.com/a", ArtifactSandbox.linkToOffer("https://example.com/a", userGesture = true))
        assertEquals("http://example.com", ArtifactSandbox.linkToOffer("http://example.com", userGesture = true))
        assertNull(ArtifactSandbox.linkToOffer("https://example.com/a", userGesture = false))
        assertNull(ArtifactSandbox.linkToOffer("intent://scan#Intent;end", userGesture = true))
        assertNull(ArtifactSandbox.linkToOffer("javascript:alert(1)", userGesture = true))
        assertNull(ArtifactSandbox.linkToOffer("file:///sdcard/x", userGesture = true))
        assertNull(ArtifactSandbox.linkToOffer("https://appassets.androidplatform.net/artifact/x/index.html", userGesture = true))
        assertNull(ArtifactSandbox.linkToOffer("https://example.com/" + "a".repeat(3000), userGesture = true))
    }

    @Test
    fun `the policy blocks the network and keeps data and blob`() {
        val csp = ArtifactSandbox.CONTENT_CSP
        assertTrue(csp.startsWith("default-src 'none'"))
        assertTrue("connect-src data: blob:" in csp)
        assertTrue("frame-src 'none'" in csp)
        assertTrue("form-action 'none'" in csp)
        assertFalse("http" in csp, "no network source anywhere")
        assertTrue(ArtifactSandbox.CONTENT_HEADER_CSP.endsWith("sandbox allow-scripts"))
        assertEquals("no-store", ArtifactSandbox.headers(csp)["Cache-Control"])
    }

    @Test
    fun `the served document puts the policy and prelude before the artifact`() {
        val doc = ArtifactSandbox.contentDocument(Artifact(ArtifactKind.Html, "\uFEFF<!DOCTYPE html><html><body><script>go()</script></body></html>"))
        val csp = doc.indexOf("Content-Security-Policy")
        val prelude = doc.indexOf("RTCPeerConnection")
        val script = doc.indexOf("go()")
        assertTrue(csp in 0 until prelude && prelude < script)
        assertEquals(1, Regex("(?i)<!doctype").findAll(doc).count())
        assertTrue("background:#fff" in doc, "a page with no dark styling gets white paper")
        val darkAware = ArtifactSandbox.contentDocument(Artifact(ArtifactKind.Html, "<style>@media (prefers-color-scheme: dark){body{background:#000}}</style>"))
        assertTrue("color-scheme:light dark" in darkAware)
    }

    @Test
    fun `svg drops its prologue and sits on white`() {
        val doc = ArtifactSandbox.contentDocument(Artifact(ArtifactKind.Svg, "<?xml version=\"1.0\"?>\n<!DOCTYPE svg>\n<svg/>"))
        assertFalse("<?xml" in doc)
        assertTrue("<body><svg/></body>" in doc)
    }

    @Test
    fun `the host page frames the artifact in a scripts-only sandbox`() {
        val host = ArtifactSandbox.hostDocument("<b>\"x\"</b>", "#101010")
        assertTrue("<iframe sandbox=\"allow-scripts\" src=\"content.html\"" in host)
        assertFalse("allow-same-origin" in host)
        assertFalse("<b>" in host)
        assertTrue("background:#101010" in host)
    }
}

class ArtifactLiveTest {
    @Test
    fun `one live preview at a time, evicting the holder first`() {
        val slot = ArtifactLiveSlot()
        val evicted = mutableListOf<String>()
        assertTrue(slot.claimIfFree("a") { evicted += "a" })
        assertFalse(slot.claimIfFree("b") { evicted += "b" })
        assertTrue(slot.isLive("a"))
        // A tap on b takes over; a is told first, so it can take its snapshot.
        assertEquals("a", slot.claim("b") { evicted += "b" })
        assertEquals(listOf("a"), evicted)
        assertTrue(slot.isLive("b"))
        // Releasing someone else's slot does nothing; releasing your own doesn't evict you.
        slot.release("a")
        assertEquals("b", slot.owner)
        slot.release("b")
        assertNull(slot.owner)
        assertEquals(listOf("a"), evicted)
    }

    @Test
    fun `claiming again keeps the slot without evicting yourself`() {
        val slot = ArtifactLiveSlot()
        var evictions = 0
        slot.claim("a") { evictions++ }
        assertNull(slot.claim("a") { evictions++ })
        assertEquals(0, evictions)
        slot.claim("b") {}
        assertEquals(1, evictions)
    }

    @Test
    fun `snapshots are bounded by bytes, least recently used out`() {
        val cache = SnapshotCache<ByteArray>(100) { it.size.toLong() }
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.get("a") // a is now the most recently used
        cache.put("c", ByteArray(40))
        assertEquals(listOf("a", "c"), cache.keys())
        assertEquals(80, cache.totalBytes)
        // Replacing an entry recounts it.
        cache.put("a", ByteArray(10))
        assertEquals(50, cache.totalBytes)
        // Too big for the whole budget: not kept, and nothing else is pushed out for it.
        cache.put("huge", ByteArray(101))
        assertNull(cache.get("huge"))
        assertEquals(2, cache.size)
        cache.remove("c")
        assertEquals(10, cache.totalBytes)
        cache.clear()
        assertEquals(0, cache.totalBytes)
    }

    @Test
    fun `snapshots are kept per theme`() {
        assertNotEquals(snapshotKey("k", dark = true), snapshotKey("k", dark = false))
    }
}
