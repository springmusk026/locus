package dev.locus.core.designsystem

import org.junit.Test
import kotlin.test.assertEquals

/** [codeSpans]: backticked runs in Locus's own strings become code-font spans. */
class CodeSpansTest {

    private fun spans(text: String) = codeSpans(text).let { s -> s.text to s.spanStyles.map { s.text.substring(it.start, it.end) } }

    @Test
    fun `backticked runs are set in the code font and lose their backticks`() {
        assertEquals("Run npm test …" to listOf("npm test …"), spans("Run `npm test …`"))
        assertEquals("like python3 or npx alone" to listOf("python3", "npx"), spans("like `python3` or `npx` alone"))
        assertEquals(InlineCode, codeSpans("`ls`").spanStyles.single().item)
    }

    @Test
    fun `text without a pair stays as it is`() {
        assertEquals("plain" to emptyList<String>(), spans("plain"))
        assertEquals("one ` alone" to emptyList<String>(), spans("one ` alone"))
        assertEquals("a and b `c" to listOf("a"), spans("`a` and b `c"))
    }
}
