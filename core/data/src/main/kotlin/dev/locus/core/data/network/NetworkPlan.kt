package dev.locus.core.data.network

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.HeaderRules
import dev.locus.core.model.NetworkDefaults
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProxyConfig
import dev.locus.core.model.ProxyMode
import dev.locus.core.model.TrustedCertificate

/**
 * What one provider's connection needs, worked out from its settings and the global defaults:
 * which extra headers to send, which proxy (and whose credentials), and which extra CAs to trust.
 * Holds names only, never secret values, so it is safe to log, compare and use as a cache key.
 */
internal data class NetworkPlan(
    /** Usable extra header names, as the user typed them. */
    val headerNames: List<String> = emptyList(),
    val allowAuthOverride: Boolean = false,
    val proxy: ProxyConfig? = null,
    /** The vault scope of the proxy's credentials: the provider's id, or the default proxy's scope. */
    val proxyScope: String? = null,
    val certificates: List<TrustedCertificate> = emptyList(),
) {
    /** Redirects are off whenever extra headers are sent, so they can't follow a redirect to another host. */
    val followRedirects: Boolean get() = headerNames.isEmpty()

    /** Nothing to change: the provider can use the app's shared client. */
    val isDirect: Boolean get() = headerNames.isEmpty() && proxy == null && certificates.isEmpty()

    companion object {
        fun resolve(provider: ProviderConfig, defaults: NetworkDefaults): NetworkPlan {
            val net = provider.network
            // A ChatGPT sign-in's token is the point of that provider; it can't be replaced by a header.
            val allowAuth = net.allowAuthOverride && provider.format != ApiFormat.CHATGPT
            val headers = net.headerNames
                .map { it.trim() }
                .filter { HeaderRules.usable(it, allowAuth) }
                .distinctBy { it.lowercase() }
                .take(HeaderRules.MAX_HEADERS)
            val (proxy, scope) = when (net.proxyMode) {
                ProxyMode.DIRECT -> null to null
                ProxyMode.CUSTOM -> net.proxy?.takeIf { it.isValid }?.let { it to provider.id } ?: (null to null)
                ProxyMode.DEFAULT -> defaults.proxy?.takeIf { it.isValid }?.let { it to dev.locus.core.datastore.SecretStore.DEFAULT_PROXY_SCOPE } ?: (null to null)
            }
            val certificates = defaults.certificates.filter { it.allProviders || it.id in net.certificateIds }
            return NetworkPlan(headers, allowAuth && headers.any(HeaderRules::isCredential), proxy, scope?.takeIf { proxy?.hasCredentials == true }, certificates)
        }
    }
}
