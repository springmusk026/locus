package dev.locus.core.agent.home

import dev.locus.core.model.ConnectorSealFormat
import dev.locus.core.model.IntegrityKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which connectors the user approved, sealed with a Keystore key: `connectors/.seal` lists each
 * active connector's id and the SHA-256 of its file, with an HMAC over the list
 * (docs/design/linux-isolation.md, "Sealed permissions").
 *
 * A connector file that isn't on the list, or whose contents changed, was put there outside Locus.
 * [unapproved] finds those; [AgentHome] sends them back to drafts and reports them.
 */
class ConnectorSeal(private val key: IntegrityKey, private val onUnapproved: (List<String>) -> Unit = {}) {
    @Serializable
    private data class Manifest(val connectors: Map<String, String> = emptyMap(), val mac: String = "")

    /** The manifest text last found valid, so an unchanged manifest isn't checked against the Keystore again. */
    @Volatile private var lastValid: String? = null

    internal fun report(ids: List<String>) {
        if (ids.isNotEmpty()) onUnapproved(ids)
    }

    /** Each active connector file's id and content hash. */
    internal fun current(connectorsDir: File): Map<String, String> =
        connectorsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .associate { it.name.removeSuffix(".json") to ConnectorSealFormat.sha256(it.readBytes()) }

    /**
     * Ids in [connectorsDir] the user didn't approve. Without a manifest, everything is trusted once
     * if the key is new (an update from a version without seals) and sealed; otherwise none is approved.
     */
    internal fun unapproved(connectorsDir: File): List<String> {
        val now = current(connectorsDir)
        val file = File(connectorsDir, FILE)
        val text = runCatching { file.readText() }.getOrNull()
        if (text == null) {
            if (now.isEmpty()) {
                // Seal the empty list too: otherwise a connector planted later, before any was ever
                // approved, would arrive while the key is still new and be trusted as "first use".
                runCatching { write(connectorsDir) }
                return emptyList()
            }
            val firstUse = runCatching { key.createdThisRun() }.getOrDefault(false)
            if (firstUse) {
                write(connectorsDir)
                return emptyList()
            }
            return now.keys.sorted()
        }
        val manifest = runCatching { json.decodeFromString(Manifest.serializer(), text) }.getOrNull()
        val valid = manifest != null && (text == lastValid || ConnectorSealFormat.matches(manifest.connectors, manifest.mac, key))
        if (!valid) return now.keys.sorted()
        lastValid = text
        return now.filter { (id, hash) -> manifest!!.connectors[id] != hash }.keys.sorted()
    }

    /** Seals the connectors now in [connectorsDir] as approved. */
    fun write(connectorsDir: File) {
        val now = current(connectorsDir)
        val text = json.encodeToString(Manifest.serializer(), Manifest(now, ConnectorSealFormat.mac(now, key)))
        val file = File(connectorsDir, FILE)
        val tmp = File(connectorsDir, "$FILE.tmp")
        tmp.writeText(text)
        check(tmp.renameTo(file)) { "Could not save the connector seal" }
        lastValid = text
    }

    companion object {
        /** Not `.json`, so it's never read as a connector. */
        const val FILE = ".seal"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
