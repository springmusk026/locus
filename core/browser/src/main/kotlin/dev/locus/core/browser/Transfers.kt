package dev.locus.core.browser

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Base64

/**
 * File names and data URLs for downloads into, and uploads from, the workspace, and the HTTP fetch of a
 * download. Plain JVM code, so unit-tested.
 */
object Transfers {
    const val DOWNLOAD_DIR = "downloads"
    const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024
    const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024
    const val MAX_UPLOAD_FILES = 10

    /** A safe single file name: no folders, control characters or leading dots; at most 120 characters. */
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f\\u007f:*?\"<>|]"), "_")
            .trim().trimStart('.').trim()
        if (base.isEmpty()) return "download"
        if (base.length <= 120) return base
        val ext = base.substringAfterLast('.', "").take(10)
        return if (ext.isEmpty()) base.take(120) else base.take(119 - ext.length) + "." + ext
    }

    /** [name], or "name (2).ext", "name (3).ext"… when [taken]. */
    fun unique(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        return (2..999).asSequence().map { "$stem ($it)$ext" }.firstOrNull { !taken(it) } ?: "$stem (${System.nanoTime()})$ext"
    }

    /** The bytes and MIME type of a `data:` URL, or null when it isn't one. */
    fun decodeDataUrl(url: String): Pair<ByteArray, String>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',').takeIf { it >= 0 } ?: return null
        val meta = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val base64 = meta.endsWith(";base64")
        val mime = meta.removeSuffix(";base64").substringBefore(';').ifBlank { "text/plain" }
        val bytes = if (base64) {
            runCatching { Base64.getDecoder().decode(payload.replace(Regex("\\s"), "")) }.getOrNull() ?: return null
        } else {
            URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray()
        }
        return bytes to mime
    }

    /** A file extension for [mime] when a downloaded name has none. */
    fun extensionFor(mime: String): String = when (mime.substringBefore(';').trim().lowercase()) {
        "application/pdf" -> ".pdf"
        "text/csv" -> ".csv"
        "text/plain" -> ".txt"
        "application/json" -> ".json"
        "application/zip" -> ".zip"
        "image/png" -> ".png"
        "image/jpeg" -> ".jpg"
        "text/html" -> ".html"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
        else -> ""
    }

    const val MAX_REDIRECTS = 10

    /**
     * Opens [url] for a download the page started, following its redirects by hand, and returns the
     * connection once it answers 2xx (the caller reads it and disconnects). Each hop gets only the cookies
     * a browser would send to that hop's URL ([cookiesFor], the agent profile's CookieManager), never the
     * first URL's: HttpURLConnection keeps a hand-set Cookie header across its own redirects, so a link that
     * redirects to another host would hand it the site's session (#369). The Referer is only [referer]'s
     * origin, never the page's full URL, and not sent to plain http from https. A redirect from https to
     * http is refused.
     */
    fun openDownload(
        url: String,
        userAgent: String?,
        referer: String?,
        cookiesFor: (String) -> String?,
        maxRedirects: Int = MAX_REDIRECTS,
    ): HttpURLConnection {
        val refererOrigin = referer?.let(::originOf)
        var current = webUrl(url) ?: throw IOException("it isn't an http(s) link")
        repeat(maxRedirects + 1) {
            val connection = current.openConnection() as HttpURLConnection
            var keep = false
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                userAgent?.let { connection.setRequestProperty("User-Agent", it) }
                cookiesFor(current.toString())?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
                refererOrigin?.takeUnless { it.startsWith("https:") && current.protocol == "http" }
                    ?.let { connection.setRequestProperty("Referer", it) }
                val code = connection.responseCode
                if (code in 200..299) {
                    keep = true
                    return connection
                }
                val location = connection.getHeaderField("Location")
                if (code !in REDIRECTS || location.isNullOrBlank()) throw IOException("the server answered HTTP $code")
                current = nextHop(current, location)
            } finally {
                if (!keep) connection.disconnect()
            }
        }
        throw IOException("it redirected more than $maxRedirects times")
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /** Where a redirect from [current] to [location] goes: an http(s) URL, and never down from https to http. */
    internal fun nextHop(current: URL, location: String): URL {
        val next = runCatching { URL(current, location) }.getOrNull()?.toString()?.let(::webUrl)
            ?: throw IOException("it redirected to something that isn't an http(s) link")
        if (current.protocol == "https" && next.protocol == "http") throw IOException("it redirected from https to plain http")
        return next
    }

    private fun webUrl(url: String): URL? =
        runCatching { URL(url) }.getOrNull()?.takeIf { (it.protocol == "http" || it.protocol == "https") && !it.host.isNullOrEmpty() }

    /** "https://host[:port]/" of an http(s) URL, or null. */
    internal fun originOf(url: String): String? {
        val u = webUrl(url) ?: return null
        val port = if (u.port == -1 || u.port == u.defaultPort) "" else ":${u.port}"
        return "${u.protocol}://${u.host}$port/"
    }
}
