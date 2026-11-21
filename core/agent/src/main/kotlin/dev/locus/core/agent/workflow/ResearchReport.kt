package dev.locus.core.agent.workflow

/**
 * Checks the shape of a research report (docs/design/deep-research.md): a summary, inline citations
 * `[n]`, a numbered sources list where every source has a web address and every citation has a source,
 * and an "Unverified claims" section (which may say "None."). Pure, so the receipt's check and the tests
 * use the same rules. It checks structure only; whether the sources say what the report claims is the
 * verify step's job.
 */
object ResearchReport {

    enum class Problem(val english: String) {
        NO_SUMMARY("it has no Summary section"),
        NO_CITATIONS("it cites no sources inline, like [1]"),
        NO_SOURCES("it has no numbered Sources list"),
        SOURCE_WITHOUT_URL("a source in the list has no web address"),
        UNKNOWN_CITATION("a citation points to a source that isn't in the list"),
        NO_UNVERIFIED_SECTION("it has no Unverified claims section"),
    }

    /** What [check] found: [problems] (none means the report is well formed) and counts for the receipt. */
    data class Result(
        val problems: List<Problem>,
        /** Distinct source numbers cited in the text. */
        val citations: Int,
        /** Entries in the sources list. */
        val sources: Int,
        /** Items in the Unverified claims section ("None." counts as zero). */
        val unverified: Int,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /** A citation: `[3]`, or several in one bracket, `[1, 4]`; never a Markdown link `[1](…)`. */
    private val CITATION = Regex("""\[(\d{1,3}(?:\s*[,;]\s*\d{1,3})*)\](?!\()""")
    private val SOURCE_ENTRY = Regex("""^\s*(?:[-*]\s*)?(?:\[(\d{1,3})\]|(\d{1,3})[.)])\s+(.+)$""")
    private val URL = Regex("""https?://[^\s)>\]]+""", RegexOption.IGNORE_CASE)
    private val LIST_ITEM = Regex("""^\s*(?:[-*+]|\d{1,3}[.)])\s+(.+)$""")

    fun check(markdown: String): Result {
        val sections = sections(markdown)
        fun find(names: List<String>) = sections.firstOrNull { s -> s.title.isNotEmpty() && names.any { it in s.title } }
        val unverifiedSection = find(UNVERIFIED)
        val summary = find(SUMMARY)
        val sourcesSection = sections.firstOrNull { s -> s !== unverifiedSection && s.title.isNotEmpty() && SOURCES.any { it in s.title } }

        val sources = sourcesSection?.lines.orEmpty().mapNotNull { line ->
            SOURCE_ENTRY.find(line)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt() to m.groupValues[3] }
        }
        val sourceNumbers = sources.map { it.first }.toSet()
        val cited = sections.filter { it !== sourcesSection }.flatMap { it.lines }.flatMap { line ->
            CITATION.findAll(line).flatMap { m -> m.groupValues[1].split(',', ';').mapNotNull { it.trim().toIntOrNull() } }.toList()
        }.toSet()
        val unverified = unverifiedSection?.lines.orEmpty().mapNotNull { LIST_ITEM.find(it)?.groupValues?.get(1) }
            .count { !it.trim().trimEnd('.').equals("none", ignoreCase = true) }

        val problems = buildList {
            if (summary == null || summary.lines.none { it.isNotBlank() }) add(Problem.NO_SUMMARY)
            if (cited.isEmpty()) add(Problem.NO_CITATIONS)
            if (sources.isEmpty()) add(Problem.NO_SOURCES)
            if (sources.any { URL.find(it.second) == null }) add(Problem.SOURCE_WITHOUT_URL)
            if (sources.isNotEmpty() && cited.any { it !in sourceNumbers }) add(Problem.UNKNOWN_CITATION)
            if (unverifiedSection == null) add(Problem.NO_UNVERIFIED_SECTION)
        }
        return Result(problems, cited.size, sources.size, unverified)
    }

    /**
     * Heading words, matched within a `##` heading: English, which the plan asks for, and the words the
     * model uses when it writes the report in Spanish, Portuguese or Chinese (the plan lets it translate
     * the headings into the question's language).
     */
    private val SUMMARY = listOf("summary", "key findings", "resumen", "resumo", "摘要", "概要", "总结")
    private val SOURCES = listOf("sources", "references", "bibliography", "fuentes", "referencias", "fontes", "referências", "来源", "参考")
    private val UNVERIFIED = listOf("unverified", "not verified", "no verificad", "sin verificar", "não verificad", "sem verificação", "未核实", "未经核实", "未验证", "未经证实")

    private class Section(val title: String, val lines: MutableList<String> = mutableListOf())

    /** The report's `##` sections (titles lowercased, numbering dropped), with the text before the first as an untitled one. Code blocks are skipped. */
    private fun sections(markdown: String): List<Section> {
        val out = mutableListOf(Section(""))
        var fenced = false
        markdown.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                fenced = !fenced
                return@forEach
            }
            if (fenced) return@forEach
            if (line.startsWith("## ")) {
                val title = line.removePrefix("## ").trim().lowercase().replace(Regex("""^\d+[.)]\s*"""), "").trim()
                out += Section(title)
            } else {
                out.last().lines += line
            }
        }
        return out
    }
}
