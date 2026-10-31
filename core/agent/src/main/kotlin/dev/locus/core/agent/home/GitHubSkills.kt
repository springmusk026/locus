package dev.locus.core.agent.home

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A GitHub link to a skill: a repository root (`github.com/<owner>/<repo>`), a folder
 * (`…/tree/<ref>/<path>`) or a SKILL.md (`…/blob/<ref>/<path>/SKILL.md`).
 *
 * In a tree or blob link the ref and the path share one run of segments, and refs may contain
 * slashes (`feature/x`), so [candidates] lists every split, shortest ref first. Git can't hold both
 * a ref `feature` and a ref `feature/x`, so at most one split names a real ref and path.
 */
data class GitHubSkillLink(val owner: String, val repo: String, val segments: List<String>) {
    val isRoot: Boolean get() = segments.isEmpty()

    /** (ref, path) pairs to try; a null ref means the default branch. */
    fun candidates(): List<Pair<String?, String>> =
        if (segments.isEmpty()) listOf(null to "")
        else (1..segments.size).map { k -> segments.take(k).joinToString("/") to segments.drop(k).joinToString("/") }.take(MAX_CANDIDATES)

    companion object {
        private const val MAX_CANDIDATES = 8
        private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")

        fun parse(input: String): GitHubSkillLink {
            var url = input.trim()
            if (!url.contains("://")) url = "https://$url"
            val scheme = url.substringBefore("://").lowercase()
            val rest = url.substringAfter("://").substringBefore('#').substringBefore('?')
            val host = rest.substringBefore('/').lowercase()
            if (scheme != "https" && scheme != "http") throw SkillImportException("Use a https://github.com/… link.")
            if (host != "github.com" && host != "www.github.com") {
                throw SkillImportException("Only github.com links can be imported. For other places, download the skill as a zip and import the file.")
            }
            val parts = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
            if (parts.size < 2) throw SkillImportException("Link to a repository or a skill folder, like https://github.com/owner/repo/tree/main/skills/my-skill.")
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            if (!NAME.matches(owner) || !NAME.matches(repo)) throw SkillImportException("That doesn't look like a GitHub repository link.")
            if (parts.size == 2) return GitHubSkillLink(owner, repo, emptyList())
            val after = parts.drop(3)
            if (after.any { it == ".." || it == "." }) throw SkillImportException("The link has an unsafe path.")
            return when (parts[2]) {
                "tree" -> {
                    if (after.isEmpty()) throw SkillImportException("The link is missing a branch after /tree/.")
                    GitHubSkillLink(owner, repo, after)
                }
                "blob" -> {
                    if (after.size < 2 || after.last() != "SKILL.md") {
                        throw SkillImportException("That links to a single file. Link to the skill's folder, or to its SKILL.md.")
                    }
                    GitHubSkillLink(owner, repo, after.dropLast(1))
                }
                else -> throw SkillImportException("Link to a repository root or a folder (…/tree/<branch>/<folder>).")
            }
        }
    }
}

/**
 * Downloads a skill folder with GitHub's REST contents API
 * (docs.github.com/en/rest/repos/contents): one request per folder listing and one per file, with
 * the raw media type. Requests are unauthenticated (60 an hour per IP) unless the user saved a
 * GitHub token under Settings → Integrations, which also allows private repositories.
 * Symlinks and submodules are refused, and limits are checked from the listings before anything
 * is downloaded. Nothing is written to disk here.
 */
class GitHubSkillFetcher(
    private val http: HttpClient,
    private val secrets: SecretResolver,
    private val limits: SkillImportLimits = SkillImportLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(url: String): SkillBundle {
        val link = GitHubSkillLink.parse(url)
        val token = secrets.resolve(TOKEN_SECRET)?.takeIf { it.isNotBlank() }
        val (ref, path, listing) = locate(link, token)
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var folders = 1
        val queue = ArrayDeque(listOf("" to listing))
        while (queue.isNotEmpty()) {
            val (prefix, entries) = queue.removeFirst()
            for (entry in entries) {
                val o = entry as? JsonObject ?: continue
                val name = o.str("name") ?: continue
                val rel = SkillImport.safePath(prefix + name, limits)
                if (SkillImport.isJunk(rel)) continue
                when (o.str("type")) {
                    "dir" -> {
                        if (++folders > limits.maxGitHubFolders) throw SkillImportException("The skill has more than ${limits.maxGitHubFolders} folders.")
                        val sub = get(contentsPath(link, o.str("path") ?: continue), ref, token)
                        queue.addLast("$rel/" to (parse(sub) as? JsonArray ?: throw SkillImportException("GitHub sent an unexpected answer for $rel.")))
                    }
                    "file" -> {
                        if (o["download_url"]?.jsonPrimitive?.contentOrNull == null) {
                            throw SkillImportException("$rel is a git submodule. Skills with submodules can't be imported.")
                        }
                        val size = o["size"]?.jsonPrimitive?.longOrNull ?: 0
                        if (size > limits.maxFileBytes) throw SkillImportException("$rel is larger than ${SkillImport.mb(limits.maxFileBytes)}, the limit for one file in a skill.")
                        total += size
                        if (total > limits.maxTotalBytes) throw SkillImportException("The skill is larger than ${SkillImport.mb(limits.maxTotalBytes)}, the limit for one skill.")
                        if (files.size + 1 > limits.maxFiles) throw SkillImportException("The skill has more than ${limits.maxFiles} files.")
                        files[rel] = download(link, o.str("path") ?: rel, ref, token, rel)
                    }
                    "symlink" -> throw SkillImportException("$rel is a symbolic link. Skills with links can't be imported.")
                    "submodule" -> throw SkillImportException("$rel is a git submodule. Skills with submodules can't be imported.")
                    else -> throw SkillImportException("$rel has a type Locus can't import.")
                }
            }
        }
        if ("SKILL.md" !in files) {
            throw SkillImportException(
                "There is no SKILL.md in ${if (path.isEmpty()) "the repository's top folder" else path}. Link to the folder that holds the skill's SKILL.md.",
            )
        }
        val source = SkillSource(
            kind = "github",
            url = url.trim(),
            repo = "${link.owner}/${link.repo}",
            ref = ref,
            path = path,
            commit = runCatching { latestCommit(link, ref, path, token) }.getOrNull(),
            importedAt = clock(),
        )
        return SkillImport.bundle(files, source, limits)
    }

    private data class Located(val ref: String?, val path: String, val listing: JsonArray)

    private suspend fun locate(link: GitHubSkillLink, token: String?): Located {
        for ((ref, path) in link.candidates()) {
            val response = http.get(API + contentsPath(link, path)) {
                common(token)
                if (ref != null) parameter("ref", ref)
            }
            if (response.status.value == 404) continue
            val body = parse(checked(response))
            if (body !is JsonArray) throw SkillImportException("That link points to a file. Link to the skill's folder instead.")
            return Located(ref, path, body)
        }
        throw SkillImportException(
            if (token == null) "GitHub couldn't find that repository, branch or folder. Check the link. Private repositories need a GitHub token in Settings → Integrations → GitHub."
            else "GitHub couldn't find that repository, branch or folder. Check the link, and that your GitHub token can read the repository.",
        )
    }

    private suspend fun get(path: String, ref: String?, token: String?): String {
        val response = http.get(API + path) {
            common(token)
            if (ref != null) parameter("ref", ref)
        }
        if (response.status.value == 404) throw SkillImportException("GitHub couldn't find part of the skill. It may have changed while importing; try again.")
        return checked(response)
    }

    private suspend fun download(link: GitHubSkillLink, repoPath: String, ref: String?, token: String?, rel: String): ByteArray {
        val response = http.get(API + contentsPath(link, repoPath)) {
            common(token, raw = true)
            if (ref != null) parameter("ref", ref)
        }
        checkStatus(response)
        val bytes = response.readRawBytes()
        if (bytes.size > limits.maxFileBytes) throw SkillImportException("$rel is larger than ${SkillImport.mb(limits.maxFileBytes)}, the limit for one file in a skill.")
        return bytes
    }

    private suspend fun latestCommit(link: GitHubSkillLink, ref: String?, path: String, token: String?): String? {
        val response = http.get("$API/repos/${link.owner}/${link.repo}/commits") {
            common(token)
            if (ref != null) parameter("sha", ref)
            if (path.isNotEmpty()) parameter("path", path)
            parameter("per_page", 1)
        }
        if (response.status.value != 200) return null
        return ((parse(response.bodyAsText()) as? JsonArray)?.firstOrNull() as? JsonObject)?.str("sha")
    }

    private fun contentsPath(link: GitHubSkillLink, path: String) =
        "/repos/${link.owner}/${link.repo}/contents" + path.split('/').filter { it.isNotEmpty() }.joinToString("") { "/" + it.encodeURLPathPart() }

    private fun HttpRequestBuilder.common(token: String?, raw: Boolean = false) {
        header("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
        header("X-GitHub-Api-Version", API_VERSION)
        header("User-Agent", "Locus")
        if (token != null) header("Authorization", "Bearer $token")
    }

    private suspend fun checked(response: HttpResponse): String {
        checkStatus(response)
        return response.bodyAsText()
    }

    private suspend fun checkStatus(response: HttpResponse) {
        val status = response.status.value
        if (status in 200..299) return
        if ((status == 403 || status == 429) && response.headers["x-ratelimit-remaining"] == "0") {
            val reset = response.headers["x-ratelimit-reset"]?.toLongOrNull()?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it * 1000)) }
            throw SkillImportException(
                "GitHub's hourly request limit is used up" + (reset?.let { "; it resets at $it" } ?: "") +
                    ". Adding a GitHub token in Settings → Integrations → GitHub raises the limit from 60 to 5,000 requests an hour.",
            )
        }
        if (status == 401) throw SkillImportException("GitHub rejected the token saved in Settings → Integrations → GitHub. Replace it, or remove it to import public skills without one.")
        val message = runCatching { (parse(response.bodyAsText()) as? JsonObject)?.str("message") }.getOrNull()
        throw SkillImportException("GitHub answered $status${message?.let { ": ${it.take(200)}" } ?: ""}.")
    }

    private fun parse(text: String): JsonElement = runCatching { json.parseToJsonElement(text) }
        .getOrElse { throw SkillImportException("GitHub sent an answer Locus couldn't read.") }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        const val API = "https://api.github.com"
        /** The same REST API version the GitHub integration uses. */
        const val API_VERSION = "2026-03-10"
        /** The vault name of the GitHub integration's token (see Integrations.github). */
        const val TOKEN_SECRET = "integration-github"
    }
}
