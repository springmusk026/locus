package dev.locus.core.data.network

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.AppSettings
import dev.locus.core.model.HeaderRules
import dev.locus.core.model.NetworkDefaults
import dev.locus.core.model.ProviderNetwork
import dev.locus.core.model.ProxyConfig
import dev.locus.core.model.ProxyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The vault side of a provider's network settings, as edited in the provider editor.
 *
 * [headerValues]: new values by header name; a header listed in the settings but missing here keeps
 * its saved value. [proxyCredentials]: null keeps what is saved, a pair replaces it, and two empty
 * strings remove it. [toString] never shows a value.
 */
data class NetworkSecretEdits(
    val headerValues: Map<String, String> = emptyMap(),
    val proxyCredentials: Pair<String, String>? = null,
) {
    override fun toString() = "NetworkSecretEdits(headers=${headerValues.keys}, proxyCredentials=${if (proxyCredentials == null) "kept" else "changed"})"

    internal suspend fun apply(secrets: SecretStore, providerId: String, before: ProviderNetwork?, after: ProviderNetwork) {
        val kept = after.headerNames.map { it.lowercase() }.toSet()
        before?.headerNames?.filter { it.lowercase() !in kept }?.forEach { secrets.put(SecretStore.providerHeader(providerId, it), null) }
        for ((name, value) in headerValues) {
            if (name.lowercase() in kept && HeaderRules.checkValue(value) == null) secrets.put(SecretStore.providerHeader(providerId, name), value)
        }
        val proxyKey = SecretStore.proxyCredentials(providerId)
        val wantsCredentials = after.proxyMode == ProxyMode.CUSTOM && after.proxy?.hasCredentials == true
        when {
            !wantsCredentials -> secrets.put(proxyKey, null)
            proxyCredentials != null -> secrets.put(
                proxyKey,
                proxyCredentials.takeUnless { it.first.isEmpty() && it.second.isEmpty() }?.let { ProviderHttpClients.writeProxyCredentials(it.first, it.second) },
            )
        }
    }

    companion object {
        /** Drops header names that may never be sent, duplicates, and the credential override when unused. */
        fun sanitize(network: ProviderNetwork): ProviderNetwork {
            val names = network.headerNames.map { it.trim() }
                .filter { HeaderRules.usable(it, allowAuthOverride = true) }
                .distinctBy { it.lowercase() }
                .take(HeaderRules.MAX_HEADERS)
            val proxy = network.proxy?.let { it.copy(host = it.host.trim()) }
            return network.copy(
                headerNames = names,
                allowAuthOverride = network.allowAuthOverride && names.any(HeaderRules::isCredential),
                proxy = proxy,
                proxyMode = if (network.proxyMode == ProxyMode.CUSTOM && proxy?.isValid != true) ProxyMode.DEFAULT else network.proxyMode,
                certificateIds = network.certificateIds.distinct(),
            )
        }

        /** Removes every network secret of a deleted provider. */
        internal suspend fun forget(secrets: SecretStore, providerId: String) {
            val prefix = SecretStore.providerHeader(providerId, "")
            secrets.names.first().filter { it.startsWith(prefix) }.forEach { secrets.put(it, null) }
            secrets.put(SecretStore.proxyCredentials(providerId), null)
        }
    }
}

/**
 * Which network secrets exist, never their values: header names (lowercase) by provider id, and the
 * proxy credential scopes (provider ids, or [DEFAULT_SCOPE]).
 */
data class SavedNetworkSecrets(val headers: Map<String, Set<String>> = emptyMap(), val proxyScopes: Set<String> = emptySet()) {
    fun hasHeader(providerId: String, name: String) = name.trim().lowercase() in headers[providerId].orEmpty()

    companion object {
        const val DEFAULT_SCOPE = SecretStore.DEFAULT_PROXY_SCOPE

        internal fun from(names: Set<String>): SavedNetworkSecrets {
            val headers = names.filter { it.startsWith(SecretStore.PROVIDER_HEADER_PREFIX) }
                .map { it.removePrefix(SecretStore.PROVIDER_HEADER_PREFIX) }
                .groupBy({ it.substringBefore(':') }, { it.substringAfter(':') })
                .mapValues { it.value.toSet() }
            val prefix = SecretStore.proxyCredentials("")
            return SavedNetworkSecrets(headers, names.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet())
        }
    }
}

/** Settings → Network: the default proxy and the imported CA certificates. */
@Singleton
class NetworkSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: DataStore<AppSettings>,
    private val secrets: SecretStore,
) {
    val defaults: Flow<NetworkDefaults> = store.data.map { it.network }

    /** Which providers trust each certificate on their own (not counting "all providers"). */
    val providersByCertificate: Flow<Map<String, List<String>>> = store.data.map { s ->
        s.network.certificates.associate { c -> c.id to s.providers.filter { c.id in it.network.certificateIds }.map { it.name } }
    }

    val saved: Flow<SavedNetworkSecrets> = secrets.names.map(SavedNetworkSecrets::from)

    /** [credentials]: null keeps the saved ones, two empty strings remove them. */
    suspend fun setDefaultProxy(proxy: ProxyConfig?, credentials: Pair<String, String>?) {
        val clean = proxy?.copy(host = proxy.host.trim())?.takeIf { it.isValid }
        store.updateData { it.copy(network = it.network.copy(proxy = clean)) }
        val key = SecretStore.proxyCredentials(SecretStore.DEFAULT_PROXY_SCOPE)
        when {
            clean == null || !clean.hasCredentials -> secrets.put(key, null)
            credentials != null -> secrets.put(
                key,
                credentials.takeUnless { it.first.isEmpty() && it.second.isEmpty() }?.let { ProviderHttpClients.writeProxyCredentials(it.first, it.second) },
            )
        }
    }

    /** Adds the certificates in the file at [uri]; returns how many were new. */
    suspend fun importCertificates(uri: Uri): Int {
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val data = input.readNBytesCompat(Certificates.MAX_FILE_BYTES + 1)
                require(data.size <= Certificates.MAX_FILE_BYTES) { "That file is too large to be a certificate." }
                data
            } ?: throw IllegalArgumentException("Couldn't open that file.")
        }
        return importCertificates(bytes)
    }

    suspend fun importCertificates(bytes: ByteArray): Int {
        val parsed = Certificates.parse(bytes).map(Certificates::toTrusted)
        var added = 0
        store.updateData { s ->
            val known = s.network.certificates.map { it.sha256 }.toSet()
            val fresh = parsed.filter { it.sha256 !in known }.distinctBy { it.sha256 }
            added = fresh.size
            s.copy(network = s.network.copy(certificates = s.network.certificates + fresh))
        }
        return added
    }

    suspend fun setTrustedByAll(id: String, all: Boolean) = store.updateData { s ->
        s.copy(network = s.network.copy(certificates = s.network.certificates.map { if (it.id == id) it.copy(allProviders = all) else it }))
    }

    /** Removes the certificate and every provider's reference to it. */
    suspend fun removeCertificate(id: String) = store.updateData { s ->
        s.copy(
            network = s.network.copy(certificates = s.network.certificates.filterNot { it.id == id }),
            providers = s.providers.map { p -> if (id in p.network.certificateIds) p.copy(network = p.network.copy(certificateIds = p.network.certificateIds - id)) else p },
        )
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
