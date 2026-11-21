package dev.locus.core.data.chatimport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Lenient reads: a field of an unexpected type reads as missing, never as an error. */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * A timestamp as exports write it, in epoch milliseconds: seconds as a number (ChatGPT, often with a
 * fraction), milliseconds as a number, or an ISO 8601 string (Claude, with microseconds and `Z` or an
 * offset). Null for anything else.
 */
internal fun JsonElement?.epochMillis(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    if (p.isString) return parseIso(p.content)
    val n = p.doubleOrNull ?: return null
    if (n.isNaN() || n <= 0) return null
    // 1e11 seconds is the year 5138; anything bigger is milliseconds.
    return if (n > 1e11) n.toLong() else (n * 1000).toLong()
}

private fun parseIso(s: String): Long? {
    val t = s.trim()
    if (t.isEmpty()) return null
    return try {
        OffsetDateTime.parse(t).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            Instant.parse(t).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // "2024-03-01 10:00:00" without a zone: read as UTC.
            runCatching { Instant.parse(t.replace(' ', 'T') + "Z").toEpochMilli() }.getOrNull()
        }
    }
}
