package dev.locus.core.agent.home

import dev.locus.core.agent.ToolException
import java.net.URI

/**
 * Where each connector key may be sent: the origins (`scheme://host[:port]`) it was saved or
 * confirmed for. A key is looked up by name, and names are chosen by whoever wrote the connector, so
 * the name alone can't say which server the key belongs to (docs/design/mcp-connectors.md,
 * "Keys belong to an address").
 */
fun interface KeyOrigins {
    /** The origins [secret] may go to; empty when it was never tied to one (the user must confirm it). */
    suspend fun allowed(secret: String): Set<String>
}

/** A connector key and the address checks it must pass before it's sent. */
object ConnectorKeys {
    /** Auth types whose value is a vault key the connector names (not OAuth, not none). */
    val KEY_TYPES = setOf("bearer", "header", "query", "basic")

    /**
     * The header [auth] sends [secret] in, or null for query and no auth: `Authorization: Bearer …`,
     * the named header with its prefix (`Authorization: Token …`, `X-Auth-Token: …`), or
     * `Authorization: Basic base64(user:password)` for basic, whose vault value is `user:password`.
     */
    fun header(auth: Connector.Auth, secret: String): Pair<String, String>? = when (auth.type) {
        "bearer", "oauth" -> "Authorization" to "Bearer $secret"
        "header" -> auth.name to auth.prefix + secret
        "basic" -> "Authorization" to "Basic " + basic64(secret)
        else -> null
    }

    /** The vault value for basic auth: `user:password` (RFC 7617 forbids a colon in the user name). */
    fun basicValue(username: String, password: String): String {
        val user = username.trim()
        require(user.isNotEmpty()) { "Enter the user name." }
        require(':' !in user) { "A user name can't contain a colon." }
        require(password.isNotEmpty()) { "Enter the password." }
        return "$user:$password"
    }

    /**
     * Every form [secret] can take on the wire or in an echo, for scrubbing: as stored, the password
     * part and the base64 credentials of basic auth, and the value with its header prefix.
     */
    fun secretForms(auth: Connector.Auth, secret: String): List<String> = buildList {
        add(secret)
        if (auth.type == "basic") {
            add(basic64(secret))
            secret.substringAfter(':', "").takeIf { it.length >= 6 }?.let(::add)
        }
    }.filter { it.isNotEmpty() }.distinct()

    private fun basic64(secret: String) = java.util.Base64.getEncoder().encodeToString(secret.toByteArray(Charsets.UTF_8))

    /**
     * Vault names a connector can never use: the built-in integrations' keys (GitHub, email,
     * Telegram…). A connector gets its own copy only through the user's "Use the key saved for…" tap.
     */
    fun reserved(name: String): Boolean = name.startsWith("integration-")

    /** [url]'s origin, lowercase, without a default port; null when it isn't an http(s) URL. */
    fun origin(url: String): String? = runCatching {
        val u = URI(url.trim())
        val scheme = u.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
        val host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = u.port.takeIf { it != -1 && it != (if (scheme == "https") 443 else 80) }
        "$scheme://$host${port?.let { ":$it" }.orEmpty()}"
    }.getOrNull()

    /** The host part of an origin, for messages. */
    fun host(origin: String): String = origin.substringAfter("://")

    /**
     * [connector]'s key, only when it was saved for the connector's own address. Throws a
     * [ToolException] the agent can relay when it's missing, unconfirmed or for another address.
     */
    suspend fun resolve(connector: Connector, secrets: SecretResolver, origins: KeyOrigins): String {
        val name = connector.auth.secret
        val value = secrets.resolve(name)
            ?: throw ToolException("“${connector.id}” needs its key (“$name”). Ask the user to add it in Library → Connectors.")
        val origin = origin(connector.baseUrl) ?: throw ToolException("“${connector.id}” has no valid address.")
        val allowed = origins.allowed(name)
        if (origin in allowed) return value
        throw ToolException(
            if (allowed.isEmpty()) "The key “$name” isn't confirmed for ${host(origin)} yet, so Locus didn't send it. Ask the user to confirm it in Library → Connectors → ${connector.name}."
            else "The key “$name” was saved for ${allowed.joinToString { host(it) }}, not ${host(origin)}, so Locus didn't send it. Ask the user to add a key for ${connector.name} in Library → Connectors.",
        )
    }
}
