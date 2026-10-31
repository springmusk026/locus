package dev.locus.feature.workspace.preview

import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewParsingTest {
    @Test
    fun `kinds come from the extension`() {
        assertEquals(PreviewKind.Pdf, previewKindFor("docs/Report.PDF"))
        assertEquals(PreviewKind.Image, previewKindFor("a.heic"))
        assertEquals(PreviewKind.Markdown, previewKindFor("README.md"))
        assertEquals(PreviewKind.Web, previewKindFor("index.html"))
        assertEquals(PreviewKind.Web, previewKindFor("logo.svg"))
        assertEquals(PreviewKind.Table, previewKindFor("data.tsv"))
        assertEquals(PreviewKind.Json, previewKindFor("package.json"))
        assertEquals(PreviewKind.Code, previewKindFor("main.py"))
        assertEquals(PreviewKind.Code, previewKindFor("app.ts"))
        assertEquals(PreviewKind.Video, previewKindFor("clip.mp4"))
        assertEquals(PreviewKind.Audio, previewKindFor("song.mp3"))
        assertEquals(PreviewKind.Binary, previewKindFor("bundle.zip"))
        assertEquals(PreviewKind.Binary, previewKindFor("app.apk"))
        assertEquals(PreviewKind.Text, previewKindFor("notes"))
        assertEquals(PreviewKind.Text, previewKindFor("server.log"))
    }

    @Test
    fun `syntax by name and extension`() {
        assertEquals(SyntaxLanguage.PYTHON, syntaxFor("x/y/script.py"))
        assertEquals(SyntaxLanguage.SHELL, syntaxFor("Dockerfile"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, syntaxFor("a.json"))
        assertEquals(SyntaxLanguage.DEFAULT, syntaxFor("config.yaml"))
        assertNull(syntaxFor("notes.txt"))
        assertEquals("Python", languageLabel("main.py"))
        assertEquals("JSON", languageLabel("a.json"))
    }

    @Test
    fun `csv handles quotes, doubled quotes and newlines in cells`() {
        val t = parseTable("name,note\n\"Smith, J\",\"said \"\"hi\"\"\nthen left\"\r\nB,2\n\n", ',')
        assertEquals(listOf("name", "note"), t.rows[0])
        assertEquals(listOf("Smith, J", "said \"hi\"\nthen left"), t.rows[1])
        assertEquals(listOf("B", "2"), t.rows[2])
        assertEquals(3, t.totalRows)
        assertEquals(2, t.totalCols)
    }

    @Test
    fun `tsv ignores quotes`() {
        val t = parseTable("a\tb\n\"x\ty", '\t')
        assertEquals(listOf("\"x", "y"), t.rows[1])
    }
}
