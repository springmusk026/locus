package dev.locus.core.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RichMarkdownTest {

    private fun parse(markdown: String): ASTNode = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)

    private fun ASTNode.find(type: org.intellij.markdown.IElementType): List<ASTNode> =
        (if (this.type == type) listOf(this) else emptyList()) + children.flatMap { it.find(type) }

    /** What the paragraph renderer would build for [markdown]'s first paragraph with our annotator. */
    private fun render(prepared: PreparedMarkdown, typeset: Set<String>): AnnotatedString {
        val root = parse(prepared.markdown)
        val paragraph = root.find(MarkdownElementTypes.PARAGRAPH).first()
        val settings = DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(),
            codeSpanStyle = SpanStyle(),
            annotator = mathAnnotator(prepared, typeset),
            highlightSpanStyle = SpanStyle(),
            referenceLinkHandler = null,
            linkInteractionListener = null,
        )
        return buildAnnotatedString { buildMarkdownAnnotatedString(prepared.markdown, paragraph, settings) }
    }

    private val inlineContentTag = "androidx.compose.foundation.text.inlineContent"

    @Test
    fun `typeset formulas become inline content whose text is the source`() {
        val prepared = prepareMath("Area \$\\pi r^2\$ and **bold** text")
        val text = render(prepared, setOf(inlineTag(0)))
        assertEquals("Area \$\\pi r^2\$ and bold text", text.text)
        val annotations = text.getStringAnnotations(inlineContentTag, 0, text.length)
        assertEquals(listOf(inlineTag(0)), annotations.map { it.item })
    }

    @Test
    fun `a formula that can't be typeset falls back to its source`() {
        val prepared = prepareMath("Area \$\\pi r^2\$ here")
        val text = render(prepared, emptySet())
        assertEquals("Area \$\\pi r^2\$ here", text.text)
        assertTrue(text.getStringAnnotations(inlineContentTag, 0, text.length).isEmpty())
    }

    @Test
    fun `placeholders survive emphasis and links`() {
        val prepared = prepareMath("*see \$x\$* and [\$y\$](https://example.com)")
        val text = render(prepared, setOf(inlineTag(0), inlineTag(1)))
        assertEquals("see \$x\$ and \$y\$", text.text)
        assertEquals(2, text.getStringAnnotations(inlineContentTag, 0, text.length).size)
    }

    @Test
    fun `currency renders unchanged`() {
        val prepared = prepareMath("Pay bills \$100 and \$50")
        assertEquals("Pay bills \$100 and \$50", render(prepared, emptySet()).text)
    }

    @Test
    fun `display maths parses as a math fence`() {
        val prepared = prepareMath("Intro\n\n$$\na^2 + b^2 = c^2\n$$\n")
        val fence = parse(prepared.markdown).find(MarkdownElementTypes.CODE_FENCE).single()
        assertTrue(isFenceClosed(fence))
        val language = fence.children.first { it.type.toString().contains("FENCE_LANG") }
        assertEquals(FenceKind.Math, fenceKind(language.getText(prepared.markdown)))
    }

    private fun ASTNode.getText(s: String) = s.substring(startOffset, endOffset)

    @Test
    fun `an unfinished fence is open`() {
        val streaming = "Look:\n\n```mermaid\ngraph TD\n  A --> B\n"
        assertFalse(isFenceClosed(parse(streaming).find(MarkdownElementTypes.CODE_FENCE).single()))
        assertTrue(isFenceClosed(parse("$streaming```\n").find(MarkdownElementTypes.CODE_FENCE).single()))
    }

    @Test
    fun `fence kinds`() {
        assertEquals(FenceKind.Mermaid, fenceKind("mermaid"))
        assertEquals(FenceKind.Mermaid, fenceKind(" Mermaid title=x"))
        assertEquals(FenceKind.Math, fenceKind("math"))
        assertEquals(FenceKind.Code, fenceKind("latex"))
        assertEquals(FenceKind.Code, fenceKind(null))
        assertEquals(FenceKind.Code, fenceKind("kotlin"))
    }

    @Test
    fun `cache key depends on source, theme and version only`() {
        val a = diagramCacheKey("graph TD\nA-->B", dark = false)
        assertEquals(64, a.length)
        assertEquals(a, diagramCacheKey("graph TD\r\nA-->B\n\n", dark = false))
        assertNotEquals(a, diagramCacheKey("graph TD\nA-->C", dark = false))
        assertNotEquals(a, diagramCacheKey("graph TD\nA-->B", dark = true))
        assertNotEquals(a, diagramCacheKey("graph TD\nA-->B", dark = false, rendererVersion = "0"))
    }

    @Test
    fun `javascript string literals are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u003c/script\\u003e\\u2028\"", jsStringLiteral("a\"b\\c\nd</script>\u2028"))
        assertEquals("\"\\u0000\"", jsStringLiteral("\u0000"))
    }

    @Test
    fun `render results are decoded`() {
        assertNull(parseMermaidTake(null))
        assertNull(parseMermaidTake("\"\""))
        assertNull(parseMermaidTake("null"))
        val drawn = parseMermaidTake("\"{\\\"png\\\":\\\"iVBOR\\\",\\\"width\\\":120,\\\"height\\\":80}\"")
        assertEquals(MermaidOutcome.Drawn("iVBOR", 120, 80), drawn)
        assertEquals(MermaidOutcome.Failed("Parse error on line 2"), parseMermaidTake("\"{\\\"error\\\":\\\"Parse error on line 2\\\"}\""))
        assertIs<MermaidOutcome.Failed>(parseMermaidTake("\"{\\\"png\\\":\\\"\\\",\\\"width\\\":0}\""))
        assertIs<MermaidOutcome.Failed>(parseMermaidTake("\"not json\""))
    }
}
