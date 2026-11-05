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

    fun display(raw: String): String {
        var stem = raw.trim().substringAfterLast('/').removeSuffix(".gguf").removeSuffix(".GGUF")
        GgufQuant.fromFileName(stem)?.let { quant ->
            stem = stem.replace(Regex("""[-_.]${Regex.escape(quant)}$""", RegexOption.IGNORE_CASE), "")
        }
        if (' ' in stem.trim()) return stem.trim()
        val spaced = stem.replace(Regex("[-_]+"), " ").replace(Regex("""\s+"""), " ").trim()
            // A size reads the same in every name: "1b" and "e2b" as "1B" and "E2B".
            .split(' ').joinToString(" ") { w -> if (size.matches(w)) w.uppercase() else w }
        return spaced.replaceFirstChar { it.uppercaseChar() }.ifEmpty { raw }
    }

    private val size = Regex("""(?i)(e|a)?\d+(\.\d+)?[bm]""")
}
