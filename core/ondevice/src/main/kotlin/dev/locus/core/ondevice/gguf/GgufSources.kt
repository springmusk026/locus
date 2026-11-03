package dev.locus.core.ondevice.gguf

import dev.locus.core.ondevice.DeviceMemory
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A model file Locus is about to download, with everything the confirm step shows: where it comes
 * from, whether its checksum is known, its licence, and (from its header, read before downloading)
 * what it is, whether it calls tools and the memory it needs on this phone.
 */
data class GgufOffer(
    val name: String,
    val fileName: String,
    /** The URL downloaded: a huggingface.co `resolve` URL pinned to a commit, or the pasted https URL. */
    val url: String,
    val sizeBytes: Long?,
    /** The SHA-256 the Hub publishes for the file; null for an unverified source. */
    val sha256: String?,
    /** "unsloth/Qwen3.5-2B-GGUF@f6d5376", or the host of an unverified URL. */
    val source: String,
    val license: String?,
    val catalogId: String? = null,
    val architecture: String? = null,
    val quant: String? = null,
    val sizeLabel: String? = null,
    /** Null when the header couldn't be read before downloading. */
    val tools: Boolean? = null,
    val ramBytes: Long? = null,
    /** Why the file can't be used ([GgufImportCheck.problem]); the download is refused. */
    val problem: String? = null,
) {
    val verified: Boolean get() = sha256 != null
}

/**
 * Turns the three sources into [GgufOffer]s: a catalogue entry (all known), a Hub file (looked up
 * and pinned now), or a pasted URL (a Hub link is treated as a Hub file; any other https URL is
 * unverified). Hub calls go only to huggingface.co, without a token.
 */
class GgufSources(private val http: ModelHttp) {

    suspend fun search(query: String, sort: HfHub.Sort): List<HfRepo> = HfHub.parseSearch(http.hubText(HfHub.searchUrl(query, sort)))

    /** A repo's details and its .gguf files, at the commit its main branch is at now. */
    suspend fun files(repo: String): Pair<HfRepoInfo, List<HfFile>> {
        val info = HfHub.parseRepo(http.hubText(HfHub.repoUrl(repo)))
        val sha = info.sha ?: HfHub.parseRevision(http.hubText(HfHub.revisionUrl(repo, "main")))
        return info.copy(sha = sha) to HfHub.parseTree(http.hubText(HfHub.treeUrl(repo, sha)))
    }

    /** A repo's .gguf files at [commit] (a catalogue entry's pinned revision). */
    suspend fun tree(repo: String, commit: String): List<HfFile> = HfHub.parseTree(http.hubText(HfHub.treeUrl(repo, commit)))

    fun offer(entry: GgufCatalogEntry, memory: DeviceMemory) = GgufOffer(
        name = entry.name,
        fileName = entry.file,
        url = entry.source.resolveUrl,
        sizeBytes = entry.sizeBytes,
        sha256 = entry.sha256,
        source = entry.source.label,
        license = entry.licenseName,
        catalogId = entry.id,
        architecture = entry.architecture,
        quant = entry.quant,
        sizeLabel = entry.sizeLabel,
        tools = entry.tools,
        ramBytes = entry.ramBytes(memory),
    )

    /**
     * A Hub file as an offer: the revision resolved to a commit, size and SHA-256 from the tree at
     * that commit, licence from the model card, then the header read from the pinned URL.
     */
    suspend fun offer(ref: HfFileRef, memory: DeviceMemory): GgufOffer {
        val info = HfHub.parseRepo(http.hubText(HfHub.repoUrl(ref.repo)))
        if (info.gated) throw HfError.Gated(ref.repo)
        val commit = if (ref.pinned) ref.revision else HfHub.parseRevision(http.hubText(HfHub.revisionUrl(ref.repo, ref.revision)))
        val pinned = ref.copy(revision = commit)
        val file = HfHub.parseTree(http.hubText(HfHub.treeUrl(ref.repo, commit))).firstOrNull { it.path == ref.path }
            ?: throw HfError.NotFound("${ref.path} in ${ref.repo}")
        kindProblem(file.kind)?.let { throw DownloadException(it, retryable = false) }
        val sha = file.sha256 ?: throw DownloadException("Hugging Face publishes no checksum for this file, so Locus can't verify it.", retryable = false)
        val base = GgufOffer(
            name = file.fileName.removeSuffix(".gguf"),
            fileName = file.fileName,
            url = pinned.resolveUrl,
            sizeBytes = file.sizeBytes,
            sha256 = sha,
            source = pinned.label,
            license = info.license,
            quant = file.quant,
        )
        return withHeader(base, memory)
    }

    /**
     * A pasted URL: https only. A huggingface.co file link becomes a verified Hub offer; any other
     * host is read for its header (so a non-GGUF is refused before downloading) and stays unverified.
     */
    suspend fun offer(url: String, memory: DeviceMemory): GgufOffer {
        val trimmed = url.trim()
        val parsed = runCatching { Url(trimmed) }.getOrNull()
        if (parsed == null || !(trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true))) {
            throw DownloadException("Paste a full address starting with https://.", retryable = false)
        }
        if (parsed.protocol.name != "https") throw DownloadException("Only https:// addresses: a plain http download could be changed on the way.", retryable = false)
        HfHub.parseFileUrl(trimmed)?.let { return offer(it, memory) }
        val host = parsed.host.lowercase()
        if (host == HfHub.HOST || host.endsWith(".${HfHub.HOST}") || host == "hf.co") {
            throw DownloadException("That's a Hugging Face page, not a file. Open the file and copy its download link (…/resolve/…), or use Search.", retryable = false)
        }
        http.refusal(parsed)?.let { throw DownloadException(it, retryable = false) }
        val fileName = parsed.segments.lastOrNull()?.takeIf { it.isNotBlank() } ?: "model.gguf"
        val base = GgufOffer(
            name = fileName.removeSuffix(".gguf"),
            fileName = if (fileName.endsWith(".gguf", ignoreCase = true)) fileName else "$fileName.gguf",
            url = trimmed,
            sizeBytes = null,
            sha256 = null,
            source = host,
            license = null,
            quant = GgufQuant.fromFileName(fileName),
        )
        return withHeader(base, memory)
    }

    /** Reads the file's header (its first megabytes) and fills in what it says; refuses what isn't a usable GGUF. */
    private suspend fun withHeader(offer: GgufOffer, memory: DeviceMemory): GgufOffer = http.open(offer.url) { r ->
        if (r.status !in 200..299) {
            throw when (r.status) {
                401, 403, 404, 429 -> ModelHttp.hubError(r, offer.url)
                else -> DownloadException("The server answered HTTP ${r.status}.")
            }
        }
        val length = r.header("Content-Length")?.toLongOrNull()
        val header = try {
            withContext(Dispatchers.IO) { GgufReader.read(r.body) }
        } catch (e: GgufException) {
            throw DownloadException(if (e.corrupt) "This isn't a GGUF model file: ${e.message}" else e.message.orEmpty(), retryable = false)
        }
        val size = offer.sizeBytes ?: length
        val problem = GgufImportCheck.problem(header, size, offer.fileName)
        val kv = GgufMemory.kvBytesPerToken(header.blockCount, header.headCountKv, header.keyLength, header.valueLength)
        val ram = size?.let { GgufMemory.estimate(it, kv, GgufMemory.chooseContext(header.contextLength, it, kv, memory)) }
        offer.copy(
            name = header.name ?: offer.name,
            sizeBytes = size,
            architecture = header.architecture,
            quant = GgufQuant.name(header.fileType) ?: offer.quant,
            sizeLabel = header.sizeLabel,
            license = offer.license ?: header.license,
            tools = GgufTemplate.supportsTools(header.chatTemplate),
            ramBytes = ram,
            problem = problem,
        )
    }

    companion object {
        /** Why a Hub file of this kind can't be downloaded, or null when it can. */
        fun kindProblem(kind: HfFileKind): String? = when (kind) {
            HfFileKind.Model -> null
            is HfFileKind.Split -> "This model is split into ${kind.count} files, and Locus loads single-file models only. Pick a smaller quantisation that comes as one file."
            HfFileKind.Projector -> "This is a vision projector (mmproj), not a model. Pick the model's own GGUF file."
            HfFileKind.Draft -> "This is a draft (MTP) head that speeds up another model, not a chat model on its own."
            HfFileKind.Other -> "This isn't a model file (an importance matrix or similar)."
        }
    }
}
