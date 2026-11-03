package dev.locus.core.data.mcp

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.ConnectorKeys
import dev.locus.core.agent.home.KeyOrigins
import dev.locus.core.datastore.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The vault's record of which address each connector key belongs to ([KeyOrigins]). A key is tied to
 * the connector's origin when the user saves it (adding a server, Add key / Replace key) or confirms
 * it in Library; a connector whose key is tied elsewhere can't send it.
 *
 * Keys saved before this existed are tied once, on first use: to their connector's origin when exactly
 * one origin among the approved connectors uses them. A key shared by connectors at different
 * addresses, or named like a built-in integration's, stays untied until the user confirms it
 * (docs/design/mcp-connectors.md, "Keys belong to an address"). The pass runs before any approval, so a
 * draft approved later can't be what a key gets tied to.
 */
@Singleton
class ConnectorKeyOrigins @Inject constructor(
    private val secrets: SecretStore,
    private val home: AgentHome,
) : KeyOrigins {
    private val lock = Mutex()

    override suspend fun allowed(secret: String): Set<String> {
        migrate()
        return read(secret)
    }

    /** The origins [secret] is tied to, without migrating; for Library's card. */
    suspend fun read(secret: String): Set<String> =
        secrets.get(SecretStore.connectorKeyOrigins(secret))?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** The user saved a new value for [secret] for [url]: it now goes there only. */
    suspend fun saved(secret: String, url: String) {
        val origin = ConnectorKeys.origin(url) ?: return
        write(secret, setOf(origin))
    }

    /** The user confirmed that the existing value of [secret] may also go to [url]. */
    suspend fun confirm(secret: String, url: String) {
        val origin = ConnectorKeys.origin(url) ?: return
        write(secret, read(secret) + origin)
    }

    private suspend fun write(secret: String, origins: Set<String>) =
        secrets.put(SecretStore.connectorKeyOrigins(secret), origins.sorted().joinToString("\n"))

    /** Ties the keys saved before origins were recorded, once. Safe to call any number of times. */
    suspend fun migrate() = lock.withLock {
        if (secrets.get(SecretStore.CONNECTOR_ORIGINS_MIGRATED) != null) return@withLock
        val connectors = withContext(Dispatchers.IO) { home.connectors() }
            .filter { it.local == null && it.auth.type in ConnectorKeys.KEY_TYPES }
        connectors.groupBy { it.auth.secret }.forEach { (name, users) ->
            if (ConnectorKeys.reserved(name) || secrets.get(SecretStore.connectorSecret(name)) == null || read(name).isNotEmpty()) return@forEach
            val origins = users.mapNotNull { ConnectorKeys.origin(it.baseUrl) }.toSet()
            if (origins.size == 1) write(name, origins)
        }
        secrets.put(SecretStore.CONNECTOR_ORIGINS_MIGRATED, "1")
    }
}
