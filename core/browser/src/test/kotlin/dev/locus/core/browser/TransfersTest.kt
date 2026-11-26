package dev.locus.core.browser

import com.sun.net.httpserver.HttpServer
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransfersTest {
    @Test
    fun namesCannotEscapeTheDownloadsFolder() {
        assertEquals("passwd", Transfers.sanitize("../../etc/passwd"))
        assertEquals("evil.sh", Transfers.sanitize("..\\..\\evil.sh"))
        assertEquals("env", Transfers.sanitize(".env"))
        assertEquals("a_b_.pdf", Transfers.sanitize("a:b?.pdf"))
        assertEquals("download", Transfers.sanitize("..."))
        val long = Transfers.sanitize("x".repeat(300) + ".pdf")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".pdf"))
    }

    @Test
    fun existingNamesGetANumber() {
        val taken = setOf("report.pdf", "report (2).pdf", "notes")
        assertEquals("report (3).pdf", Transfers.unique("report.pdf") { it in taken })
        assertEquals("notes (2)", Transfers.unique("notes") { it in taken })
        assertEquals("new.pdf", Transfers.unique("new.pdf") { it in taken })
    }

    @Test
    fun decodesDataUrls() {
        val (bytes, mime) = Transfers.decodeDataUrl("data:text/csv;base64,YSxiCjEsMgo=")!!
        assertEquals("a,b\n1,2\n", bytes.decodeToString())
        assertEquals("text/csv", mime)
        assertEquals("hi there+", Transfers.decodeDataUrl("data:,hi%20there+")!!.first.decodeToString())
        assertNull(Transfers.decodeDataUrl("https://example.com/a.pdf"))
        assertNull(Transfers.decodeDataUrl("data:text/plain;base64,@@@"))
    }

    @Test
    fun extensionsForCommonTypes() {
        assertEquals(".pdf", Transfers.extensionFor("application/pdf"))
        assertEquals(".csv", Transfers.extensionFor("text/csv; charset=utf-8"))
        assertEquals("", Transfers.extensionFor("application/x-unknown"))
    }

    // ---- Downloads over HTTP (#369) ---------------------------------------------------------------

    /** What one local server saw: each request's path, Cookie and Referer. */
    private class Seen(val path: String, val cookie: String?, val referer: String?)

    private fun server(handle: (path: String) -> Pair<Int, String?>): Pair<HttpServer, MutableList<Seen>> {
        val seen = Collections.synchronizedList(mutableListOf<Seen>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            seen += Seen(path, ex.requestHeaders.getFirst("Cookie"), ex.requestHeaders.getFirst("Referer"))
            val (code, location) = handle(path)
            location?.let { ex.responseHeaders.add("Location", it) }
            val body = if (code == 200) "file body".toByteArray() else ByteArray(0)
            ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
        server.start()
        return server to seen
    }

    /** Cookies as the profile's CookieManager would give them: only for the host they belong to. */
    private val cookieJar: (String) -> String? = { url -> if (java.net.URL(url).host == "127.0.0.1") "session=webmail-secret" else null }

    private fun download(url: String, referer: String? = null): String {
        val connection = Transfers.openDownload(url, "UA", referer, cookieJar)
        return try { connection.inputStream.use { it.readBytes().decodeToString() } } finally { connection.disconnect() }
    }

    @Test
    fun aCrossHostRedirectGetsNoCookies() {
        // "localhost" and "127.0.0.1" are different hosts for cookies: the attacker is the other one.
        val (evil, evilSeen) = server { 200 to null }
        val (site, siteSeen) = server { 302 to "http://localhost:${evil.address.port}/steal" }
        try {
            val body = download("http://127.0.0.1:${site.address.port}/attachment?id=1", referer = "http://127.0.0.1:${site.address.port}/inbox?token=abc#m1")
            assertEquals("file body", body)
            assertEquals("session=webmail-secret", siteSeen.single().cookie)
            val stolen = evilSeen.single()
            assertEquals("/steal", stolen.path)
            assertNull(stolen.cookie)
            // Only the page's origin, never its path or query (which can hold tokens).
            assertEquals("http://127.0.0.1:${site.address.port}/", stolen.referer)
            assertEquals("http://127.0.0.1:${site.address.port}/", siteSeen.single().referer)
        } finally {
            site.stop(0)
            evil.stop(0)
        }
    }

    @Test
    fun aSameHostRedirectKeepsTheHostsCookies() {
        val (site, seen) = server { path -> if (path == "/a") 301 to "/b" else 200 to null }
        try {
            assertEquals("file body", download("http://127.0.0.1:${site.address.port}/a"))
            assertEquals(listOf("/a", "/b"), seen.map { it.path })
            assertTrue(seen.all { it.cookie == "session=webmail-secret" })
            assertTrue(seen.all { it.referer == null })
        } finally {
            site.stop(0)
        }
    }

    @Test
    fun redirectLoopsAndErrorsFail() {
        val (site, _) = server { path -> if (path == "/missing") 404 to null else 302 to "/loop" }
        try {
            assertTrue("more than" in assertFailsWith<IOException> { download("http://127.0.0.1:${site.address.port}/loop") }.message!!)
            assertTrue("HTTP 404" in assertFailsWith<IOException> { download("http://127.0.0.1:${site.address.port}/missing") }.message!!)
        } finally {
            site.stop(0)
        }
    }

    @Test
    fun onlyWebLinksAndOrigins() {
        assertFailsWith<IOException> { Transfers.openDownload("file:///etc/passwd", null, null, cookieJar) }
        assertEquals("https://mail.example/", Transfers.originOf("https://mail.example/inbox?token=abc#x"))
        assertEquals("http://h.example:8080/", Transfers.originOf("http://user:pw@h.example:8080/p"))
        assertEquals("https://h.example/", Transfers.originOf("https://h.example:443/p"))
        assertNull(Transfers.originOf("about:blank"))
        val page = java.net.URL("https://mail.example/a")
        assertEquals("https://cdn.example/f.pdf", Transfers.nextHop(page, "https://cdn.example/f.pdf").toString())
        assertEquals("https://mail.example/b", Transfers.nextHop(page, "/b").toString())
        assertTrue("plain http" in assertFailsWith<IOException> { Transfers.nextHop(page, "http://mail.example/b") }.message!!)
        assertFailsWith<IOException> { Transfers.nextHop(page, "javascript:alert(1)") }
    }
}
