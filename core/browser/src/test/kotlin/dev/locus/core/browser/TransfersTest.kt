package dev.locus.core.browser

import org.junit.Test
import kotlin.test.assertEquals
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
}
