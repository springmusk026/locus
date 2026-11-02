package dev.locus.core.markdown

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MathSyntaxTest {

    private fun formulas(text: String) = prepareMath(text).formulas

    // Currency and other single dollars that are not maths.

    @Test
    fun `prices stay text`() {
        for (text in listOf(
            "It costs $5 and $10.",
            "Between $5-$10 a month.",
            "Pay bills \$100 and \$50",
            "From $1,000 to $2,000.",
            "US$5 and US$10",
            "Ca\$h and Ke\$ha",
            "It's $5, or $6 with tax.",
            "Just $5.",
            "set \$HOME and \$PATH",
            "costs $5 (or $10 with tax)",
        )) {
            val prepared = prepareMath(text)
            assertEquals(emptyList(), prepared.formulas, text)
            assertEquals(text, prepared.markdown, text)
        }
    }

    @Test
    fun `a price before a formula leaves the formula intact`() {
        val found = formulas("It costs $5.99 and the formula is \$x^2\$.")
        assertEquals(listOf("x^2"), found.map { it.latex })
    }

    @Test
    fun `escaped dollars are literal`() {
        assertEquals(emptyList(), formulas("Costs \\\$5 and \\\$x\\\$ too"))
        assertEquals(listOf("a \\\$ b"), formulas("\$a \\\$ b\$").map { it.latex })
    }

    // Inline maths.

    @Test
    fun `dollar inline maths`() {
        val prepared = prepareMath("Euler: \$e^{i\\pi} + 1 = 0\$, done.")
        assertEquals(listOf("e^{i\\pi} + 1 = 0"), prepared.formulas.map { it.latex })
        assertEquals("Euler: ${placeholderFor(0)}, done.", prepared.markdown)
        assertEquals("\$e^{i\\pi} + 1 = 0\$", prepared.formulas[0].source)
        assertEquals(false, prepared.formulas[0].display)
    }

    @Test
    fun `pandoc spacing rules for single dollars`() {
        assertEquals(emptyList(), formulas("a \$ x\$ b"))
        assertEquals(emptyList(), formulas("a \$x \$ b"))
        assertEquals(emptyList(), formulas("\$x\$5"))
        assertEquals(listOf("x"), formulas("\$x\$.").map { it.latex })
    }

    @Test
    fun `parenthesis delimiters`() {
        val prepared = prepareMath("""where \(a_1 * b_2\) holds""")
        assertEquals(listOf("a_1 * b_2"), prepared.formulas.map { it.latex })
        assertEquals("where ${placeholderFor(0)} holds", prepared.markdown)
    }

    @Test
    fun `maths keeps markdown characters away from the parser`() {
        val prepared = prepareMath("\$a_1 * b_2 * c_3\$ and *real emphasis*")
        assertEquals("${placeholderFor(0)} and *real emphasis*", prepared.markdown)
    }

    @Test
    fun `inline maths does not cross a blank line`() {
        assertEquals(emptyList(), formulas("\$a\n\nb\$"))
        assertEquals(listOf("a\nb"), formulas("\$a\nb\$").map { it.latex })
    }

    @Test
    fun `several formulas get their own placeholders`() {
        val prepared = prepareMath("\$a\$, \$b\$ and \\(c\\)")
        assertEquals(listOf("a", "b", "c"), prepared.formulas.map { it.latex })
        assertEquals("${placeholderFor(0)}, ${placeholderFor(1)} and ${placeholderFor(2)}", prepared.markdown)
        assertEquals("c", prepared.formulaAt(placeholderFor(2))?.latex)
        assertEquals(null, prepared.formulaAt('x'))
    }

    // Display maths.

    @Test
    fun `double dollars on their own lines become a math fence`() {
        val prepared = prepareMath("Before\n\n$$\n\\int_0^1 x\\,dx\n$$\n\nAfter")
        assertEquals("Before\n\n```math\n\\int_0^1 x\\,dx\n```\n\nAfter", prepared.markdown)
        assertEquals(emptyList(), prepared.formulas)
    }

    @Test
    fun `single line display maths becomes a fence`() {
        assertEquals("```math\nE = mc^2\n```\n", prepareMath("\$\$E = mc^2\$\$").markdown)
        assertEquals("Text\n```math\na+b\n```\nmore", prepareMath("Text\n\\[ a+b \\]\nmore").markdown)
    }

    @Test
    fun `display maths inside a sentence stays inline`() {
        val prepared = prepareMath("so \$\$x^2\$\$ is positive")
        assertEquals("so ${placeholderFor(0)} is positive", prepared.markdown)
        assertEquals(true, prepared.formulas.single().display)
    }

    @Test
    fun `display maths in a list item keeps the item's indentation`() {
        val prepared = prepareMath("1. First\n2. $$\n   a = b\n   $$\n3. Third")
        assertEquals("1. First\n2. ```math\n   a = b\n   ```\n3. Third", prepared.markdown)
    }

    @Test
    fun `display maths in a blockquote keeps the quote markers`() {
        val prepared = prepareMath("> $$\n> a = b\n> $$\n")
        assertEquals("> ```math\n> a = b\n> ```\n", prepared.markdown)
    }

    @Test
    fun `an unclosed display delimiter is left alone`() {
        val text = "$$ a = b\n\nno end"
        assertEquals(text, prepareMath(text).markdown)
    }

    // Code is never touched.

    @Test
    fun `code spans and fences are skipped`() {
        val text = "Run `echo \$HOME \$x\$` then\n\n```sh\nprice=\$5; echo \$x\$\n$$\n```\n\nand ``a \$b\$ c``"
        val prepared = prepareMath(text)
        assertEquals(text, prepared.markdown)
        assertEquals(emptyList(), prepared.formulas)
    }

    @Test
    fun `maths after a code fence is still found`() {
        val prepared = prepareMath("```\ncode \$x\$\n```\nThen \$y\$.")
        assertEquals(listOf("y"), prepared.formulas.map { it.latex })
    }

    @Test
    fun `an unclosed fence runs to the end`() {
        val text = "```\n\$x\$"
        assertEquals(text, prepareMath(text).markdown)
    }

    @Test
    fun `text with private use characters is left alone`() {
        val text = "\uE000 and \$x\$"
        assertEquals(text, prepareMath(text).markdown)
    }

    @Test
    fun `plain text is returned as is`() {
        val text = "No maths here, just words."
        assertTrue(prepareMath(text).markdown === text)
    }

    @Test
    fun `fence regions cover whole lines`() {
        val text = "a\n```\nb\n```\nc"
        assertEquals(listOf(2 until 12), codeFenceRegions(text))
    }
}
