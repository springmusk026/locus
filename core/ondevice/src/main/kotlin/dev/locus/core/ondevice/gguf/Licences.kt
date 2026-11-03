package dev.locus.core.ondevice.gguf

/**
 * A model card's licence slug ("apache-2.0", "llama3.2") as people write it ("Apache 2.0", "Llama 3.2
 * Community License"). Hugging Face's licence identifiers: huggingface.co/docs/hub/repositories-licenses.
 * An unknown slug is shown as it is.
 */
object Licences {
    private val names = mapOf(
        "apache-2.0" to "Apache 2.0",
        "mit" to "MIT",
        "bsd-3-clause" to "BSD 3-Clause",
        "bsd-2-clause" to "BSD 2-Clause",
        "gpl-3.0" to "GPL 3.0",
        "lgpl-3.0" to "LGPL 3.0",
        "agpl-3.0" to "AGPL 3.0",
        "mpl-2.0" to "MPL 2.0",
        "cc-by-4.0" to "CC BY 4.0",
        "cc-by-sa-4.0" to "CC BY-SA 4.0",
        "cc-by-nc-4.0" to "CC BY-NC 4.0",
        "cc-by-nc-sa-4.0" to "CC BY-NC-SA 4.0",
        "openrail" to "OpenRAIL",
        "openrail++" to "OpenRAIL++",
        "llama2" to "Llama 2 licence",
        "llama3" to "Llama 3 licence",
        "llama3.1" to "Llama 3.1 licence",
        "llama3.2" to "Llama 3.2 licence",
        "llama3.3" to "Llama 3.3 licence",
        "llama4" to "Llama 4 licence",
        "gemma" to "Gemma terms",
        "deepseek" to "DeepSeek licence",
        "other" to "Custom licence",
        "unknown" to "Not stated",
    )

    fun name(slug: String?): String? = slug?.trim()?.takeIf { it.isNotEmpty() }?.let { names[it.lowercase()] ?: it }
}
