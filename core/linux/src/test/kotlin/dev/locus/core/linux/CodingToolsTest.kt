package dev.locus.core.linux

import dev.locus.core.agent.ToolException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodingToolsTest {
    @Test
    fun `git commands quote paths and switch off repo-run programs`() {
        val diff = gitCommand("diff", "src/My File.kt", "main", staged = true, maxCount = null)
        assertTrue("--staged 'main' -- 'src/My File.kt'" in diff, diff)
        assertTrue("core.fsmonitor=false" in diff && "--no-ext-diff" in diff && "--no-textconv" in diff && "safe.directory" in diff)
        assertTrue("apk add git" in diff)
        val quote = gitCommand("log", "it's.txt", null, staged = false, maxCount = 500)
        assertTrue("-n 200" in quote && "'it'\\''s.txt'" in quote, quote)
        assertTrue("'HEAD'" in gitCommand("show", null, null, staged = false, maxCount = null))
    }

    @Test
    fun `git refuses option injection and unknown actions`() {
        assertFailsWith<ToolException> { gitCommand("diff", null, "--output=/workspace/x", staged = false, maxCount = null) }
        assertFailsWith<ToolException> { gitCommand("log", null, "main; rm -rf /", staged = false, maxCount = null) }
        assertFailsWith<ToolException> { gitCommand("log", "-p", null, staged = false, maxCount = null) }
        assertFailsWith<ToolException> { gitCommand("push", null, null, staged = false, maxCount = null) }
        assertFailsWith<ToolException> { gitCommand("blame", null, null, staged = false, maxCount = null) }
        gitCommand("log", null, "main..HEAD~2", staged = false, maxCount = null)
    }

    @Test
    fun `service log returns the last lines, filtered`() {
        val log = (1..10).joinToString("\n") { if (it % 3 == 0) "ERROR $it" else "ok $it" } + "\n"
        assertEquals("[7 earlier lines not shown]\nok 8\nERROR 9\nok 10", lastLines(log, 3, null))
        assertEquals("[1 earlier lines not shown]\nERROR 6\nERROR 9", lastLines(log, 2, Regex("ERROR")))
        assertEquals("(no lines match the filter)", lastLines(log, 2, Regex("panic")))
        assertEquals("(no output yet)", lastLines("", 5, null))
    }
}
