package dev.locus.core.data

import dev.locus.core.linux.EngineText
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Renders [EngineText] in English on the plain JVM, from core/data's and core/linux's `values/strings.xml`,
 * so this module's tests can check the words without Robolectric. It reads strings the way Android does
 * for the escapes these files use (`\'`, `\"`, `\n`, a value in double quotes) and picks `one` for 1.
 */
internal object EnglishResources {
    private val files = listOf(File("src/main/res/values/strings.xml"), File("../linux/src/main/res/values/strings.xml"))
    private val rClasses = listOf(R.string::class.java, R.plurals::class.java, dev.locus.core.linux.R.string::class.java, dev.locus.core.linux.R.plurals::class.java)

    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        files.forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                when (e.tagName) {
                    "string" -> strings[e.getAttribute("name")] = unescape(e.textContent)
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        plurals[e.getAttribute("name")] = (0 until items.length).associate { j ->
                            (items.item(j) as Element).let { it.getAttribute("quantity") to unescape(it.textContent) }
                        }
                    }
                }
            }
        }
    }

    private fun name(id: Int): String = rClasses.firstNotNullOfOrNull { c -> c.fields.firstOrNull { it.getInt(null) == id }?.name }
        ?: error("no resource with id $id")

    private fun unescape(raw: String): String {
        val quoted = raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")
        val text = if (quoted) raw.substring(1, raw.length - 1) else raw.trim().replace(Regex("\\s+"), " ")
        return buildString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    append(if (text[i + 1] == 'n') '\n' else text[i + 1])
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }

    fun render(text: EngineText): String = when (text) {
        is EngineText.Plain -> text.value
        is EngineText.Res -> strings.getValue(name(text.id)).let { if (text.args.isEmpty()) it else String.format(Locale.US, it, *args(text.args)) }
        is EngineText.Plural -> plurals.getValue(name(text.id)).let { forms ->
            val form = (if (text.count == 1) forms["one"] else null) ?: forms.getValue("other")
            String.format(Locale.US, form, *(if (text.args.isEmpty()) arrayOf<Any>(text.count) else args(text.args)))
        }
    }

    private fun args(args: List<Any>): Array<Any> = args.map { if (it is EngineText) render(it) else it }.toTypedArray()
}
