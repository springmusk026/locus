package dev.locus.core.ondevice.gguf

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tool support read from real chat templates. The fixtures in src/test/resources/gguf-templates are
 * copied from llama.cpp's models/templates at the pinned tag (the models' own tokenizer_config.json
 * templates), except qwen2.5-instruct and smollm2-instruct, read from the GGUF files of
 * Qwen/Qwen2.5-0.5B-Instruct-GGUF and bartowski/SmolLM2-135M-Instruct-GGUF.
 */
class GgufTemplateTest {
    private fun template(name: String): String =
        requireNotNull(javaClass.getResource("/gguf-templates/$name")) { "missing fixture $name" }.readText()

    @Test
    fun templatesThatDeclareToolsTakeTools() {
        listOf(
            "qwen2.5-instruct.jinja",
            "Qwen-Qwen3-0.6B.jinja",
            "meta-llama-Llama-3.2-3B-Instruct.jinja",
            "mistralai-Mistral-Nemo-Instruct-2407.jinja",
            "google-gemma-4-31B-it.jinja",
            "LFM2.5-Instruct.jinja",
        ).forEach { assertTrue(GgufTemplate.supportsTools(template(it)), it) }
    }

    @Test
    fun templatesWithoutToolsAreChatOnly() {
        listOf(
            "smollm2-instruct.jinja",
            "google-gemma-2-2b-it.jinja",
            "microsoft-Phi-3.5-mini-instruct.jinja",
            "deepseek-ai-DeepSeek-R1-Distill-Qwen-32B.jinja",
        ).forEach { assertFalse(GgufTemplate.supportsTools(template(it)), it) }
    }

    @Test
    fun noTemplateMeansNoTools() {
        assertFalse(GgufTemplate.supportsTools(null))
        assertFalse(GgufTemplate.supportsTools("  "))
    }

    @Test
    fun theWordToolsInPlainTextDoesNotCount() {
        // Only Jinja code counts: a system prompt that mentions tools in its text declares nothing.
        assertFalse(GgufTemplate.supportsTools("You have no tools.{% for m in messages %}{{ m['content'] }}{% endfor %}"))
        // A message field named tools isn't the tools variable either.
        assertFalse(GgufTemplate.supportsTools("{% for m in messages %}{{ m.tools }}{{ m['tools'] }}{% endfor %}"))
        assertTrue(GgufTemplate.supportsTools("{%- if tools %}{{ tools | tojson }}{% endif %}"))
    }
}
