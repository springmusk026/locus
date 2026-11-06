package dev.locus.core.browser

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** What the address bar opens for what the user typed, and which hosts that names. */
class AddressInputTest {
    private fun parse(text: String) = AddressInput.parse(text)!!

    @Test
    fun `a bare host opens over https and names that host`() {
        assertEquals(AddressInput.Target("https://github.com/springmusk026", setOf("github.com")), parse("  github.com/springmusk026 "))
        assertEquals(setOf("example.com"), parse("www.example.com").named)
        assertEquals("https://shop.example.co.uk:8443/cart?x=1", parse("shop.example.co.uk:8443/cart?x=1").url)
    }

    @Test
    fun `a full address opens as typed`() {
        assertEquals(AddressInput.Target("http://example.org/a", setOf("example.org")), parse("http://example.org/a"))
        assertEquals("HTTPS://Example.org", parse("HTTPS://Example.org").url)
    }

    @Test
    fun `local servers open over http`() {
        assertEquals("http://localhost:3000/", parse("localhost:3000/").url)
        assertEquals("http://127.0.0.1:8080", parse("127.0.0.1:8080").url)
        assertEquals("http://[::1]:5173", parse("[::1]:5173").url)
    }

    @Test
    fun `words are a search, and name only the hosts in them`() {
        val search = parse("cheap flights to Lisbon")
        assertEquals("https://duckduckgo.com/?q=cheap+flights+to+Lisbon", search.url)
        assertEquals(emptySet(), search.named)
        assertEquals(setOf("bbc.co.uk"), parse("bbc.co.uk weather").named)
        // Not an address: a search, never a script or an app link.
        assertEquals(emptySet(), parse("javascript:alert(1)").named)
        assertEquals("https://duckduckgo.com/?q=javascript%3Aalert%281%29", parse("javascript:alert(1)").url)
    }

    @Test
    fun `other schemes are refused and blank does nothing`() {
        listOf("file:///sdcard/a.html", "intent://scan/#Intent;end", "ftp://example.com").forEach { text ->
            assertEquals(AddressInput.ONLY_WEB, assertFailsWith<IllegalArgumentException> { AddressInput.parse(text) }.message, text)
        }
        assertNull(AddressInput.parse("   "))
    }
}
