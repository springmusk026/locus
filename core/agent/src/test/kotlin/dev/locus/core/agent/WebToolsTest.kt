package dev.locus.core.agent

import dev.locus.core.agent.tools.htmlToText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebToolsTest {
    @Test
    fun `html becomes readable text with absolute links`() {
        val html = """
            <html><head><title>Trip</title><script>evil()</script></head>
            <body><nav>menu</nav><main><h2>Pokhara</h2><p>See <a href="/lake">the lake</a>.</p>
            <ul><li>Boots</li><li>Jacket</li></ul></main><footer>copyright</footer></body></html>
        """.trimIndent()
        val text = htmlToText(html, "https://example.com/trips")
        assertTrue("# Trip" in text)
        assertTrue("## Pokhara" in text)
        assertTrue("[the lake](https://example.com/lake)" in text)
        assertTrue("- Boots" in text)
        assertFalse("evil" in text)
        assertFalse("menu" in text)
        assertFalse("copyright" in text)
    }
}
