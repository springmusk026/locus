package dev.locus.core.data.updates

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What one ask for the latest release came back with. */
sealed interface CheckOutcome {
    /** A release, with the ETag to send next time. */
    data class Fresh(val release: Release, val etag: String?) : CheckOutcome

    /** 304: the release last fetched is still the latest. */
    data object NotModified : CheckOutcome

    /** 404: the repository has no published release. */
    data object NoRelease : CheckOutcome

    /** GitHub's rate limit for this address is used up until [resetAt] (epoch ms). */
    data class RateLimited(val resetAt: Long) : CheckOutcome

    /** No connection, or it dropped. */
    data object Offline : CheckOutcome

    /** Any other answer: [code] 0 when the body wasn't a release. */
    data class Failed(val code: Int) : CheckOutcome
}

/**
 * GitHub's "latest release" for Locus: one unauthenticated GET with no cookies, no token and no
 * identifiers, only the headers GitHub asks for, a fixed User-Agent (no version or device) and the
 * last ETag (docs/design/updates.md).
 */
class ReleaseSource(private val http: HttpClient, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun latest(etag: String?): CheckOutcome = try {
        withTimeout(TIMEOUT_MS) {
            val response = http.get(LATEST_URL) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header("X-GitHub-Api-Version", API_VERSION)
                header(HttpHeaders.UserAgent, USER_AGENT)
                etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
            val code = response.status.value
            rateLimit(code, response.headers[HttpHeaders.RetryAfter], response.headers["x-ratelimit-remaining"], response.headers["x-ratelimit-reset"])
                ?.let { return@withTimeout CheckOutcome.RateLimited(it) }
            when (response.status) {
                HttpStatusCode.NotModified -> CheckOutcome.NotModified
                HttpStatusCode.NotFound -> CheckOutcome.NoRelease
                HttpStatusCode.OK -> ReleaseJson.parse(response.bodyAsText())?.let { CheckOutcome.Fresh(it, response.headers[HttpHeaders.ETag]) } ?: CheckOutcome.Failed(0)
                else -> CheckOutcome.Failed(code)
            }
        }
    } catch (e: TimeoutCancellationException) {
        CheckOutcome.Offline
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        CheckOutcome.Offline
    } catch (e: Exception) {
        // Ktor wraps engine failures (no route, DNS) in its own exceptions.
        if (e.isNetwork()) CheckOutcome.Offline else CheckOutcome.Failed(0)
    }

    /**
     * When a limited answer may be retried, as GitHub's rate-limit docs say
     * (https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api): after
     * `retry-after` seconds; else, with none remaining, at `x-ratelimit-reset`; else, for a 403 or 429
     * that says nothing, a minute on. Null for an answer that isn't limited.
     */
    internal fun rateLimit(code: Int, retryAfter: String?, remaining: String?, reset: String?): Long? {
        val limitedCode = code == 403 || code == 429
        retryAfter?.trim()?.toLongOrNull()?.takeIf { limitedCode }?.let { return now() + it * 1000 }
        if (remaining?.trim() == "0" && (limitedCode || code >= 400)) {
            return reset?.trim()?.toLongOrNull()?.let { it * 1000 } ?: (now() + 60_000)
        }
        return if (code == 429) now() + 60_000 else null
    }

    /** A small text file from a release (its checksums); null when it can't be had. */
    suspend fun text(url: String): String? = try {
        withTimeout(TIMEOUT_MS) {
            val response = http.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }
            if (response.status == HttpStatusCode.OK) response.bodyAsText().take(MAX_TEXT) else null
        }
    } catch (e: TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Streams [url] into [into], reporting bytes as they come, and returns the file's SHA-256. Throws
     * [IOException] for a dropped connection and [DownloadException] for an HTTP error.
     */
    suspend fun download(url: String, into: File, onProgress: suspend (bytes: Long, total: Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        http.prepareGet(url) { header(HttpHeaders.UserAgent, USER_AGENT) }.execute { response ->
            if (response.status != HttpStatusCode.OK) throw DownloadException(response.status.value)
            val total = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            val buf = ByteArray(64 * 1024)
            var bytes = 0L
            var reported = 0L
            into.outputStream().use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    bytes += n
                    if (bytes - reported >= REPORT_EVERY || bytes == total) {
                        reported = bytes
                        onProgress(bytes, total)
                    }
                }
            }
            if (total > 0 && bytes != total) throw IOException("Download ended early: $bytes of $total bytes")
            onProgress(bytes, total)
        }
        return ReleaseFiles.hex(md.digest())
    }

    companion object {
        const val REPO = "springmusk026/locus"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_URL = "https://github.com/$REPO/releases"
        /** The REST API version Locus was written against (https://docs.github.com/en/rest/about-the-rest-api/api-versions). */
        const val API_VERSION = "2022-11-28"
        /** GitHub requires a User-Agent; this one says nothing about the phone or the version. */
        const val USER_AGENT = "Locus-update-check"
        private const val TIMEOUT_MS = 30_000L
        private const val MAX_TEXT = 64 * 1024
        private const val REPORT_EVERY = 256L * 1024
    }
}

class DownloadException(val code: Int) : IOException("Download failed (HTTP $code)")

internal fun Throwable.isNetwork(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is IOException || it::class.java.name.contains("UnresolvedAddress") }
