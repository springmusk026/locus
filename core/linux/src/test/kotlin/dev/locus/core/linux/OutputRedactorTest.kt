package dev.locus.core.linux

import org.junit.Test
import kotlin.test.assertEquals

class OutputRedactorTest {
    @Test
    fun `registered secret values are scrubbed until their owner unregisters`() {
        val r = OutputRedactor()
        r.register("github", listOf("ghp_abcdef123456", "abc"))
        val env = "GITHUB_TOKEN=ghp_abcdef123456\nSHORT=abc\n"
        assertEquals("GITHUB_TOKEN=[secret]\nSHORT=abc\n", r.scrub(env))
        r.unregister("github")
        assertEquals(env, r.scrub(env))
    }

    @Test
    fun `a longer value containing a shorter one is replaced whole`() {
        val r = OutputRedactor()
        r.register("a", listOf("secret1"))
        r.register("b", listOf("secret1-extended"))
        assertEquals("x=[secret] y=[secret]", r.scrub("x=secret1-extended y=secret1"))
    }
}
