package dev.locus.core.model

/**
 * Whether a host is on the user's local network, for Android 17's local network permission.
 *
 * Apps targeting Android 17 (API 37) need the runtime permission `ACCESS_LOCAL_NETWORK` (Nearby
 * devices group) to connect to LAN hosts; without it TCP connects typically time out
 * (https://developer.android.com/privacy-and-security/local-network-permission). Android's
 * definition (https://developer.android.com/privacy-and-security/local-network-definition) lists
 * 10/8, 172.16/12, 192.168/16, 169.254/16 (link-local), 100.64/10 (CGNAT), IPv6 link-local,
 * multicast (224/4, ff00::/8) and 255.255.255.255, and resolving `.local` names. It doesn't mention
 * loopback, which isn't a broadcast-capable interface, so localhost (the Linux workspace's servers,
 * OAuth redirects) is treated as unaffected.
 *
 * This only looks at the text of the host: a public-looking name that resolves to a LAN address
 * isn't caught. It errs towards "local" (IPv6 unique-local fc00::/7, `.home.arpa`, `.lan` and
 * single-label names count) because asking once too often costs less than a silent timeout.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The first API level whose apps are blocked from the LAN without [PERMISSION]. */
    const val ENFORCED_FROM_SDK = 37

    /** [target] is a URL (`http://192.168.1.5:11434/v1`), `host:port`, or a bare host. */
    fun isLocalTarget(target: String): Boolean = hostOf(target)?.let(::isLocalHost) ?: false

    /** The host part of a URL, `host:port` or bare host, without brackets or an IPv6 zone. */
    fun hostOf(target: String): String? {
        var rest = target.trim().substringAfter("://")
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (rest.isEmpty()) return null
        val host = when {
            rest.startsWith("[") -> rest.substringAfter('[').substringBefore(']')
            rest.count { it == ':' } == 1 -> rest.substringBefore(':')
            else -> rest // bare IPv6 or plain host
        }
        return host.substringBefore('%').trimEnd('.').lowercase().ifEmpty { null }
    }

    fun isLocalHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%').trimEnd('.').lowercase()
        if (h.isEmpty() || isLoopbackName(h)) return false
        ipv4(h)?.let { return isLocalIpv4(it) }
        if (':' in h) return ipv6(h)?.let(::isLocalIpv6) ?: false
        if (!HOST_NAME.matches(h)) return false
        if (LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        // A single-label name ("nas", "mypc") can only be resolved on the local network.
        return '.' !in h
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".home.arpa", ".lan")
    private val HOST_NAME = Regex("[a-z0-9_-]+(\\.[a-z0-9_-]+)*")

    private fun isLoopbackName(h: String) = h == "localhost" || h.endsWith(".localhost")

    private fun ipv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        return IntArray(4) { i -> parts[i].takeIf { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) }?.toInt()?.takeIf { it <= 255 } ?: return null }
    }

    private fun isLocalIpv4(a: IntArray): Boolean = when {
        a[0] == 10 -> true
        a[0] == 172 && a[1] in 16..31 -> true
        a[0] == 192 && a[1] == 168 -> true
        a[0] == 169 && a[1] == 254 -> true
        a[0] == 100 && a[1] in 64..127 -> true
        a[0] in 224..239 -> true
        a.all { it == 255 } -> true
        else -> false // loopback 127/8, 0.0.0.0 and public addresses
    }

    /** The eight 16-bit groups of an IPv6 address, or null if it isn't one. */
    private fun ipv6(h: String): IntArray? {
        // IPv4-mapped or -embedded form (::ffff:192.168.1.5): rewrite the dotted quad as two groups.
        val lastColon = h.lastIndexOf(':')
        val last = h.substring(lastColon + 1)
        val text = if ('.' !in last) h else {
            val v4 = ipv4(last) ?: return null
            h.substring(0, lastColon + 1) + "%x:%x".format((v4[0] shl 8) or v4[1], (v4[2] shl 8) or v4[3])
        }
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groups(s: String): List<Int>? = if (s.isEmpty()) emptyList() else s.split(':').map { g ->
            if (g.isEmpty() || g.length > 4) return null
            g.toIntOrNull(16) ?: return null
        }
        val head = groups(halves[0]) ?: return null
        val back = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val missing = 8 - head.size - back.size
        if (missing < 0 || (halves.size == 1 && missing != 0)) return null
        return (head + List(missing) { 0 } + back).toIntArray()
    }

    private fun isLocalIpv6(g: IntArray): Boolean {
        val first = g[0]
        return when {
            g.take(5).all { it == 0 } && g[5] == 0xffff -> isLocalIpv4(intArrayOf(g[6] shr 8, g[6] and 0xff, g[7] shr 8, g[7] and 0xff))
            first and 0xffc0 == 0xfe80 -> true // link-local fe80::/10
            first and 0xfe00 == 0xfc00 -> true // unique local fc00::/7
            first and 0xff00 == 0xff00 -> true // multicast ff00::/8
            else -> false // ::1 loopback, :: and global addresses
        }
    }
}
