package dev.locus.core.ondevice.gguf

import dev.locus.core.ondevice.DeviceMemory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A recommended GGUF model: one file at one commit of one Hugging Face repo, with the size and
 * SHA-256 the Hub publishes for it (its Git LFS pointer). Everything but the name, description and
 * licence name is written by tools/gguf-catalog.py from the Hub API and the file's own header, so the
 * memory estimate and "tools" are known before a byte is downloaded.
 */
data class GgufCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val repo: String,
    val file: String,
    /** The commit the file is pinned to: a 40-character hex SHA, never a branch. */
    val revision: String,
    val sizeBytes: Long,
    val sha256: String,
    val licenseName: String,
    val licenseUrl: String?,
    val architecture: String,
    val contextLength: Int?,
    /** "2B", from general.size_label. */
    val sizeLabel: String?,
    val kvBytesPerToken: Long?,
    val tools: Boolean,
) {
    val quant: String? get() = GgufQuant.fromFileName(file)
    val source: HfFileRef get() = HfFileRef(repo, revision, file)

    /** Memory it needs on [memory], at the context Locus would give it ([GgufMemory.chooseContext]). */
    fun ramBytes(memory: DeviceMemory): Long =
        GgufMemory.estimate(sizeBytes, kvBytesPerToken, GgufMemory.chooseContext(contextLength, sizeBytes, kvBytesPerToken, memory))
}

/** The catalogue, read from catalog.json beside this class (in the APK's Java resources). */
object GgufCatalog {
    /** Absolute, so R8 repackaging this class can't move where it's looked up. */
    private const val RESOURCE = "/dev/locus/core/ondevice/gguf/catalog.json"

    val models: List<GgufCatalogEntry> by lazy {
        val text = GgufCatalog::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
            ?: error("$RESOURCE is missing from the build")
        parse(text)
    }

    fun parse(text: String): List<GgufCatalogEntry> {
        val root = Json.parseToJsonElement(text) as JsonObject
        return (root["models"] as JsonArray).map { decode(it as JsonObject) }
    }

    private fun decode(o: JsonObject): GgufCatalogEntry {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun need(k: String) = s(k) ?: error("catalogue entry ${s("id")} has no $k")
        return GgufCatalogEntry(
            id = need("id"),
            name = need("name"),
            description = need("description"),
            repo = need("repo"),
            file = need("file"),
            revision = need("revision"),
            sizeBytes = (o["sizeBytes"] as? JsonPrimitive)?.longOrNull ?: error("catalogue entry ${s("id")} has no sizeBytes"),
            sha256 = need("sha256").lowercase(),
            licenseName = need("licenseName"),
            licenseUrl = s("licenseUrl"),
            architecture = need("architecture"),
            contextLength = (o["contextLength"] as? JsonPrimitive)?.intOrNull,
            sizeLabel = s("sizeLabel"),
            kvBytesPerToken = (o["kvBytesPerToken"] as? JsonPrimitive)?.longOrNull,
            tools = (o["tools"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }
}
