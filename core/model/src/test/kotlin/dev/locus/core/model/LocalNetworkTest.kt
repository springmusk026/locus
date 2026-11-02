package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkTest {
    private fun local(vararg targets: String) = targets.forEach { assertTrue(LocalNetwork.isLocalTarget(it), it) }
    private fun notLocal(vararg targets: String) = targets.forEach { assertFalse(LocalNetwork.isLocalTarget(it), it) }

    @Test
    fun `rfc1918 ranges are local`() {
        local("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.1.20")
        notLocal("172.15.0.1", "172.32.0.1", "192.169.0.1", "11.0.0.1")
    }

    @Test
    fun `link-local, CGNAT, multicast and broadcast are local`() {
        local("169.254.10.10", "100.64.0.1", "100.127.255.254", "224.0.0.251", "239.255.255.250", "255.255.255.255")
        notLocal("100.63.255.255", "100.128.0.1", "169.255.0.1")
    }

    @Test
    fun `loopback and unspecified addresses are not`() {
        notLocal("127.0.0.1", "127.1.2.3", "localhost", "LOCALHOST", "app.localhost", "::1", "[::1]", "0.0.0.0", "::")
        notLocal("http://localhost:8080/callback", "http://127.0.0.1:1455/auth/callback", "http://[::1]:3000")
    }

    @Test
    fun `public addresses and names are not`() {
        notLocal("8.8.8.8", "api.anthropic.com", "https://api.openai.com/v1", "2001:4860:4860::8888", "https://[2606:4700::1111]/")
    }

    @Test
    fun `ipv6 link-local, unique-local and multicast are local`() {
        local("fe80::1", "fe80::1%wlan0", "FEBF::1", "fc00::1", "fd12:3456:789a::1", "ff02::fb")
        local("http://[fe80::1%25wlan0]:11434/v1", "[fd00::5]:22")
        notLocal("fec0::1", "fbff::1", "2001:db8::1")
    }

    @Test
    fun `ipv4-mapped ipv6 follows the ipv4 address`() {
        local("::ffff:192.168.1.5", "http://[::ffff:10.0.0.2]:8000")
        notLocal("::ffff:8.8.8.8", "::ffff:127.0.0.1")
    }

    @Test
    fun `local names are local`() {
        local("nas.local", "MacBook.local.", "http://studio.local:1234/v1", "router.home.arpa", "pc.lan", "nas", "http://ollama:11434")
        notLocal("example.com", "local.example.com", "my.localdomain.io")
    }

    @Test
    fun `urls, ports and user info are stripped`() {
        assertEquals("192.168.1.5", LocalNetwork.hostOf("http://192.168.1.5:11434/v1/models?x=1"))
        assertEquals("nas.local", LocalNetwork.hostOf("ssh://me@nas.local:22"))
        assertEquals("fe80::1", LocalNetwork.hostOf("http://[fe80::1%25wlan0]:8080/"))
        assertEquals("10.0.0.2", LocalNetwork.hostOf("10.0.0.2:8000"))
        assertEquals(null, LocalNetwork.hostOf("  "))
        notLocal("", "http://", "not a url at all")
    }
}
