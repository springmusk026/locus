package dev.locus.core.ondevice.gguf

/**
 * The name a GGUF model is shown with. A file from the recommended list takes the list's name
 * ("Qwen3.5 0.8B"), matched by its SHA-256; anything else gets its header's `general.name` (or file
 * name) tidied: separators become spaces and a trailing quantisation is dropped, since the quant is
 * shown on its own ("Qwen3.5-0.8B-Q4_K_M" becomes "Qwen3.5 0.8B").
 */
object GgufNames {
    fun named(m: GgufModel): GgufModel {
        val listed = m.sha256?.let { sha -> GgufCatalog.models.firstOrNull { it.sha256.equals(sha, ignoreCase = true) }?.name }
        val name = listed ?: display(m.name)
        return if (name == m.name) m else m.copy(name = name)
    }

    /** The tidied name ([dev.locus.core.model.ModelNames.gguf]): the one formatter every screen uses. */
    fun display(raw: String): String = dev.locus.core.model.ModelNames.gguf(raw)
}
