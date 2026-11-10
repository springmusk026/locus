package dev.locus.core.designsystem

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.model.ToolActivity
import dev.locus.core.model.ToolLabelText
import dev.locus.core.model.ToolPhrase
import dev.locus.core.model.toolActivity
import dev.locus.core.model.toolLabel
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The tool steps' strings say in English exactly what core/model says to the model, phrase by phrase,
 * and every phrase has its words in each language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolLabelsResourcesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun resources(locale: Locale): Resources {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config).resources
    }

    private val english = resources(Locale.US)
    private val placeholder = Regex("""%(\d+)\$([sd])""")

    /** Values for [template]'s placeholders: words for `%s`, [count] for `%d`. */
    private fun valuesFor(template: String, count: Int): List<Any> =
        placeholder.findAll(template).map { it.groupValues[1].toInt() to it.groupValues[2] }.distinct().sortedBy { it.first }
            .map { (n, type) -> if (type == "d") count else "value$n" }.toList()

    private fun phrases(count: Int) = ToolPhrase.entries.flatMap { p ->
        listOf(true, false).map { done -> ToolLabelText.Phrase(p, done, valuesFor(if (done) p.past else p.present, count), count.takeIf { p.plural }) }
    }

    @Test
    fun everyPhraseSaysTheCoresEnglish() {
        for (count in listOf(1, 2, 5)) {
            phrases(count).forEach { assertEquals(it.english, it.asString(english), "${it.phrase} done=${it.done} count=$count") }
        }
    }

    @Test
    fun everyActivitySaysTheCoresEnglish() {
        ToolActivity.entries.forEach { assertEquals(it.english, english.getString(it.res()), it.name) }
    }

    @Test
    fun realCallsReadTheSameInEnglish() {
        val calls = listOf(
            Triple("run_command", """{"command":"ls -la\nrm x"}""", false),
            Triple("multi_edit", """{"path":"a.kt","edits":[{}]}""", true),
            Triple("view_image", """{"paths":["a.png","b.png"]}""", true),
            Triple("browser_wait", """{"seconds":5}""", true),
            Triple("mcp__notion__search", "{}", true),
            Triple("call_tool", """{"name":"weather","arguments":{"place":"Oslo"}}""", true),
            Triple("some_new_tool", "{}", true),
        )
        calls.forEach { (name, args, done) -> assertEquals(toolLabel(name, args, done), localizedToolLabel(english, name, args, done)) }
        listOf("web_search", "browser_click", "agent_spawn", "unknown").forEach { assertEquals(toolActivity(it), localizedToolActivity(english, it)) }
    }

    @Test
    fun translatedInEveryLanguage() {
        for (locale in listOf(Locale.forLanguageTag("es"), Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("zh-Hans-CN"))) {
            val r = resources(locale)
            assertNotEquals(toolLabel("web_search", """{"query":"kotlin"}"""), localizedToolLabel(r, "web_search", """{"query":"kotlin"}"""), locale.toString())
            assertNotEquals(toolActivity("web_search"), localizedToolActivity(r, "web_search"), locale.toString())
            // Every phrase keeps its values.
            phrases(3).forEach { p -> p.args.forEach { v -> assert(v.toString() in p.asString(r)) { "${p.phrase} in $locale lost $v" } } }
        }
    }
}
