package dev.locus.core.designsystem

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.model.ApprovalLineText
import dev.locus.core.model.ApprovalPhrase
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The approval lines' strings say in English exactly what the tools' `describe` says (the audit log's
 * words), phrase by phrase and count by count, and every phrase has its words in each language with the
 * call's values kept as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApprovalLinesResourcesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun resources(locale: Locale): Resources {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config).resources
    }

    private val english = resources(Locale.US)
    private val translations = listOf(Locale.forLanguageTag("es"), Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("zh-Hans-CN")).map(::resources)
    private val placeholder = Regex("""%(\d+)\$([sd])""")

    /** Each phrase with values for its placeholders: words for `%s`, [count] for `%d`. */
    private fun lines(count: Int) = ApprovalPhrase.entries.map { p ->
        val template = if (p.plural && count == 1) p.englishOne!! else p.english
        val values = placeholder.findAll(template).map { it.groupValues[1].toInt() to it.groupValues[2] }.distinct().sortedBy { it.first }
            .map { (n, type) -> if (type == "d") count else "value$n" }.toList()
        ApprovalLineText(p, values, count = count.takeIf { p.plural })
    }

    @Test
    fun everyPhraseSaysTheToolsEnglish() {
        for (count in listOf(1, 2, 5)) {
            lines(count).forEach { assertEquals(it.english, it.asString(english), "${it.phrase} count=$count") }
        }
    }

    @Test
    fun everyPhraseIsTranslatedAndKeepsItsValues() {
        for (r in translations) {
            lines(3).forEach { line ->
                val said = line.asString(r)
                line.args.forEach { v -> assertTrue(v.toString() in said, "${line.phrase} lost $v: $said") }
            }
            assertNotEquals(ApprovalLineText(ApprovalPhrase.DELETE_TO_TRASH, "a.txt").english, ApprovalLineText(ApprovalPhrase.DELETE_TO_TRASH, "a.txt").asString(r))
        }
    }

    @Test
    fun aLineInsideALineIsWordedInTheSameLanguage() {
        val line = ApprovalLineText(ApprovalPhrase.COMMIT, ApprovalLineText(ApprovalPhrase.COMMIT_ALL_CHANGES), "Fix it")
        assertEquals("Commit all changes: “Fix it”", line.asString(english))
        val spanish = resources(Locale.forLanguageTag("es"))
        assertEquals("Hacer commit de todos los cambios: “Fix it”", line.asString(spanish))
    }

    @Test
    fun theCardWordsTheLineAndKeepsWhyItAsks() {
        val line = ApprovalLineText(ApprovalPhrase.RUN_COMMAND, "rm -rf build")
        val summary = "Run: rm -rf build\nThis run read a web page, so Locus asks."
        assertEquals(summary, localizedApprovalSummary(english, summary, line))
        val spanish = resources(Locale.forLanguageTag("es"))
        assertEquals("Ejecutar: rm -rf build\nThis run read a web page, so Locus asks.", localizedApprovalSummary(spanish, summary, line))
        // Without a line, or when the summary doesn't start with the line's English, it is shown as it came.
        assertEquals(summary, localizedApprovalSummary(spanish, summary, null))
        assertEquals("Run: ls", localizedApprovalSummary(spanish, "Run: ls", line))
    }
}
