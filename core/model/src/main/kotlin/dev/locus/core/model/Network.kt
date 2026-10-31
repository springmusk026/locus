package dev.locus.core.model

import kotlinx.serialization.Serializable

/** The protocol a proxy speaks. */
@Serializable
enum class ProxyType { HTTP, SOCKS5 }

/**
 * A proxy for model requests. Its username and password, when [hasCredentials], live in the vault
 * (SecretStore.proxyCredentials), never here.
 */
@Serializable
data class ProxyConfig(
    val type: ProxyType = ProxyType.HTTP,
    val host: String,
    val port: Int,
    val hasCredentials: Boolean = false,
) {
    val isValid: Boolean get() = host.isNotBlank() && !host.any { it.isWhitespace() || it == '/' || it == '@' } && port in 1..65535
}

/** How a provider picks its proxy. */
@Serializable
enum class ProxyMode {
    /** The global default from Settings → Network, if one is set; otherwise direct. */
    DEFAULT,

    /** Never through a proxy, even when a global default is set. */
    DIRECT,

    /** This provider's own [ProviderNetwork.proxy]. */
    CUSTOM,
}

/**
 * A provider's network settings. Only names and choices are stored here, in the settings file; the
 * values of extra headers and proxy credentials are in the vault.
 */
@Serializable
data class ProviderNetwork(
    /** Extra request headers, by name. Each value is a vault secret (SecretStore.providerHeader). */
    val headerNames: List<String> = emptyList(),
    /**
     * Lets an extra header replace the credential header the app sets (Authorization, x-api-key), for
     * gateways with their own auth. Off unless the user turns it on, with a warning.
     */
    val allowAuthOverride: Boolean = false,
    val proxyMode: ProxyMode = ProxyMode.DEFAULT,
    val proxy: ProxyConfig? = null,
    /** Imported certificates ([TrustedCertificate.id]) this provider's connections also trust. */
    val certificateIds: List<String> = emptyList(),
) {
    val isDefault: Boolean get() = this == ProviderNetwork()
}

/**
 * A CA certificate the user imported. Public data (not a secret), so it lives in the settings file.
 * It's trusted only by the providers that list it, or by every provider when [allProviders].
 */
@Serializable
data class TrustedCertificate(
    val id: String,
    /** The certificate in PEM form. */
    val pem: String,
    /** The subject's common name, or the whole subject when it has none. */
    val subject: String,
    /** SHA-256 of the DER encoding, as uppercase hex pairs joined by ':'. */
    val sha256: String,
    /** Expiry, in milliseconds since the epoch. */
    val notAfter: Long,
    val allProviders: Boolean = false,
)

/** Settings → Network: the default proxy for model providers and the imported certificates. */
@Serializable
data class NetworkDefaults(
    val proxy: ProxyConfig? = null,
    val certificates: List<TrustedCertificate> = emptyList(),
)

/** What a header name may be used for as an extra provider header. */
sealed interface HeaderVerdict {
    data object Allowed : HeaderVerdict

    /** A credential header the app already sets; allowed only with [ProviderNetwork.allowAuthOverride]. */
    data class Credential(val warning: String) : HeaderVerdict

    data class Forbidden(val reason: String) : HeaderVerdict
}

/**
 * Which extra headers a provider may send. Hop-by-hop and framing headers are owned by the HTTP
 * stack, and the provider's own credential header by the adapter; replacing them would break the
 * request or send a key somewhere it wasn't meant to go.
 */
object HeaderRules {
    const val MAX_HEADERS = 16
    const val MAX_VALUE_LENGTH = 8_192

    /** Owned by the HTTP client or the connection; never set from settings. */
    private val FORBIDDEN = setOf(
        "host", "content-length", "content-type", "content-encoding", "transfer-encoding", "connection", "keep-alive",
        "upgrade", "te", "trailer", "expect", "accept-encoding", "proxy-authorization", "proxy-authenticate",
        "proxy-connection", "cookie2", "via", "date",
    )

    /** Credential headers the adapters set (Anthropic x-api-key, OpenAI-style Authorization, ChatGPT account). */
    private val CREDENTIAL = setOf("authorization", "x-api-key", "api-key", "x-goog-api-key", "chatgpt-account-id")

    /** RFC 9110 token characters. */
    private fun isTokenChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"

    fun check(name: String): HeaderVerdict {
        val n = name.trim()
        if (n.isEmpty()) return HeaderVerdict.Forbidden("Enter a header name.")
        if (n.length > 128 || !n.all(::isTokenChar)) return HeaderVerdict.Forbidden("Not a valid header name.")
        val lower = n.lowercase()
        if (lower in FORBIDDEN || lower.startsWith("proxy-") || lower.startsWith("sec-")) {
            return HeaderVerdict.Forbidden("$n is set by the connection itself and can't be changed.")
        }
        if (lower in CREDENTIAL) {
            return HeaderVerdict.Credential("$n replaces the key Locus sends for this provider. Only turn this on for a gateway with its own sign-in.")
        }
        return HeaderVerdict.Allowed
    }

    /** Null when [value] can be sent; otherwise why not. Values can't hold line breaks (header injection). */
    fun checkValue(value: String): String? = when {
        value.isEmpty() -> "Enter a value."
        value.length > MAX_VALUE_LENGTH -> "That value is too long."
        value.any { it == '\r' || it == '\n' || it == '\u0000' } -> "A header value can't contain line breaks."
        value.any { it.code > 0xFF } -> "A header value can only use Latin-1 characters."
        else -> null
    }

    /** Whether [name] may be sent for a provider with [allowAuthOverride]. */
    fun usable(name: String, allowAuthOverride: Boolean): Boolean = when (check(name)) {
        HeaderVerdict.Allowed -> true
        is HeaderVerdict.Credential -> allowAuthOverride
        is HeaderVerdict.Forbidden -> false
    }

    fun isCredential(name: String) = check(name) is HeaderVerdict.Credential
}
