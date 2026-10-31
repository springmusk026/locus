package dev.locus.core.browser

import java.net.URLDecoder
import java.util.Base64

/** File names and data URLs for downloads into, and uploads from, the workspace. Pure, so unit-tested. */
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
            URLDecoder.decode(payload.replace("+", "%2B"), Charsets.UTF_8).toByteArray()
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
}
