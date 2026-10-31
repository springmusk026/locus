package dev.locus.feature.settings

import dev.locus.core.model.NetworkDefaults
import dev.locus.core.model.ProviderNetwork
import dev.locus.core.model.ProxyConfig
import dev.locus.core.model.ProxyMode
import dev.locus.core.model.ProxyType
import dev.locus.core.model.TrustedCertificate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkSettingsTest {
    private val cert = TrustedCertificate("c1", "pem", "Corp CA", "AB:CD", notAfter = 0)

    @Test
    fun `summaries name the proxy and count certificates`() {
        val defaults = NetworkDefaults(proxy = ProxyConfig(ProxyType.SOCKS5, "s", 1080), certificates = listOf(cert))
        assertEquals("No proxy", networkSummary(ProviderNetwork(), NetworkDefaults()))
        assertEquals("Default proxy", networkSummary(ProviderNetwork(), defaults))
        assertEquals(
            "2 headers · No proxy · 1 certificate",
            networkSummary(ProviderNetwork(headerNames = listOf("A", "B"), proxyMode = ProxyMode.DIRECT, certificateIds = listOf("c1")), defaults),
        )
        assertEquals("SOCKS5 proxy · 1 certificate", networkHubSummary(defaults))
        assertEquals("Direct", networkHubSummary(NetworkDefaults()))
        assertEquals("Not used by any provider yet", certificateUse(cert, emptyList()))
        assertEquals("Trusted by every provider", certificateUse(cert.copy(allProviders = true), listOf("A")))
    }

    @Test
    fun `the draft keeps values out of the settings and asks before replacing the key header`() {
        val draft = NetworkDraft(ProviderNetwork())
        draft.headers.add(HeaderDraft("X-Title", "Locus"))
        draft.headers.add(HeaderDraft("Authorization", "Bearer gw-123"))
        assertNotNull(draft.problem, "a credential header needs the explicit override")
        draft.allowAuthOverride = true
        assertNull(draft.problem)

        val network = draft.network()
        assertEquals(listOf("X-Title", "Authorization"), network.headerNames)
        assertTrue(network.allowAuthOverride)
        assertFalse("Locus" in network.toString() || "gw-123" in network.toString())
        assertEquals(mapOf("X-Title" to "Locus", "Authorization" to "Bearer gw-123"), draft.secrets().headerValues)
        assertFalse("gw-123" in draft.secrets().toString())
    }

    @Test
    fun `a custom proxy needs a host and port`() {
        val draft = NetworkDraft(ProviderNetwork())
        draft.proxyMode = ProxyMode.CUSTOM
        assertNotNull(draft.problem)
        draft.proxyHost = "proxy.corp"
        draft.proxyPort = "3128"
        draft.proxyAuth = true
        draft.proxyUser = "alice"
        draft.proxyPassword = "pw"
        assertNull(draft.problem)
        assertEquals(ProxyConfig(ProxyType.HTTP, "proxy.corp", 3128, hasCredentials = true), draft.network().proxy)
        assertEquals("alice" to "pw", draft.secrets().proxyCredentials)
    }
}
