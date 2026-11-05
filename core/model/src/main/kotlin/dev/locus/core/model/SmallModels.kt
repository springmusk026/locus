package dev.locus.core.model

/**
 * Whether an on-device model is big enough to be given tools (docs/design/model-capabilities.md,
 * "Small models and tools").
 *
 * A chat template that declares tools only says a model *can* format a call. Below about a billion
 * parameters, published results say it rarely uses them well:
 *
 * - Qwen3.5-0.8B's own model card (Qwen/Qwen3.5-0.8B, "General Agent"): BFCL-V4 25.3 and TAU2-Bench
 *   11.6, against 43.6 and 48.8 for Qwen3.5-2B. The card names its uses as "prototyping, task-specific
 *   fine-tuning, and other research or development purposes" and says it "is more prone to entering
 *   thinking loops".
 * - At 1.2B to 1.7B, general instruct models score about the same as each other on BFCLv3 (LiquidAI's
 *   LFM2.5-1.2B-Instruct card: LFM2.5-1.2B 49.1, Qwen3-1.7B 46.3, Granite 4.0-1B 52.4), and LiquidAI
 *   recommends its 1.2B model "for agentic tasks".
 * - On a phone, a 0.8B model given Locus's tools made 26 refused calls in a row on a one-line question.
 *
 * So the line is drawn at 1B: under it, a model is chat only (no tools declared in its chats); at or
 * above it, tools as its template allows. A model whose size isn't known keeps its tools: unknown
 * never means no.
 */
object SmallModels {
    /** Fewer parameters than this (in billions): too small to be given tools. */
    const val MIN_TOOL_PARAMS_B = 1.0

    /** Shown where a model is chat only for its size. */
    const val TOO_SMALL = "Under 1B parameters: too small to use tools reliably"

    private val size = Regex("""(?:(\d+)\s*x\s*)?(\d+(?:\.\d+)?)\s*([KMBT])(?![A-Za-z])""", RegexOption.IGNORE_CASE)

    /**
     * Parameters in billions from a size label: GGUF `general.size_label` ("0.8B", "135M", "8x7B",
     * "30B-A3B": the total, first) or a model name's size ("Gemma 4 E2B"). Null when there's none.
     */
    fun billions(label: String?): Double? {
        val m = size.find(label ?: return null) ?: return null
        val experts = m.groupValues[1].toIntOrNull() ?: 1
        val n = m.groupValues[2].toDoubleOrNull() ?: return null
        val scale = when (m.groupValues[3].uppercase()) {
            "K" -> 1e-6
            "M" -> 1e-3
            "B" -> 1.0
            else -> 1e3
        }
        return experts * n * scale
    }

    /** Whether a model of [sizeLabel] is too small to be given tools; false when its size isn't known. */
    fun tooSmallForTools(sizeLabel: String?): Boolean = billions(sizeLabel)?.let { it < MIN_TOOL_PARAMS_B } == true

    /** Whether a model whose template does ([templateTools]) or doesn't declare tools, of [sizeLabel], gets tools. */
    fun callsTools(templateTools: Boolean, sizeLabel: String?): Boolean = templateTools && !tooSmallForTools(sizeLabel)
}
