package dev.locus.core.data.network

import dev.locus.core.model.ProxyConfig
import dev.locus.core.model.ProxyType
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** A proxy's username and password, from the vault. [toString] hides the password. */
internal data class ProxyCredentials(val username: String, val password: String) {
    override fun toString() = "ProxyCredentials($username, ***)"
}

internal fun ProxyConfig.toJavaProxy(): Proxy = Proxy(
    if (type == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP,
    // Unresolved: OkHttp resolves an HTTP proxy's name itself, and a SOCKS proxy's name is resolved
    // by the socket when it connects. Nothing is looked up while settings are read.
    InetSocketAddress.createUnresolved(host.trim(), port),
)

/**
 * Sends everything through [proxy] except loopback: the ChatGPT and MCP sign-in redirects, servers
 * in the Linux workspace and anything else on this phone must never go out to a proxy.
 */
internal class LoopbackBypassProxySelector(private val proxy: Proxy) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> =
        if (uri == null || isLoopbackHost(uri.host)) listOf(Proxy.NO_PROXY) else listOf(proxy)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

/**
 * Whether [host] is this device, judged from the name alone (no DNS lookup, so a proxy can't be
 * skipped by a name that only resolves to 127.0.0.1): `localhost`, `*.localhost`, 127.0.0.0/8, ::1
 * and the IPv4-mapped form of a 127 address.
 */
internal fun isLoopbackHost(host: String?): Boolean {
    if (host == null) return true
    val h = host.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
    if (h.isEmpty()) return true
    if (h == "localhost" || h.endsWith(".localhost")) return true
    val v4 = h.removePrefix("::ffff:")
    if (IPV4.matches(v4)) {
        val octets = v4.split('.').map { it.toInt() }
        return octets.all { it in 0..255 } && octets[0] == 127
    }
    if (':' in h) {
        // Any spelling of ::1 (0:0:0:0:0:0:0:1, 0000::1, ...).
        val expanded = runCatching { expandIpv6(h) }.getOrNull() ?: return false
        return expanded.dropLast(1).all { it == 0 } && expanded.last() == 1
    }
    return false
}

private val IPV4 = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

private fun expandIpv6(h: String): List<Int>? {
    val noZone = h.substringBefore('%')
    val parts = noZone.split("::")
    if (parts.size > 2) return null
    val head = parts[0].takeIf { it.isNotEmpty() }?.split(':') ?: emptyList()
    val tail = if (parts.size == 2) parts[1].takeIf { it.isNotEmpty() }?.split(':') ?: emptyList() else emptyList()
    if (head.any { it.contains('.') } || tail.any { it.contains('.') }) return null
    val missing = 8 - head.size - tail.size
    if (parts.size == 1 && missing != 0 || missing < 0) return null
    return (head + List(missing) { "0" } + tail).map { it.toInt(16).also { v -> require(v in 0..0xFFFF) } }
}

/**
 * SOCKS5 username and password. The platform's SOCKS client asks java.net.Authenticator for them
 * (protocol "SOCKS5", the proxy's host and port; see SocksSocketImpl in libcore), and the
 * Authenticator is process-wide. So this one answers only for a SOCKS5 proxy whose host and port a
 * provider registered, and never for anything else (HTTP auth challenges, other hosts).
 */
internal object SocksCredentials {
    private val byProxy = ConcurrentHashMap<String, ProxyCredentials>()

    @Volatile
    private var installed = false

    private fun key(host: String, port: Int) = "${host.trim().lowercase()}:$port"

    fun register(proxy: ProxyConfig, credentials: ProxyCredentials) {
        byProxy[key(proxy.host, proxy.port)] = credentials
        install()
    }

    fun unregister(proxy: ProxyConfig) {
        byProxy.remove(key(proxy.host, proxy.port))
    }

    /** What the Authenticator answers for a request; split out to test without the global. */
    fun answer(protocol: String?, host: String?, port: Int): PasswordAuthentication? {
        // The SOCKS client asks with requestor type SERVER (the default), so the protocol is what identifies it.
        if (!protocol.equals("SOCKS5", ignoreCase = true) || host == null) return null
        val c = byProxy[key(host, port)] ?: return null
        return PasswordAuthentication(c.username, c.password.toCharArray())
    }

    @Synchronized
    private fun install() {
        if (installed) return
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                answer(requestingProtocol, requestingHost ?: requestingSite?.hostAddress, requestingPort)
        })
        installed = true
    }
}
