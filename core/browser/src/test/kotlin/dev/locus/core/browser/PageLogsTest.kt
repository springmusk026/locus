package dev.locus.core.browser

import dev.locus.core.browser.PageLogs.Entry
import dev.locus.core.browser.PageLogs.Kind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageLogsTest {
    private val sample = listOf(
        Entry(Kind.CONSOLE, "log", "App started (main.js:1)"),
        Entry(Kind.CONSOLE, "error", "TypeError: x is undefined (main.js:42)"),
        Entry(Kind.NETWORK, "error", "GET http://localhost:3000/api/items → 500 Internal Server Error"),
    )

    @Test
    fun filtersByKindAndSeverity() {
        assertEquals(3, PageLogs.format(sample, 0, "all", false).lines().size)
        assertEquals("[error] TypeError: x is undefined (main.js:42)", PageLogs.format(sample, 0, "console", true))
        assertEquals("[network] GET http://localhost:3000/api/items → 500 Internal Server Error", PageLogs.format(sample, 0, "network", false))
    }

    @Test
    fun saysSoWhenEmpty() {
        assertEquals("No error console messages since this page loaded.", PageLogs.format(sample.take(1), 0, "console", true))
        assertEquals("No failed requests since this page loaded.", PageLogs.format(emptyList(), 0, "network", false))
    }

    @Test
    fun ringDropsOldestAndSaysSo() {
        val logs = PageLogs(capacity = 2)
        sample.forEach(logs::add)
        val (entries, dropped) = logs.snapshot()
        assertEquals(sample.drop(1), entries)
        assertTrue(PageLogs.format(entries, dropped, "all", false).startsWith("(older entries not shown)"))
        logs.clear()
        assertEquals(emptyList(), logs.snapshot().first)
    }

    @Test
    fun savePaths() {
        assertEquals("browser/example.com-20261031-120000.md", SavePaths.default("https://www.example.com/a?b", "markdown", "20261031-120000"))
        assertEquals("browser/localhost-1.png", SavePaths.default("http://localhost:3000/", "png", "1"))
        assertEquals("notes/page.mhtml", SavePaths.withExtension("notes/page", "mhtml"))
        assertEquals("notes/page.html", SavePaths.withExtension("notes/page.html", "markdown"))
    }
}
