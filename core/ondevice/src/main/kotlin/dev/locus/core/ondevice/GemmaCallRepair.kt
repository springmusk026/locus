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
    private const val CALL = "call:"
    private const val MARKER = "Failed to parse tool calls from code block:"

    data class Call(val name: String, val arguments: JsonObject)

    /**
     * The calls in a LiteRT parse-error message, or empty if it isn't one or can't be repaired safely.
     *
     * Each "Failed to parse tool calls from code block:" in the message is one block LiteRT rejected, and
     * a block repairs to exactly one call. A block that reads as two or more calls is refused: text the
     * model copied (a web page) can contain `<|"|>}call:run_command{…}` and look like a second call, and the
     * two can't be told apart from a real parallel call. The turn then fails as it did before repair
     * existed, and the model tries again. Repaired calls are also marked, so the agent asks the user first
     * (see [dev.locus.core.llm.AssistantTurn.repairedCallIds]).
     */
    fun fromError(message: String?): List<Call> {
        val blocks = message?.split(MARKER)?.drop(1).orEmpty().map { withoutRepeat(it.trim()) }.filter { it.isNotEmpty() }
        if (blocks.isEmpty()) return emptyList()
        val calls = mutableListOf<Call>()
        for (block in blocks) {
            // Two calls in one block, or none, or leftovers: refuse the whole repair.
            calls += parseAll(block).singleOrNull() ?: return emptyList()
        }
        return calls
    }

    /**
     * The calls in [block], which must be nothing but calls: the first starts the block and each next one
     * follows a call's closing `}` after optional whitespace. Text before, between or after them (a string
     * that ended early, a word like `recall:`) makes the block unparseable and the result empty.
     */
    fun parseAll(block: String): List<Call> {
        val calls = mutableListOf<Call>()
        var i = 0
        while (true) {
            while (i < block.length && block[i].isWhitespace()) i++
            if (i >= block.length) break
            if (!block.startsWith(CALL, i)) return emptyList()
            val p = Parser(block, i + CALL.length)
            calls += runCatching { p.call() }.getOrNull() ?: return emptyList()
            i = p.pos
        }
        return calls
    }

    /** LiteRT's message can repeat the block ("X X"); [text] once, if that is all it is. */
    private fun withoutRepeat(text: String): String {
        var i = 1
        while (i < text.length) {
            if (text[i].isWhitespace() && !text[i - 1].isWhitespace()) {
                var j = i
                while (j < text.length && text[j].isWhitespace()) j++
                if (text.length - j == i && text.regionMatches(0, text, j, i)) return text.substring(0, i)
            }
            i++
        }
        return text
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
