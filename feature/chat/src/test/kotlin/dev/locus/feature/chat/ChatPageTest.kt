package dev.locus.feature.chat

import dev.locus.core.agent.publish.PublishPage
import dev.locus.core.model.Part
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A chat as a published web page (docs/design/publish.md): escaping, pictures, code, steps and what stays out. */
class ChatPageTest {
    private val scrubber = PublishPage.Scrubber(listOf("vault-secret-123456"))

    private fun build(items: List<ChatItem>, tools: Boolean = false, image: (Part.Attachment) -> String? = { null }) =
        ChatPage.build("Fix <checkout> tests", "23 November 2026", items, PageLabels(), tools, scrubber, image)

    private fun body(html: String) = html.substringAfter("<main>")

    @Test
    fun `script in the user's text is text, not markup`() {
        val page = build(listOf(ChatItem.User("u", "<script>alert(1)</script> and <img src=x onerror=alert(2)>")))
        assertFalse("<script" in page.html)
        assertFalse("<img" in page.html)
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in body(page.html), page.html)
        assertTrue("<title>Fix &lt;checkout&gt; tests</title>" in page.html)
    }

    @Test
    fun `raw html and javascript links in a reply's markdown never become markup`() {
        val reply = """
            Here is **bold** and a [link](https://example.com) and a [trap](javascript:alert(1)).

            <div onmouseover="steal()"><script>fetch('https://evil.example')</script></div>

            Inline <b onclick="x()">tag</b> and ![pixel](https://tracker.example/p.gif)
        """.trimIndent()
        val html = body(build(listOf(ChatItem.Assistant("a", reply))).html)
        assertTrue("<strong>bold</strong>" in html, html)
        assertTrue("href=\"https://example.com\"" in html)
        listOf("<script", "javascript:", "<div onmouseover", "tracker.example", "<b ").forEach { assertFalse(it in html, "$it in $html") }
        // No tag anywhere carries an event handler: the handlers above are only text now.
        assertFalse(Regex("<[a-z][^<>]*\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html), html)
        assertTrue("&lt;script&gt;" in html && "&lt;b onclick" in html, html)
        assertFalse("<img" in html)
    }

    @Test
    fun `code blocks keep their text escaped`() {
        val html = body(build(listOf(ChatItem.Assistant("a", "```html\n<h1>if (a < b && c)</h1>\n```"))).html)
        assertTrue("<pre><code>" in html, html)
        assertTrue("&lt;h1&gt;if (a &lt; b &amp;&amp; c)&lt;/h1&gt;" in html, html)
    }

    @Test
    fun `attached pictures go in as data urls and other files by name`() {
        val png = Files.createTempFile("shot", ".png").toFile().apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }
        val items = listOf(
            ChatItem.User(
                "u", "look",
                attachments = listOf(
                    Part.Attachment("shot.png", "image/png", png.path, "uploads/shot.png", 4),
                    Part.Attachment("notes.pdf", "application/pdf", "/nowhere/notes.pdf", "uploads/notes.pdf", 10),
                    Part.Attachment("big.png", "image/png", File(png.parentFile, "missing.png").path, "uploads/big.png", 10),
                ),
            ),
        )
        val page = build(items, image = { PublishViewModel.dataUrl(it) })
        assertEquals(1, page.images)
        assertTrue("<img src=\"data:image/png;base64,iVBORw==\" alt=\"shot.png\">" in page.html, page.html)
        assertTrue("Attached: notes.pdf" in page.html)
    }

    @Test
    fun `tool calls are one line each and their results stay out unless asked`() {
        val call = ChatItem.ToolCall("t1", "run_command", """{"command":"cat /secret/notes.txt"}""", Part.ToolResult("t1", "run_command", "TOP-SECRET-OUTPUT"))
        val memory = ChatItem.ToolCall("t2", "memory_read", """{"topic":"me"}""", Part.ToolResult("t2", "memory_read", "I live at 1 Private Road"))
        val items = listOf(ChatItem.User("u", "go"), call, memory, ChatItem.Assistant("a", "Done."))

        val folded = build(items).html
        assertTrue("<ul class=\"steps\">" in folded)
        assertFalse("TOP-SECRET-OUTPUT" in folded || "Private Road" in folded)

        val open = build(items, tools = true).html
        assertTrue("<details><summary>" in open && "TOP-SECRET-OUTPUT" in open, open)
        // Memory never goes on a page, even with tool results on.
        assertFalse("Private Road" in open)
        assertTrue("Memory isn" in open)
    }

    @Test
    fun `reasoning stays private and vault values are scrubbed and counted`() {
        val items = listOf(
            ChatItem.User("u", "my key is vault-secret-123456"),
            ChatItem.Thought("th", "PRIVATE-REASONING", 1_000, inSteps = false),
            ChatItem.Assistant("a", "Saved vault-secret-123456 for you"),
        )
        val page = build(items)
        assertEquals(2, page.removed)
        assertFalse("vault-secret-123456" in page.html || "PRIVATE-REASONING" in page.html)
        assertFalse(PublishPage.leaksVaultValue(listOf(page.html), listOf("vault-secret-123456")))
        assertTrue("[secret]" in page.html)
    }

    @Test
    fun `the page is complete, light and dark, with no script and no network`() {
        val html = build(listOf(ChatItem.User("u", "hi"), ChatItem.Assistant("a", "hello"))).html
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(PublishPage.CHAT_CSP in html)
        assertTrue("prefers-color-scheme:dark" in html)
        assertFalse("<script" in html || "http://" in html || "src=\"https" in html)
        assertTrue("<p class=\"who\">You</p>" in html && "<p class=\"who\">Locus</p>" in html)
    }
}
