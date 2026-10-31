package dev.locus.core.data.search

import org.junit.Test
import kotlin.test.assertEquals

class SearchSnippetTest {
    private fun parse(s: String) = SearchSnippet.parse(s.replace("[[", "").replace("]]", ""))

    @Test
    fun `matches become ranges of the text`() {
        val h = parse("…we wrote the [[Kotlin]] script and [[kotlin]]x…")
        assertEquals("…we wrote the Kotlin script and kotlinx…", h.text)
        assertEquals(listOf("Kotlin", "kotlin"), h.matches.map { h.text.substring(it) })
        assertEquals("…we wrote the <Kotlin> script and <kotlin>x…", h.marked("<", ">"))
    }

    @Test
    fun `markdown and line breaks are flattened`() {
        val h = parse("## Plan\n\n- use **[[Room]]** FTS\n> see [the docs](https://x.dev/a)\n`code`")
        assertEquals("Plan - use Room FTS see the docs code", h.text)
        assertEquals("Room", h.text.substring(h.matches.single()))
    }

    @Test
    fun `unpaired markers are ignored`() {
        assertEquals(Highlighted("a b"), parse("a]] b"))
        val open = parse("a [[bc")
        assertEquals("a bc", open.text)
        assertEquals("bc", open.text.substring(open.matches.single()))
        assertEquals(Highlighted("x"), parse("x[[]]"))
        assertEquals(Highlighted(""), parse("  [[  ]] "))
    }
}
