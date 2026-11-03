package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where requests and keys go is sealed: a provider address, proxy, certificate or SSH host key changed outside Locus is caught. */
class EndpointSealTest {
    private val key = TestKey()
    private val anthropic = ProviderConfig("p1", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
    private val box = SshHost("h1", "box", "box.example", 22, "me", fingerprint = "SHA256:abc")
    private val settings = AppSettings(providers = listOf(anthropic), sshHosts = listOf(box))
    private val sealed = EndpointSeal.sealed(settings, key)

    @Test
    fun `sealed endpoints check out, and unrelated settings don't affect the seal`() {
        assertEquals(SealCheck.VALID, EndpointSeal.check(sealed, key))
        assertEquals(SealCheck.VALID, EndpointSeal.check(sealed.copy(customInstructions = "x", providers = listOf(anthropic.copy(model = "claude-haiku-5"))), key))
        assertEquals(SealCheck.DEFAULTS, EndpointSeal.check(AppSettings(), key))
    }

    @Test
    fun `a changed provider address, header, proxy or certificate breaks the seal`() {
        fun provider(change: (ProviderConfig) -> ProviderConfig) = sealed.copy(providers = listOf(change(anthropic)))
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(provider { it.copy(baseUrl = "https://relay.attacker.example") }, key))
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(provider { it.copy(network = it.network.copy(headerNames = listOf("x-api-key"), allowAuthOverride = true)) }, key))
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(provider { it.copy(network = it.network.copy(proxyMode = ProxyMode.CUSTOM, proxy = ProxyConfig(host = "mitm.example", port = 8080))) }, key))
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed.copy(network = NetworkDefaults(proxy = ProxyConfig(host = "mitm.example", port = 8080))), key))
        val ca = TrustedCertificate("c1", "-----BEGIN CERTIFICATE-----\nMITM\n-----END CERTIFICATE-----", "MITM CA", "AA:BB", 0, allProviders = true)
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed.copy(network = NetworkDefaults(certificates = listOf(ca))), key))
        // A provider added outside Locus too.
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed.copy(providers = listOf(anthropic, anthropic.copy(id = "p2", baseUrl = "https://evil.example"))), key))
    }

    @Test
    fun `a changed SSH host or host key breaks the seal`() {
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed.copy(sshHosts = listOf(box.copy(fingerprint = "SHA256:attacker"))), key))
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed.copy(sshHosts = listOf(box.copy(host = "attacker.example"))), key))
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed.copy(sshHosts = listOf(box.copy(port = 2222))), key))
    }

    @Test
    fun `an unsealed install is trusted only in the run that creates the key`() {
        // The update from a version without endpoint seals: the key is new.
        assertEquals(SealCheck.FIRST_USE, EndpointSeal.check(settings, TestKey(new = true)))
        // A seal removed later (the key already existed), or after this run already sealed: tampered.
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(settings, key))
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(settings, TestKey(new = true), firstUseAllowed = false))
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed, TestKey(broken = true)))
    }

    @Test
    fun `restricted endpoints keep the settings but send nothing unchecked`() {
        val tampered = sealed.copy(
            providers = listOf(
                anthropic.copy(baseUrl = "https://relay.attacker.example", network = ProviderNetwork(allowAuthOverride = true, proxyMode = ProxyMode.CUSTOM, proxy = ProxyConfig(host = "p", port = 1), certificateIds = listOf("c1"))),
                ProviderConfig("local", "Phone", ApiFormat.ON_DEVICE, "", "gemma"),
            ),
            network = NetworkDefaults(proxy = ProxyConfig(host = "p", port = 1)),
        )
        val safe = EndpointSeal.restricted(tampered)
        val p = safe.providers.first()
        assertTrue(p.addressUnconfirmed)
        assertEquals("https://relay.attacker.example", p.baseUrl)
        assertEquals(ProviderNetwork(proxyMode = ProxyMode.DIRECT), p.network)
        assertEquals(false, safe.providers[1].addressUnconfirmed)
        assertEquals(NetworkDefaults(), safe.network)
        assertEquals("", safe.sshHosts.single().fingerprint)
        // Once resealed, flipping the flag back outside Locus is caught too.
        val resealed = EndpointSeal.sealed(safe, key)
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(resealed.copy(providers = resealed.providers.map { it.copy(addressUnconfirmed = false) }), key))
    }

    @Test
    fun `the permissions seal is unchanged by endpoints, so an update doesn't trip it`() {
        val granted = AppSettings(toolPolicies = mapOf("shell" to ToolPolicy.ALLOW))
        val seal = PolicySeal.seal(granted, key)
        assertEquals(seal, PolicySeal.seal(granted.copy(providers = listOf(anthropic), sshHosts = listOf(box), endpointSeal = "e1:x"), key))
    }
}
