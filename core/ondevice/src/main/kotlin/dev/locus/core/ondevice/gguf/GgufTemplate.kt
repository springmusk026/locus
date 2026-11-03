package dev.locus.core.ondevice.gguf

/**
 * What a GGUF's chat template (`tokenizer.chat_template`, Jinja) can do, read from the template text
 * at import so capability negotiation knows before the model is ever loaded.
 *
 * llama.cpp renders the template itself (common/chat.cpp, the same code llama-server runs) and builds
 * the tool-call grammar and parser from it. A template takes tools when its code reads a tools
 * variable (`tools`, or a prefixed one such as `xml_tools`) to declare them to the model. One that
 * never does (Gemma 2, Phi-3.5, SmolLM2, the DeepSeek R1 distills) would silently drop the tool list,
 * so such a model is "chat only". Checked against the 75 templates in llama.cpp's models/templates
 * (GgufTemplateTest). Once loaded, llama.cpp's own detection (it renders the template with and
 * without a tool call) can still downgrade a model to chat only: see [GgufModels.confirmTools].
 */
object GgufTemplate {
    // Literal braces stay escaped: Android compiles regexes with ICU, which rejects a bare `}` (IcuRegexGuardTest).
    private val jinjaBlock = Regex("""\{[{%]-?(.*?)-?[%}]\}""", RegexOption.DOT_MATCHES_ALL)
    private val toolsVariable = Regex("""(?<![\w.'"])(?:[a-z]+_)?tools(?![\w'"])""")

    /** Whether the template's code declares tools. A blank template takes no tools. */
    fun supportsTools(template: String?): Boolean {
        if (template.isNullOrBlank()) return false
        val code = jinjaBlock.findAll(template).joinToString("\n") { it.groupValues[1] }
        return toolsVariable.containsMatchIn(code)
    }
}
