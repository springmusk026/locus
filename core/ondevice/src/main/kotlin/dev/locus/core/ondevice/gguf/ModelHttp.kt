package dev.locus.core.ondevice.gguf

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import java.io.InputStream

/**
 * The network model downloads use (docs/design/llamacpp.md, "Getting models"). The app's
 * implementation (core/data, `PublicModelNetwork`) is fetch_url's: the app's OkHttp client with a DNS
 * that refuses any private, loopback or link-local answer at connect time, no proxy, and no
 * automatic redirects.
 */
interface ModelNetwork {
    /** One GET of [url] with [headers], never following a redirect; [block] gets the response while its body is open. */
    suspend fun <T> get(url: Url, headers: Map<String, String>, block: suspend (Response) -> T): T

    /** Why [url] may not be fetched (a local or private address), or null when it may. */
    suspend fun refusal(url: Url): String?

    interface Response {
        val status: Int
        fun header(name: String): String?
        /** The body, read on an IO thread. */
        val body: InputStream
    }
}

/** A download or API call that failed. [retryable]: trying again later can work (network, server). */
class DownloadException(message: String, val retryable: Boolean = true) : Exception(message)

/**
 * HTTPS GETs for models: the Hub API and file downloads. Every hop, the first and each redirect, must
 * be https, carry no user name or password, and pass [ModelNetwork.refusal], so a pasted URL or a
 * redirect can't reach the phone's LAN or Locus's own listeners; the DNS check then pins that to the
 * address actually connected to. Only a User-Agent and a Range header are ever sent: no cookies, no
 * token, nothing about the user.
 */
class ModelHttp(private val network: ModelNetwork) {

    /** A response that isn't a redirect, with the URL it came from. */
    class Response(val url: Url, private val raw: ModelNetwork.Response) {
        val status: Int get() = raw.status
        fun header(name: String): String? = raw.header(name)
        val body: InputStream get() = raw.body
    }

    /** Why [url] can't be a hop: null when it can. */
    suspend fun refusal(url: Url): String? = when {
        url.protocol.name != "https" -> "Only https:// addresses can be downloaded from: plain http could be changed on the way."
        url.user != null || url.password != null -> "Addresses with a user name or password aren't supported."
        url.host.isBlank() -> "The address has no host."
        else -> network.refusal(url)
    }

    /**
     * GETs [start], from byte [from] when it's above 0, following up to [MAX_REDIRECTS] redirects;
     * [block] gets the final response while its body is open.
     */
    suspend fun <T> open(start: String, from: Long = 0, block: suspend (Response) -> T): T {
        var url = runCatching { Url(start) }.getOrNull() ?: throw DownloadException("Not a valid address.", retryable = false)
        val headers = buildMap {
            put("User-Agent", USER_AGENT)
            put("Accept-Encoding", "identity")
            if (from > 0) put("Range", "bytes=$from-")
        }
        repeat(MAX_REDIRECTS + 1) {
            refusal(url)?.let { throw DownloadException(it, retryable = false) }
            val outcome: Any? = try {
                network.get(url, headers) { raw ->
                    val location = raw.header("Location")
                    if (raw.status in 300..399 && location != null) {
                        Hop(URLBuilder(url).takeFrom(location).build())
                    } else {
                        Done(block(Response(url, raw)))
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                // The pinned DNS refuses a private answer with a "Blocked:" message; anything else is offline.
                if (e.message?.startsWith("Blocked:") == true) throw DownloadException(e.message!!, retryable = false)
                throw DownloadException("No connection to ${url.host}. Check the internet connection.")
            } catch (e: java.io.IOException) {
                throw DownloadException("The connection to ${url.host} broke: ${e.message ?: e::class.simpleName}.")
            }
            when (outcome) {
                is Done<*> -> @Suppress("UNCHECKED_CAST") return outcome.value as T
                is Hop -> url = outcome.to
            }
        }
        throw DownloadException("Too many redirects (more than $MAX_REDIRECTS).", retryable = false)
    }

    /** A Hub API answer as text, with the Hub's errors mapped to [HfError]. Only huggingface.co. */
    suspend fun hubText(url: String): String {
        val host = runCatching { Url(url).host }.getOrNull()
        require(host == HfHub.HOST) { "Hub calls go to ${HfHub.HOST} only" }
        return try {
            open(url) { r ->
                if (r.status !in 200..299) throw hubError(r, url)
                // Never more than the cap in memory, whatever the server sends (readNBytes is API 33).
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = r.body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_API_BYTES) throw HfError.Malformed("the answer is too large")
                }
                out.toByteArray().decodeToString()
            }
        } catch (e: DownloadException) {
            throw if (e.retryable) HfError.Offline(e.message.orEmpty()) else e
        }
    }

    private class Hop(val to: Url)
    private class Done<T>(val value: T)

    companion object {
        const val MAX_REDIRECTS = 10
        const val MAX_API_BYTES = 8 * 1024 * 1024
        /** Generic on purpose: says what's asking, nothing about the phone or the user. */
        const val USER_AGENT = "Locus (on-device model download)"

        /** The [HfError] for a Hub response that isn't a success. */
        fun hubError(r: Response, what: String): HfError = when (r.status) {
            429 -> HfError.RateLimited(HfHub.retryAfterSeconds(r.header("RateLimit"), r.header("Retry-After")))
            401, 403 -> if (r.header("X-Error-Code") == "GatedRepo") HfError.Gated(repoOf(what)) else HfError.NotFound(repoOf(what))
            404 -> HfError.NotFound(repoOf(what))
            else -> HfError.Http(r.status)
        }

        private fun repoOf(url: String): String =
            Regex("""huggingface\.co/(?:api/models/)?([^/?]+/[^/?]+)""").find(url)?.groupValues?.get(1) ?: "The file"
    }
}
