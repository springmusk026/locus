package dev.locus.core.browser

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The store behind "See the page" (docs/design/browser.md, "The page as it was"): what it keeps, for how long, and where. */
class BrowserPagesTest {
    private val root: File = Files.createTempDirectory("pages").toFile()

    private fun record(text: String = "Page: Shop\nURL: https://shop.example/\n\n[e1] button \"Buy\"", at: Long = 1L) =
        PageRecord(url = "https://shop.example/", title = "Shop", at = at, text = text)

    @Test
    fun aCallsPageComesBackWithItsPicture() {
        val pages = BrowserPages(root)
        pages.save("chat-1", "toolu_01", record(), jpeg = byteArrayOf(1, 2, 3))
        val back = assertNotNull(pages.load("chat-1", "toolu_01"))
        assertEquals("Shop", back.title)
        assertTrue(back.picture)
        assertEquals(3L, pages.pictureOf("chat-1", "toolu_01")?.length())
        assertTrue(pages.has("chat-1", "toolu_01"))
        // Another chat's call with the same id is another record.
        assertNull(pages.load("chat-2", "toolu_01"))
    }

    @Test
    fun aRecordWithoutAPictureDropsAnOldOne() {
        val pages = BrowserPages(root)
        pages.save("chat-1", "c1", record(), jpeg = byteArrayOf(1))
        pages.save("chat-1", "c1", record(text = "Page: Next\nURL: https://shop.example/next"))
        assertFalse(assertNotNull(pages.load("chat-1", "c1")).picture)
        assertNull(pages.pictureOf("chat-1", "c1"))
    }

    @Test
    fun textIsCapped() {
        val pages = BrowserPages(root)
        pages.save("chat-1", "c1", record(text = "x".repeat(BrowserPages.MAX_TEXT + 500)))
        assertEquals(BrowserPages.MAX_TEXT, pages.load("chat-1", "c1")?.text?.length)
    }

    @Test
    fun eachChatKeepsItsNewestCalls() {
        val pages = BrowserPages(root, perChat = 3)
        (1..5).forEach { i ->
            pages.save("chat-1", "c$i", record())
            File(root, "chat-1/c$i.json").setLastModified(i * 10_000L)
        }
        pages.prune()
        assertEquals(listOf(false, false, true, true, true), (1..5).map { pages.has("chat-1", "c$it") })
    }

    @Test
    fun theOldestGoFirstWhenAllChatsTogetherAreTooBig() {
        val pages = BrowserPages(root, maxBytes = 2_500)
        listOf("a" to 1L, "b" to 2L, "c" to 3L).forEach { (chat, t) ->
            pages.save(chat, "call", record(), jpeg = ByteArray(1_000))
            File(root, "$chat/call.json").setLastModified(t * 10_000L)
        }
        pages.prune()
        assertFalse(pages.has("a", "call"))
        assertTrue(pages.has("b", "call") && pages.has("c", "call"))
        // A chat left with nothing has no folder.
        assertFalse(File(root, "a").exists())
    }

    @Test
    fun deletingAChatDeletesItsPages() {
        val pages = BrowserPages(root)
        pages.save("chat-1", "c1", record(), jpeg = byteArrayOf(1))
        pages.save("helper-1", "c2", record())
        pages.save("chat-2", "c3", record())
        pages.forget(listOf("chat-1", "helper-1"))
        assertNull(pages.load("chat-1", "c1"))
        assertNull(pages.load("helper-1", "c2"))
        assertNotNull(pages.load("chat-2", "c3"))
    }

    @Test
    fun idsThatArentPlainNamesAreNeverPaths() {
        val pages = BrowserPages(File(root, "store"))
        listOf("../x", "..", ".", "a/b", "", "x".repeat(200)).forEach { bad ->
            pages.save(bad, "c1", record())
            pages.save("chat", bad, record())
            assertNull(pages.load(bad, "c1"))
            assertNull(pages.load("chat", bad))
        }
        assertFalse(File(root, "x").exists())
        assertTrue(File(root, "store").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aDamagedRecordReadsAsNone() {
        val pages = BrowserPages(root)
        File(root, "chat-1").mkdirs()
        File(root, "chat-1/c1.json").writeText("{not json")
        assertNull(pages.load("chat-1", "c1"))
        assertNull(PageRecord.fromJson("""{"title":"no url or text"}"""))
    }

    @Test
    fun theStoredShapeReadsBack() {
        val r = PageRecord("https://a.example/", "A", 42L, "text", picture = true, pictureWidth = 720, pictureHeight = 1400)
        assertEquals(r, PageRecord.fromJson(r.toJson()))
    }

    @Test
    fun aStepsOwnResultNamesThePageWhenNothingWasKept() {
        val result = "<untrusted_web_content>\nEvent: Opened a new tab\n\nPage: Hotel X\nURL: https://booking.com/x\nScroll: 10% of page\n</untrusted_web_content>"
        assertEquals("Hotel X" to "https://booking.com/x", BrowserPages.headerOf(result))
        assertNull(BrowserPages.headerOf("No page is open. Use browser_open first."))
    }
}
