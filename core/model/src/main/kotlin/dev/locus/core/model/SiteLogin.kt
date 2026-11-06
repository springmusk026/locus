package dev.locus.core.model

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * A login the user saved for one site, which the agent can have Locus fill but never sees
 * (docs/design/browser.md, "Saved logins"). Kept only in the encrypted vault (`SecretStore`), one
 * entry per login, so the binding to [origin] is inside the authenticated ciphertext with the secrets.
 *
 * [origin] is exact: scheme, host and port ([SiteOrigin.of]). No subdomain or wildcard matching.
 * [password] and [totpSecret] are secrets: they are never shown to the model, logged, or put in a
 * tool result. [username] is shown to the user (the list, the approval card), not to the model.
 */
@Serializable
data class SiteLogin(
    val id: String,
    val origin: String,
    val username: String,
    val password: String,
    /** The base32 TOTP secret (RFC 6238), when the user added one; null for none. */
    val totpSecret: String? = null,
    /** The user's name for it; the origin's host when blank. */
    val label: String = "",
    val createdAt: Long = 0,
    /** When Locus last filled it; null if never. */
    val lastUsed: Long? = null,
) {
    /** What the list and cards call it. */
    val title: String get() = label.ifBlank { SiteOrigin.host(origin) }

    /** Without its secrets, for anything that only needs to know it exists. */
    override fun toString(): String = "SiteLogin(id=$id, origin=$origin, hasTotp=${totpSecret != null})"
}

/** Origins as browsers compare them for site permissions and password managers: `scheme://host[:port]`. */
object SiteOrigin {
    /**
     * [url]'s origin, lower-cased, without a default port; null for anything but plain http(s) without
     * credentials in it. `https://github.com/login?x=1` → `https://github.com`.
     */
    fun of(url: String?): String? {
        val u = runCatching { URI(url?.trim() ?: return null) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (u.rawUserInfo != null) return null
        val host = u.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        val port = u.port.takeIf { it != -1 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    /** The host (and port) of an origin, for messages: `https://github.com` → `github.com`. */
    fun host(origin: String): String = origin.substringAfter("://")

    /** The phone itself: the only place a plain-http login may be filled (a dev server in Linux, say). */
    fun isLoopback(origin: String): Boolean {
        val authority = host(origin)
        val host = if (authority.startsWith("[")) authority.substringBefore("]").removePrefix("[") else authority.substringBefore(':')
        return host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "::1"
    }

    /**
     * Whether a login may be saved for or filled on [origin]: HTTPS, or plain http on the phone's own
     * loopback. Anything else would send the password in the clear.
     */
    fun secureEnough(origin: String): Boolean = origin.startsWith("https://") || (origin.startsWith("http://") && isLoopback(origin))
}
