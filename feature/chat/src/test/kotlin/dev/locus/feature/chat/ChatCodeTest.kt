package dev.locus.feature.chat

import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatCodeTest {
    @Test fun aliasesMapToHighlighterLanguages() {
        val cases = mapOf(
            SyntaxLanguage.JAVASCRIPT to listOf("js", "jsx", "mjs", "javascript", "JavaScript"),
            SyntaxLanguage.TYPESCRIPT to listOf("ts", "tsx", "typescript"),
            SyntaxLanguage.PYTHON to listOf("py", "python", "Python3"),
            SyntaxLanguage.SHELL to listOf("sh", "bash", "zsh", "shell", "console"),
            SyntaxLanguage.KOTLIN to listOf("kt", "kts", "kotlin"),
            SyntaxLanguage.CPP to listOf("c++", "cc", "hpp", "cpp"),
            SyntaxLanguage.CSHARP to listOf("cs", "csharp"),
            SyntaxLanguage.RUBY to listOf("rb", "ruby"),
            SyntaxLanguage.GO to listOf("golang", "go"),
            SyntaxLanguage.RUST to listOf("rs", "rust"),
            SyntaxLanguage.JAVA to listOf("java"),
            SyntaxLanguage.SWIFT to listOf("swift"),
        )
        cases.forEach { (lang, aliases) -> aliases.forEach { assertEquals(lang, syntaxLanguageFor(it), it) } }
    }

    @Test fun fenceInfoAfterTheLanguageIsIgnored() {
        assertEquals(SyntaxLanguage.KOTLIN, syntaxLanguageFor("  kotlin title=\"Main.kt\""))
        assertEquals(SyntaxLanguage.PYTHON, syntaxLanguageFor("py{linenos}"))
    }

    @Test fun unsupportedOrMissingLanguagesArePlain() {
        listOf(null, "", "  ", "json", "yaml", "html", "sql", "diff", "text", "default", "mermaid").forEach {
            assertNull(syntaxLanguageFor(it), it.toString())
        }
    }

    @Test fun displayNames() {
        assertEquals("Code", languageDisplayName(null))
        assertEquals("JavaScript", languageDisplayName("js"))
        assertEquals("C++", languageDisplayName("cc"))
        assertEquals("bash", languageDisplayName("bash"))
        assertEquals("JSON", languageDisplayName("json"))
        assertEquals("Mermaid", languageDisplayName("Mermaid"))
    }

    @Test fun highlightingKeepsTheTextAndAddsSpans() {
        val code = "fun main() { val s = \"hi\" } // done"
        val out = highlightCode(code, SyntaxLanguage.KOTLIN, SyntaxThemes.atom(darkMode = true))
        assertEquals(code, out.text)
        assertTrue(out.spanStyles.isNotEmpty())
        assertTrue(out.spanStyles.all { it.start in 0..code.length && it.end in 0..code.length })
    }
}
