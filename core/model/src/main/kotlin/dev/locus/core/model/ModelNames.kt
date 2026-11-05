package dev.locus.core.model

/**
 * How a model id is named wherever a person reads it: the composer's model pill, task receipts, the
 * "What was sent" and failure details sheets, fallback notes and Settings rows. One formatter, so no
 * screen shows `gguf:qwen3.5-0.8b-q4_k_m` while another shows "Qwen3.5 0.8B".
 *
 * Diagnostic sheets keep the exact id in a row of its own ([idIfDifferent]): a bug report needs it.
 */
object ModelNames {
    /**
     * A model id short enough to read: no vendor path or `:tag`, no date stamp
     * (`anthropic/claude-sonnet-5-5-20260301` → `claude-sonnet-5-5`). An imported GGUF model's id
     * (`gguf:qwen3.5-0.8b-q4_k_m`) gets the tidied name the on-device screens use (`Qwen3.5 0.8B`), not
     * the `gguf` before its colon.
     */
    fun short(id: String): String {
        val trimmed = id.trim()
        if (trimmed.startsWith(GGUF_MODEL_PREFIX)) {
            return gguf(trimmed.removePrefix(GGUF_MODEL_PREFIX)).ifBlank { trimmed }
        }
        val base = trimmed.substringAfterLast('/').substringBefore(':')
        return base.replace(dateStamp, "").ifBlank { base }
    }

    /**
     * [id] itself when [short] reads differently, for a details row next to the short name; null when they
     * match. A hosted id ("anthropic/claude-sonnet-5-5-20260301") is what its provider's docs use. An
     * imported GGUF id is a local file slug nobody needs to read, and screens never show a `gguf:` key
     * (the render guard's raw-identifier rule), so it's only shown with [forReport]: a copied bug report.
     */
    fun idIfDifferent(id: String, forReport: Boolean = false): String? =
        id.trim().takeIf { it.isNotEmpty() && it != short(id) && (forReport || !isGgufModel(it)) }

    /**
     * A GGUF model's name from its header's `general.name` or its file name: separators become spaces
     * and a trailing quantisation is dropped, since the quant is shown on its own
     * ("Qwen3.5-0.8B-Q4_K_M" becomes "Qwen3.5 0.8B").
     */
    fun gguf(raw: String): String {
        var stem = raw.trim().substringAfterLast('/').removeSuffix(".gguf").removeSuffix(".GGUF")
        quantInFileName(stem)?.let { quant ->
            stem = stem.replace(Regex("""[-_.]${Regex.escape(quant)}$""", RegexOption.IGNORE_CASE), "")
        }
        if (' ' in stem.trim()) return stem.trim()
        val spaced = stem.replace(Regex("[-_]+"), " ").replace(Regex("""\s+"""), " ").trim()
            // A size reads the same in every name: "1b" and "e2b" as "1B" and "E2B".
            .split(' ').joinToString(" ") { w -> if (size.matches(w)) w.uppercase() else w }
        return spaced.replaceFirstChar { it.uppercaseChar() }.ifEmpty { raw }
    }

    /** The quantisation named in a file name ("qwen2.5-1.5b-instruct-q4_k_m.gguf" → "Q4_K_M"), or null. */
    fun quantInFileName(name: String): String? =
        quantInName.findAll(name.removeSuffix(".gguf")).lastOrNull()?.groupValues?.get(1)?.uppercase()

    private val dateStamp = Regex("""[-_@]?(20\d{2}-?\d{2}-?\d{2}|latest)$""")
    private val size = Regex("""(?i)(e|a)?\d+(\.\d+)?[bm]""")
    private val quantInName = Regex("""(?i)(?:^|[-_.])((?:IQ|Q|TQ)\d(?:_[0-9A-Z]{1,3}){0,2}|BF16|F16|F32|MXFP4(?:_MOE)?)(?=[-_.]|$)""")
}
