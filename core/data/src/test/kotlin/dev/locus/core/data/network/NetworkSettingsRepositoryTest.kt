package dev.locus.core.data.network

import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderNetwork
import dev.locus.core.model.ProxyConfig
import dev.locus.core.model.ProxyMode
import dev.locus.core.model.ProxyType
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkSettingsRepositoryTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/network/$name")!!.readBytes()

    @Test
    fun `certificates import once, and removing one clears it from providers`() = runTest {
        val graph = TestGraph(backgroundScope)
        val repo = NetworkSettingsRepository(graph.context, graph.settingsStore, graph.secrets)
        assertEquals(1, repo.importCertificates(resource("test-ca.pem")))
        assertEquals(0, repo.importCertificates(resource("test-ca.pem")), "the same certificate twice")
        assertEquals(2, repo.importCertificates(resource("other-ca.pem") + resource("impostor-ca.pem")))
        assertFailsWith<IllegalArgumentException> { repo.importCertificates("not a cert".toByteArray()) }

        val certs = repo.defaults.first().certificates
        assertEquals(listOf("Locus Test CA", "Other CA", "Locus Test CA"), certs.map { it.subject })
        val id = certs.first().id
        graph.settingsRepository.saveProvider(ProviderConfig("p", "P", ApiFormat.OPENAI_CHAT, "https://x", "m", network = ProviderNetwork(certificateIds = listOf(id))), null)
        assertEquals(mapOf(id to listOf("P")), repo.providersByCertificate.first().filterValues { it.isNotEmpty() })

        repo.setTrustedByAll(id, true)
        assertTrue(repo.defaults.first().certificates.first().allProviders)
        repo.removeCertificate(id)
        assertTrue(repo.defaults.first().certificates.none { it.id == id })
        assertTrue(graph.settingsRepository.current().providers.single().network.certificateIds.isEmpty())
    }

    @Test
    fun `the default proxy's credentials live in the vault and go when the proxy does`() = runTest {
        val graph = TestGraph(backgroundScope)
        val repo = NetworkSettingsRepository(graph.context, graph.settingsStore, graph.secrets)
        val key = SecretStore.proxyCredentials(SecretStore.DEFAULT_PROXY_SCOPE)

        repo.setDefaultProxy(ProxyConfig(ProxyType.SOCKS5, " socks.corp ", 1080, hasCredentials = true), "alice" to "pw-123456")
        assertEquals(ProxyConfig(ProxyType.SOCKS5, "socks.corp", 1080, hasCredentials = true), repo.defaults.first().proxy)
        assertFalse("pw-123456" in graph.settingsStore.value.toString())
        assertEquals(ProxyCredentials("alice", "pw-123456"), ProviderHttpClients.readProxyCredentials(graph.secrets.get(key)))
        assertTrue(SavedNetworkSecrets.DEFAULT_SCOPE in repo.saved.first().proxyScopes)

        // Saving again without typing keeps them; turning the proxy off removes them.
        repo.setDefaultProxy(ProxyConfig(ProxyType.SOCKS5, "socks.corp", 1081, hasCredentials = true), null)
        assertEquals(ProxyCredentials("alice", "pw-123456"), ProviderHttpClients.readProxyCredentials(graph.secrets.get(key)))
        repo.setDefaultProxy(null, null)
        assertNull(repo.defaults.first().proxy)
        assertNull(graph.secrets.get(key))
    }

    @Test
    fun `a provider's own proxy credentials are saved with it and removed with it`() = runTest {
        val graph = TestGraph(backgroundScope)
        val settings = graph.settingsRepository
        val provider = ProviderConfig(
            "p", "P", ApiFormat.OPENAI_CHAT, "https://x", "m",
            network = ProviderNetwork(proxyMode = ProxyMode.CUSTOM, proxy = ProxyConfig(ProxyType.HTTP, "proxy", 3128, hasCredentials = true)),
        )
        settings.saveProvider(provider, null, NetworkSecretEdits(proxyCredentials = "bob" to "pw-abcdef"))
        assertEquals(ProxyCredentials("bob", "pw-abcdef"), ProviderHttpClients.readProxyCredentials(graph.secrets.get(SecretStore.proxyCredentials("p"))))
        assertEquals(setOf("p"), settings.networkSecrets.first().proxyScopes)

        // An invalid custom proxy is stored as Default, and its credentials are dropped.
        settings.saveProvider(provider.copy(network = provider.network.copy(proxy = ProxyConfig(ProxyType.HTTP, "", 0, hasCredentials = true))), null)
        assertEquals(ProxyMode.DEFAULT, settings.current().providers.single().network.proxyMode)
        assertNull(graph.secrets.get(SecretStore.proxyCredentials("p")))

        settings.saveProvider(provider, null, NetworkSecretEdits(proxyCredentials = "bob" to "pw-abcdef"))
        settings.deleteProvider("p")
        assertNull(graph.secrets.get(SecretStore.proxyCredentials("p")))
    }
}
