package dev.locus.core.data.network

import dev.locus.core.model.HeaderRules
import io.ktor.client.plugins.api.createClientPlugin

/**
 * Extra header values for one provider, read from the vault. [toString] never shows a value, so a
 * stray log line or exception message can't carry one.
 */
internal class HeaderValues(values: Map<String, String>) {
    /** Name as typed to value. */
    val values: Map<String, String> = values.toMap()

    override fun toString(): String = "HeaderValues(${values.keys.joinToString()})"

    override fun equals(other: Any?) = other is HeaderValues && other.values == values

    override fun hashCode() = values.hashCode()
}

internal class ProviderHeadersConfig {
    var headers: HeaderValues = HeaderValues(emptyMap())
    var allowAuthOverride: Boolean = false
}

/**
 * Adds a provider's extra headers to each request after the adapter built it. A header the adapter
 * already set wins, except a credential header the user explicitly allowed to replace (a gateway with
 * its own auth). Forbidden names are checked again here, so a hand-edited settings file can't slip
 * in a Host or Content-Length.
 */
internal val ProviderHeaders = createClientPlugin("ProviderHeaders", ::ProviderHeadersConfig) {
    val headers = pluginConfig.headers.values
    val allowAuth = pluginConfig.allowAuthOverride
    onRequest { request, _ ->
        for ((name, value) in headers) {
            if (!HeaderRules.usable(name, allowAuth) || HeaderRules.checkValue(value) != null) continue
            val replace = allowAuth && HeaderRules.isCredential(name)
            if (replace) {
                // Replaces only the same header: an Authorization for a gateway can travel next to the provider's x-api-key.
                request.headers[name] = value
            } else if (!request.headers.contains(name)) {
                request.headers[name] = value
            }
        }
    }
}
