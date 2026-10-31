package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeaderRulesTest {
    @Test
    fun `gateway headers are allowed`() {
        listOf("HTTP-Referer", "X-Title", "cf-aig-authorization", "cf-aig-cache-ttl", "X-Corp-Token", "OpenAI-Organization").forEach {
            assertEquals(HeaderVerdict.Allowed, HeaderRules.check(it), it)
            assertTrue(HeaderRules.usable(it, allowAuthOverride = false))
        }
    }

    @Test
    fun `hop-by-hop, framing and proxy headers are forbidden in any case`() {
        listOf(
            "Host", "content-length", "Content-Type", "Transfer-Encoding", "Connection", "Keep-Alive", "Upgrade", "TE", "Trailer",
            "Accept-Encoding", "Proxy-Authorization", "proxy-anything", "Sec-Fetch-Mode", "Expect",
        ).forEach {
            assertIs<HeaderVerdict.Forbidden>(HeaderRules.check(it), it)
            assertFalse(HeaderRules.usable(it, allowAuthOverride = true), it)
        }
    }

    @Test
    fun `invalid names are forbidden`() {
        listOf("", " ", "X Title", "X:Title", "X-Title\r\nHost", ":authority", "Ünicode").forEach {
            assertIs<HeaderVerdict.Forbidden>(HeaderRules.check(it), it)
        }
    }

    @Test
    fun `credential headers need the explicit override`() {
        listOf("Authorization", "x-api-key", "X-API-KEY", "api-key", "x-goog-api-key").forEach {
            val verdict = HeaderRules.check(it)
            assertIs<HeaderVerdict.Credential>(verdict, it)
            assertTrue(verdict.warning.isNotBlank())
            assertFalse(HeaderRules.usable(it, allowAuthOverride = false), it)
            assertTrue(HeaderRules.usable(it, allowAuthOverride = true), it)
        }
    }

    @Test
    fun `values with line breaks or outside Latin-1 are refused`() {
        assertNull(HeaderRules.checkValue("Bearer abc.def"))
        assertNotNull(HeaderRules.checkValue(""))
        assertNotNull(HeaderRules.checkValue("a\r\nHost: evil"))
        assertNotNull(HeaderRules.checkValue("a\nb"))
        assertNotNull(HeaderRules.checkValue("emoji 😀"))
        assertNotNull(HeaderRules.checkValue("x".repeat(HeaderRules.MAX_VALUE_LENGTH + 1)))
    }

    @Test
    fun `proxy settings validate host and port`() {
        assertTrue(ProxyConfig(ProxyType.HTTP, "proxy.corp", 8080).isValid)
        assertFalse(ProxyConfig(ProxyType.HTTP, "", 8080).isValid)
        assertFalse(ProxyConfig(ProxyType.SOCKS5, "proxy.corp", 0).isValid)
        assertFalse(ProxyConfig(ProxyType.SOCKS5, "user@proxy", 1080).isValid)
        assertFalse(ProxyConfig(ProxyType.HTTP, "http://proxy", 8080).isValid)
    }

    @Test
    fun `old settings without network fields still load, and the stored form holds no values`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"providers":[{"id":"a","name":"A","format":"OPENAI_CHAT","baseUrl":"https://x","model":"m"}]}"""
        val settings = json.decodeFromString(AppSettings.serializer(), old)
        assertTrue(settings.providers.single().network.isDefault)
        assertEquals(NetworkDefaults(), settings.network)

        val withHeaders = settings.copy(providers = listOf(settings.providers.single().copy(network = ProviderNetwork(headerNames = listOf("X-Title")))))
        val stored = json.encodeToString(AppSettings.serializer(), withHeaders)
        assertTrue("X-Title" in stored)
    }
}
