package dev.locus.core.ondevice.gguf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** One file in one Hugging Face model repo at one revision (a commit SHA once [pinned]). */
data class HfFileRef(val repo: String, val revision: String, val path: String) {
    val pinned: Boolean get() = HfHub.isCommit(revision)
    val fileName: String get() = path.substringAfterLast('/')
    val resolveUrl: String get() = "https://${HfHub.HOST}/$repo/resolve/${HfHub.encode(revision)}/${HfHub.encodePath(path)}"
    /** "unsloth/Qwen3.5-2B-GGUF@f6d5376" */
    val label: String get() = "$repo@${if (pinned) revision.take(7) else revision}"
}

/** A search hit: a model repo with GGUF files. */
data class HfRepo(
    val id: String,
    val downloads: Long,
    val likes: Long,
    /** Downloading needs a Hugging Face account that accepted the repo's terms ("auto" or "manual" on the Hub). */
    val gated: Boolean,
    val license: String?,
    val lastModified: String?,
)

/** A repo's details: the commit its main branch is at now, whether it's gated, and its card's licence. */
data class HfRepoInfo(val id: String, val sha: String?, val gated: Boolean, val license: String?)

/** What a .gguf file in a repo is, judged by its name (the header confirms it after download). */
sealed interface HfFileKind {
    data object Model : HfFileKind
    /** One part of a model split with llama-gguf-split; Locus loads single files only. */
    data class Split(val part: Int, val count: Int) : HfFileKind
    /** A vision projector (mmproj), useless without its model and not loaded by Locus. */
    data object Projector : HfFileKind
    /** A multi-token-prediction or draft head (mtp-…), not a chat model on its own. */
    data object Draft : HfFileKind
    /** An importance matrix or other non-model file named .gguf. */
    data object Other : HfFileKind
}

data class HfFile(val path: String, val sizeBytes: Long, val sha256: String?, val kind: HfFileKind) {
    val fileName: String get() = path.substringAfterLast('/')
    val quant: String? get() = GgufQuant.fromFileName(fileName)
    val usable: Boolean get() = kind == HfFileKind.Model && sha256 != null
}

/** How a Hub API call ended when it didn't give an answer. */
sealed class HfError(message: String) : Exception(message) {
    class Offline(message: String) : HfError(message)
    /** HTTP 429. [retryAfterSeconds] from the RateLimit (t=…) or Retry-After header. */
    class RateLimited(val retryAfterSeconds: Long?) : HfError(
        "Hugging Face is limiting requests from this network" + (retryAfterSeconds?.let { ". Try again in ${HfHub.waitLabel(it)}." } ?: ". Try again in a few minutes."),
    )
    class Gated(repo: String) : HfError("$repo is gated: Hugging Face only lets accounts that accepted its terms download it, and Locus downloads without an account. Search for another upload of the same model.")
    class NotFound(what: String) : HfError("$what wasn't found on Hugging Face. It may be private, renamed or deleted.")
    class Http(code: Int) : HfError("Hugging Face answered HTTP $code.")
    class Malformed(detail: String) : HfError("Hugging Face's answer wasn't understood: $detail")
}

/**
 * The Hugging Face Hub API calls Locus makes, as URLs and parsers, so they're tested against
 * recorded answers (docs/design/llamacpp.md, "Getting models"):
 *
 * - `GET /api/models?search=…&filter=gguf&sort=downloads|trendingScore&direction=-1&limit=…&expand[]=…`
 * - `GET /api/models/{repo}?expand[]=cardData&expand[]=gated&expand[]=sha`
 * - `GET /api/models/{repo}/revision/{revision}`: the commit a branch or tag is at.
 * - `GET /api/models/{repo}/tree/{commit}?recursive=true`: every file with its size, and for Git LFS
 *   files `lfs.oid`, the SHA-256 of the content, which the download is checked against.
 *
 * No token is ever sent: everything Locus reads is public.
 */
object HfHub {
    const val HOST = "huggingface.co"
    private const val API = "https://$HOST/api"
    private val json = Json { ignoreUnknownKeys = true }
    private val commit = Regex("^[0-9a-f]{40}$")
    private val repoId = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")
    private val splitName = Regex("""-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)

    enum class Sort(val api: String, val label: String) { Downloads("downloads", "Popular"), Trending("trendingScore", "Trending") }

    fun isCommit(revision: String) = commit.matches(revision)

    fun isRepoId(id: String) = repoId.matches(id) && ".." !in id

    fun searchUrl(query: String, sort: Sort = Sort.Downloads, limit: Int = 30): String =
        "$API/models?search=${encode(query.trim())}&filter=gguf&sort=${sort.api}&direction=-1&limit=$limit" +
            "&expand[]=downloads&expand[]=likes&expand[]=gated&expand[]=cardData&expand[]=lastModified"

    fun repoUrl(repo: String) = "$API/models/$repo?expand[]=cardData&expand[]=gated&expand[]=sha"

    fun revisionUrl(repo: String, revision: String) = "$API/models/$repo/revision/${encode(revision)}"

    fun treeUrl(repo: String, commit: String) = "$API/models/$repo/tree/$commit?recursive=true"

    fun parseSearch(text: String): List<HfRepo> = array(text).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("id")?.takeIf(::isRepoId) ?: return@mapNotNull null
        HfRepo(
            id = id,
            downloads = o.long("downloads") ?: 0,
            likes = o.long("likes") ?: 0,
            gated = gated(o["gated"]),
            license = license(o),
            lastModified = o.str("lastModified"),
        )
    }

    fun parseRepo(text: String): HfRepoInfo {
        val o = obj(text)
        val id = o.str("id") ?: throw HfError.Malformed("no repo id")
        return HfRepoInfo(id, o.str("sha")?.takeIf(::isCommit), gated(o["gated"]), license(o))
    }

    fun parseRevision(text: String): String = obj(text).str("sha")?.takeIf(::isCommit) ?: throw HfError.Malformed("no commit")

    /** The .gguf files in a tree listing, largest model first after the usable ones. */
    fun parseTree(text: String): List<HfFile> = array(text).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        if (o.str("type") != "file") return@mapNotNull null
        val path = o.str("path") ?: return@mapNotNull null
        if (!path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
        val lfs = o["lfs"] as? JsonObject
        val sha = lfs?.str("oid")?.lowercase()?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
        HfFile(path, lfs?.long("size") ?: o.long("size") ?: 0, sha, classify(path))
    }.sortedWith(compareBy<HfFile>({ !it.usable }, { it.sizeBytes }))

    fun classify(path: String): HfFileKind {
        val name = path.substringAfterLast('/').lowercase()
        splitName.find(name)?.let { m ->
            val part = m.groupValues[1].toInt()
            val count = m.groupValues[2].toInt()
            if (count > 1) return HfFileKind.Split(part, count)
        }
        return when {
            "mmproj" in name -> HfFileKind.Projector
            name.startsWith("mtp-") || "/mtp/" in "/${path.lowercase()}" -> HfFileKind.Draft
            "imatrix" in name -> HfFileKind.Other
            else -> HfFileKind.Model
        }
    }

    /**
     * A huggingface.co link to a file, as copied from the site: `/{owner}/{repo}/resolve/{rev}/{path}`
     * or `/blob/…` (the file's page), with or without `?download=true`; `hf.co` is the Hub's short
     * domain. Null for anything else: other hosts, datasets and Spaces, or a path that climbs out.
     */
    fun parseFileUrl(url: String): HfFileRef? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host != HOST && host != "www.$HOST" && host != "hf.co") return null
        if (uri.port != -1 && uri.port != 443) return null
        val parts = (uri.rawPath ?: return null).trim('/').split('/')
        if (parts.size < 5 || parts[2] !in setOf("resolve", "blob")) return null
        val repo = "${decode(parts[0])}/${decode(parts[1])}"
        if (!isRepoId(repo)) return null
        val revision = decode(parts[3]).takeIf { it.isNotBlank() } ?: return null
        val segments = parts.drop(4).map(::decode)
        if (segments.any { it.isEmpty() || it == "." || it == ".." || '\\' in it }) return null
        return HfFileRef(repo, revision, segments.joinToString("/"))
    }

    /** Seconds to wait after a 429, from `RateLimit: "api";r=0;t=55` or `Retry-After: 55`. */
    fun retryAfterSeconds(rateLimit: String?, retryAfter: String?): Long? =
        rateLimit?.let { Regex("""(?:^|;)\s*t=(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?: retryAfter?.trim()?.toLongOrNull()

    fun waitLabel(seconds: Long): String = if (seconds < 90) "$seconds s" else "${(seconds + 59) / 60} min"

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun encodePath(path: String): String = path.split('/').joinToString("/", transform = ::encode)

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun gated(e: JsonElement?): Boolean = when (e) {
        is JsonPrimitive -> e.booleanOrNull ?: (e.contentOrNull?.let { it != "false" && it.isNotBlank() } ?: false)
        else -> false
    }

    private fun license(o: JsonObject): String? = ((o["cardData"] as? JsonObject)?.get("license") as? JsonPrimitive)?.contentOrNull
        ?: (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.firstOrNull { it.startsWith("license:") }?.removePrefix("license:")

    private fun array(text: String): JsonArray =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: throw HfError.Malformed("expected a list")

    private fun obj(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: throw HfError.Malformed("expected an object")

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
}
