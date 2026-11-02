package dev.locus.core.agent

import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parses the exact multipart/form-data bytes a request sends, so tests can pin the wire format
 * (RFC 7578) instead of searching the body for substrings.
 */
internal object MultipartWire {
    /** One part: its header lines in order (name as written, value) and its body. */
    class Part(val headers: List<Pair<String, String>>, val body: String) {
        fun all(name: String) = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
        val disposition get() = all("Content-Disposition").single()
        val contentType get() = all("Content-Type").singleOrNull()
    }

    fun bytes(body: OutgoingContent): ByteArray = runBlocking {
        when (val b = body) {
            is OutgoingContent.ByteArrayContent -> b.bytes()
            is OutgoingContent.WriteChannelContent -> coroutineScope {
                val channel = ByteChannel()
                launch { b.writeTo(channel); channel.flushAndClose() }
                channel.toByteArray()
            }
            else -> error("Not a multipart body: $b")
        }
    }

    fun parts(req: HttpRequestData): List<Part> = parts(req.body)

    fun parts(body: OutgoingContent): List<Part> {
        val type = body.contentType ?: error("No Content-Type")
        assertEquals("multipart/form-data", "${type.contentType}/${type.contentSubtype}")
        val boundary = type.parameter("boundary") ?: error("No boundary")
        // ISO-8859-1 maps every byte to one char, so nothing is lost or merged while splitting.
        val text = bytes(body).toString(Charsets.ISO_8859_1)
        val sections = text.split("--$boundary")
        assertTrue(sections.last().startsWith("--"), "body ends with the closing boundary")
        return sections.drop(1).dropLast(1).map { raw ->
            assertTrue(raw.startsWith("\r\n") && raw.endsWith("\r\n"), "part is framed by CRLF")
            val inner = raw.removePrefix("\r\n").removeSuffix("\r\n")
            val split = inner.indexOf("\r\n\r\n")
            assertTrue(split >= 0, "part has a blank line after its headers")
            val headers = inner.substring(0, split).split("\r\n").map { line ->
                val colon = line.indexOf(':')
                assertTrue(colon > 0, "header line '$line'")
                line.substring(0, colon) to line.substring(colon + 1).trim()
            }
            Part(headers, inner.substring(split + 4))
        }
    }

    /**
     * Every part has exactly one Content-Disposition, and it is a well-formed `form-data` disposition:
     * `form-data; name="…"` optionally followed by `; filename="…"`, nothing repeated and nothing else.
     */
    fun assertWellFormed(parts: List<Part>) {
        val good = Regex("^form-data; name=\"[^\"\\\\]+\"(; filename=\"[^\"\\\\]+\")?$")
        for (p in parts) {
            val dispositions = p.all("Content-Disposition")
            assertEquals(1, dispositions.size, "one Content-Disposition per part: ${p.headers}")
            assertTrue(good.matches(dispositions.single()), "well-formed Content-Disposition: ${dispositions.single()}")
            assertTrue(p.all("Content-Type").size <= 1, "at most one Content-Type: ${p.headers}")
        }
    }

    /** The `name` parameter of a part's Content-Disposition. */
    fun Part.name() = Regex("; name=\"([^\"]*)\"").find(disposition)!!.groupValues[1]
}
