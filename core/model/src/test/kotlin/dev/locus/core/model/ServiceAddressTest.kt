package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A self-hosted server's address: https by default, http only on this phone or the LAN, with a warning. */
class ServiceAddressTest {
    private fun ok(input: String) = ServiceAddress.check(input).also { assertNull(it.error, "$input: ${it.error}") }
    private fun refused(input: String) = ServiceAddress.check(input).also { assertNull(it.url, input); assertNotNull(it.error, input) }

    @Test
    fun `https is the default and the address is tidied`() {
        assertEquals("https://rss.example.com", ok("rss.example.com").url)
        assertEquals("https://rss.example.com", ok("  https://RSS.example.com/  ").url)
        assertEquals("https://home.example.com:8443/miniflux", ok("https://home.example.com:8443/miniflux/").url)
        assertEquals("https://homeassistant.local:8123", ok("homeassistant.local:8123").url)
        assertNull(ok("https://rss.example.com").warning)
    }

    @Test
    fun `http is allowed only for loopback and the local network, with a warning`() {
        for (lan in listOf("http://192.168.1.20:8123", "http://10.0.0.5", "http://172.20.1.1/rss", "http://homeassistant.local:8123", "http://nas:8080", "http://[fd00::5]:8123")) {
            val c = ok(lan)
            assertEquals(ServiceAddress.LAN_HTTP_WARNING, c.warning, lan)
        }
        for (loop in listOf("http://localhost:8080", "http://127.0.0.1:2283", "http://[::1]:8000")) {
            assertNotNull(ok(loop).warning, loop)
        }
        for (public in listOf("http://rss.example.com", "http://8.8.8.8", "http://[2001:db8::1]")) {
            assertTrue("https" in refused(public).error.orEmpty(), public)
        }
    }

    @Test
    fun `other schemes and odd addresses are refused`() {
        listOf(
            "ftp://rss.example.com", "file:///etc/passwd", "javascript:alert(1)", "mailto:me@example.com", "ws://ha.local:8123",
            "", "   ", "https://", "https://user:pass@rss.example.com", "https://rss.example.com/?token=1", "https://rss.example.com/#x",
            "https://rss example.com", "https://rss.example.com:99999",
        ).forEach { refused(it) }
    }

    @Test
    fun `a template's host placeholder takes the checked address`() {
        assertEquals("https://rss.example.com/v1", ServiceAddress.fill("{host}/v1", "https://rss.example.com/"))
        assertEquals("http://192.168.1.2:8080/sub/api", ServiceAddress.fill("{host}/api", ok("http://192.168.1.2:8080/sub").url!!))
    }
}
