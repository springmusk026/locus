package dev.locus.core.agent

import dev.locus.core.agent.publish.PublishPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The safe parts of a published page (docs/design/publish.md). */
class PublishPageTest {

    @Test
    fun `escape leaves no markup`() {
        assertEquals("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &#39;", PublishPage.escape("<script>alert(\"x\")</script> & '"))
    }

    @Test
    fun `sanitize strips scripts, handlers, frames and javascript links`() {
        val html = PublishPage.sanitize(
            "<p onclick=\"steal()\">hi</p><script>alert(1)</script><iframe src=\"https://e.com\"></iframe>" +
                "<a href=\"javascript:alert(1)\">x</a><a href=\"https://ok.example/\">ok</a><style>body{}</style><form action=\"/x\"><input></form>" +
                "<svg onload=\"alert(1)\"></svg><img src=\"x\" onerror=\"alert(1)\">",
        )
        listOf("<script", "onclick", "onerror", "onload", "<iframe", "javascript:", "<style", "<form", "<input", "<svg").forEach {
            assertFalse(it in html, "$it in $html")
        }
        assertTrue("<a href=\"https://ok.example/\" rel=\"nofollow noopener noreferrer\">ok</a>" in html, html)
    }

    @Test
    fun `only data images stay pictures, anything else becomes its alt text`() {
        val html = PublishPage.sanitize("<img src=\"https://tracker.example/p.gif\" alt=\"chart\"><img src=\"data:image/png;base64,iVBORw0KGgo=\" alt=\"a\">")
        assertFalse("tracker.example" in html)
        assertTrue("[chart]" in html)
        assertTrue("<img src=\"data:image/png;base64,iVBORw0KGgo=\"" in html, html)
    }

    @Test
    fun `a chat page has no scripts and no network in its policy`() {
        val doc = PublishPage.chatDocument("<b>Title</b>", "today", "<p>body</p>")
        assertTrue("default-src 'none'" in doc && "img-src data:" in doc)
        assertFalse("script-src" in doc || "<script" in doc)
        assertTrue("<title>&lt;b&gt;Title&lt;/b&gt;</title>" in doc)
        assertTrue("prefers-color-scheme:dark" in doc)
    }

    @Test
    fun `scrubber counts vault values and key shapes it removed`() {
        val scrubber = PublishPage.Scrubber(listOf("hunter2-vault-value"))
        val out = scrubber.scrub("pw hunter2-vault-value and sk-abcdefghijklmnopqrstuv and eyJhbGciOiJI.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2Q, keep [secret] as it was")
        assertEquals(3, out.removed)
        assertFalse("hunter2-vault-value" in out.text || "sk-abc" in out.text || "eyJhbGci" in out.text)
        assertEquals(PublishPage.Scrubbed("nothing here", 0), scrubber.scrub("nothing here"))
    }

    @Test
    fun `a page that still holds a vault value is blocked, in any encoding`() {
        val vault = listOf("p&ss<word>123")
        assertTrue(PublishPage.leaksVaultValue(listOf("<p>p&ss<word>123</p>"), vault))
        assertTrue(PublishPage.leaksVaultValue(listOf("<p>p&amp;ss&lt;word&gt;123</p>"), vault))
        assertTrue(PublishPage.leaksVaultValue(listOf("<a href=\"?q=p%26ss%3Cword%3E123\">"), vault))
        assertFalse(PublishPage.leaksVaultValue(listOf("<p>[secret]</p>"), vault))
        // A scrubbed page passes.
        val scrubbed = PublishPage.Scrubber(vault).scrub("the key is p&ss<word>123")
        assertFalse(PublishPage.leaksVaultValue(listOf(PublishPage.escape(scrubbed.text)), vault))
    }

    @Test
    fun `an artifact goes as it is when it's a document, wrapped when it isn't`() {
        val doc = "<!DOCTYPE html><html><body><script>draw()</script></body></html>"
        assertEquals(doc, PublishPage.artifactDocument(doc, svg = false, title = "Chart"))
        val fragment = PublishPage.artifactDocument("<canvas></canvas>", svg = false, title = "A <chart>")
        assertTrue(fragment.startsWith("<!doctype html>") && "<title>A &lt;chart&gt;</title>" in fragment && "<canvas></canvas>" in fragment)
        val svg = PublishPage.artifactDocument("<svg viewBox=\"0 0 1 1\"></svg>", svg = true, title = "Logo")
        assertTrue("<body>\n<svg" in svg && "max-width:100%" in svg)
    }
}
