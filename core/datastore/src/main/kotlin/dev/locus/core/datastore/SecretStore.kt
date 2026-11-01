package dev.locus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API keys and other credentials. The backing file is encrypted with AES-256-GCM through Tink; the
 * Tink keyset is itself wrapped by a key in the Android Keystore that never leaves secure hardware.
 * Secrets are read only at the moment a request is built and are never put into prompts.
 *
 * The constructor is public so unit tests can back the store with an in-memory DataStore (and a
 * plain-JSON file format); in the app only Hilt creates it, with the encrypted store and format.
 */
@Singleton
class SecretStore @Inject constructor(
    @param:Secrets private val store: DataStore<Map<String, String>>,
    /** The vault file's format; in the app, the same Tink AEAD serializer [store] reads and writes with. */
    @param:Secrets private val fileFormat: Serializer<Map<String, String>> = secretsJson(),
) {
    suspend fun get(name: String): String? = store.data.first()[name]

    suspend fun put(name: String, value: String?) {
        store.updateData { current ->
            if (value.isNullOrEmpty()) current - name else current + (name to value)
        }
    }

    /** Which secrets exist, never their values; for showing "key saved" in the UI. */
    val names: Flow<Set<String>> = store.data.map { it.keys }

    /**
     * Every stored value, only so text can be scrubbed of them before it is shown (the "What was sent"
     * viewer). Never display, log or send what this returns.
     */
    suspend fun valuesForScrubbing(): Collection<String> = store.data.first().values

    /**
     * Every secret, only for an encrypted backup the user explicitly chose to include the vault in
     * (Settings → Backup). The values go straight into the password-encrypted stream, never to disk.
     */
    suspend fun exportAll(): Map<String, String> = store.data.first()

    /**
     * Writes [secrets] to [target] as a complete vault file, in exactly the format the vault reads its
     * own file with (in the app: the same Tink keyset and the same associated data), then reads it
     * back to check. For a restore: the file is staged and swapped in with everything else at the next
     * start, or not at all (docs/design/backup.md, "Applying a restore"). The current vault isn't touched.
     */
    suspend fun writeVaultFile(secrets: Map<String, String>, target: File) {
        val clean = secrets.filter { (name, value) -> name.isNotEmpty() && value.isNotEmpty() }
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            fileFormat.writeTo(clean, out)
            out.fd.sync()
        }
        // A staged vault that can't be read back would replace good keys with nothing.
        val back = target.inputStream().use { fileFormat.readFrom(it) }
        check(back == clean) { "The staged vault didn't read back" }
    }

    companion object {
        fun providerKey(providerId: String) = "provider:$providerId"

        /**
         * A provider's backup API keys, one per line, tried in turn when a key runs out of quota or is
         * refused (the key pool, docs/design/fallbacks.md). Outside `provider:`, so it never counts as
         * the provider's key, and outside `connector:`, so no connector can name it.
         */
        fun providerBackupKeys(providerId: String) = "$PROVIDER_BACKUP_KEYS_PREFIX$providerId"
        const val PROVIDER_BACKUP_KEYS_PREFIX = "provider-keys:"

        /**
         * The value of one extra header a provider sends (header names are case-insensitive, so the
         * name is lowercased). Outside `provider:` so it never counts as the provider's API key, and
         * outside `connector:` so no connector can name it.
         */
        fun providerHeader(providerId: String, headerName: String) = "$PROVIDER_HEADER_PREFIX$providerId:${headerName.trim().lowercase()}"
        const val PROVIDER_HEADER_PREFIX = "provider-header:"

        /**
         * A proxy's username and password, as a JSON object {"username","password"}: [scope] is a
         * provider id, or [DEFAULT_PROXY_SCOPE] for the global default proxy.
         */
        fun proxyCredentials(scope: String) = "proxy:$scope"
        const val DEFAULT_PROXY_SCOPE = "*default"

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

        /**
         * A ChatGPT provider's sign-in (tokens) and its account registration (issued client id and
         * identity, kept after signing out), plus this install's agent host id. Outside `connector:`
         * for the same reason as the MCP sessions.
         */
        fun chatGptSession(providerId: String) = "chatgpt-oauth:$providerId"
        fun chatGptAccount(providerId: String) = "chatgpt-oauth-account:$providerId"
        const val CHATGPT_HOST_ID = "chatgpt-oauth-host-id"
        const val CHATGPT_SESSION_PREFIX = "chatgpt-oauth:"
    }
}
