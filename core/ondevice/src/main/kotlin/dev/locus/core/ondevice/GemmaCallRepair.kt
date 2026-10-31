package dev.locus.core.ondevice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Recovers tool calls LiteRT-LM failed to parse. Gemma writes calls as
 * `call:name{key:<|"|>text<|"|>,n:3,flag:true,obj:{…},list:[…]}` and small models sometimes add a
 * stray `<|"|>` (seen on device: `content:<|"|>hi<|"|><|"|>,path:…`). LiteRT then rejects the whole
 * turn; its error message carries the raw block, which this parser reads leniently.
 */
internal object GemmaCallRepair {
    private const val Q = "<|\"|>"
    private const val MARKER = "Failed to parse tool calls from code block:"

    data class Call(val name: String, val arguments: JsonObject)

    /** The calls in a LiteRT parse-error message, or empty if it isn't one or can't be repaired. */
    fun fromError(message: String?): List<Call> {
        val block = message?.substringAfter(MARKER, missingDelimiterValue = "")?.trim().orEmpty()
        if (block.isEmpty()) return emptyList()
        // LiteRT's message can repeat the block; the same call twice would run it twice.
        return parseAll(block).distinct()
    }

    fun parseAll(block: String): List<Call> {
        val calls = mutableListOf<Call>()
        var i = block.indexOf("call:")
        while (i >= 0) {
            val p = Parser(block, i + 5)
            val call = runCatching { p.call() }.getOrNull() ?: break
            calls += call
            i = block.indexOf("call:", p.pos)
        }
        return calls
    }

    private class Parser(val s: String, var pos: Int) {
        fun call(): Call {
            val nameStart = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_' || s[pos] == '-' || s[pos] == '.')) pos++
            val name = s.substring(nameStart, pos)
            require(name.isNotEmpty())
            skipSpace()
            val args = obj()
            return Call(name, args)
        }

        fun obj(): JsonObject {
            expect('{')
            val map = linkedMapOf<String, JsonElement>()
            while (true) {
                skipNoise()
                if (peek() == '}') { pos++; break }
                // Keys are bare identifiers; accept a quoted key too, just in case.
                val key = if (s.startsWith(Q, pos)) {
                    pos += Q.length
                    val end = s.indexOf(Q, pos).also { require(it >= 0) }
                    s.substring(pos, end).also { pos = end + Q.length }
                } else {
                    val start = pos
                    while (pos < s.length && s[pos] != ':') pos++
                    s.substring(start, pos).trim()
                }
                require(key.isNotEmpty())
                expect(':')
                map[key] = value()
                skipNoise()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; break }
                    else -> throw IllegalStateException("unexpected ${peek()} at $pos")
                }
            }
            return JsonObject(map)
        }

        fun value(): JsonElement {
            skipSpace()
            return when {
                s.startsWith(Q, pos) -> {
                    pos += Q.length
                    val end = s.indexOf(Q, pos).also { require(it >= 0) }
                    val text = s.substring(pos, end)
                    pos = end + Q.length
                    // Tolerate a stray extra quote token right after the string.
                    while (s.startsWith(Q, pos)) pos += Q.length
                    JsonPrimitive(text)
                }
                peek() == '{' -> obj()
                peek() == '[' -> array()
                else -> {
                    val start = pos
                    while (pos < s.length && s[pos] != ',' && s[pos] != '}' && s[pos] != ']') pos++
                    val raw = s.substring(start, pos).trim()
                    when {
                        raw == "true" || raw == "false" -> JsonPrimitive(raw.toBoolean())
                        raw == "null" -> JsonNull
                        raw.toLongOrNull() != null -> JsonPrimitive(raw.toLong())
                        raw.toDoubleOrNull() != null -> JsonPrimitive(raw.toDouble())
                        else -> JsonPrimitive(raw)
                    }
                }
            }
        }

        fun array(): JsonArray {
            expect('[')
            val items = mutableListOf<JsonElement>()
            while (true) {
                skipNoise()
                if (peek() == ']') { pos++; break }
                items += value()
                skipNoise()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; break }
                    else -> throw IllegalStateException("unexpected ${peek()} at $pos")
                }
            }
            return JsonArray(items)
        }

        private fun peek(): Char = s.getOrElse(pos) { '\u0000' }
        private fun expect(c: Char) { skipSpace(); require(peek() == c) { "expected $c at $pos" }; pos++ }
        private fun skipSpace() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        /** Whitespace and stray quote tokens between items. */
        private fun skipNoise() { while (true) { skipSpace(); if (s.startsWith(Q, pos) && (s.getOrNull(pos + Q.length) == ',' || s.getOrNull(pos + Q.length) == '}')) pos += Q.length else break } }
    }
}
