package dev.locus.core.datastore

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API keys and other credentials. The backing file is encrypted with AES-256-GCM through Tink; the
 * Tink keyset is itself wrapped by a key in the Android Keystore that never leaves secure hardware.
 * Secrets are read only at the moment a request is built and are never put into prompts.
 */
@Singleton
class SecretStore @Inject internal constructor(
    @param:Secrets private val store: DataStore<Map<String, String>>,
) {
    suspend fun get(name: String): String? = store.data.first()[name]

    suspend fun put(name: String, value: String?) {
        store.updateData { current ->
            if (value.isNullOrEmpty()) current - name else current + (name to value)
        }
    }

    /** Which secrets exist, never their values; for showing "key saved" in the UI. */
    val names: Flow<Set<String>> = store.data.map { it.keys }

    companion object {
        fun providerKey(providerId: String) = "provider:$providerId"

        fun sshPassword(hostId: String) = "ssh:$hostId:password"
        fun sshPrivateKey(hostId: String) = "ssh:$hostId:key"
        fun sshPassphrase(hostId: String) = "ssh:$hostId:passphrase"

        /** Credentials for connectors, referenced by name in connector definitions. */
        fun connectorSecret(name: String) = "connector:$name"

        /**
         * MCP OAuth sessions and client registrations. Deliberately outside `connector:`, which the
         * agent's connectors can name, so a drafted connector can never send these anywhere.
         */
        fun mcpOAuthSession(name: String) = "mcp-oauth:$name"
        fun mcpOAuthClient(issuer: String) = "mcp-oauth-client:$issuer"
    }
}
