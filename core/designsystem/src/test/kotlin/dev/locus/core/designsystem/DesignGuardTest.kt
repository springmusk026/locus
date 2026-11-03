package dev.locus.core.designsystem

import java.io.File
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps feature code on the design system (docs/design/design-system.md, "Rules"). Scans every
 * module's `src/main` Kotlin except this one and counts, per file, each kind of thing a screen must not
 * invent for itself: raw dp, alpha and colour literals, dark-mode checks, corner shapes, Material
 * components that have a Locus counterpart, Material type slots that have a `LocusType` role, and
 * hand-rolled muted text.
 *
 * Every count is enforced against design-guard-baseline.txt (one line per rule and file). A file may
 * not go above its line, and a file that isn't listed must be clean. Counts going down are the point:
 * each migration PR lowers the baseline with
 * `LOCUS_DESIGN_GUARD_UPDATE=1 ./gradlew :core:designsystem:testDebugUnitTest --tests '*DesignGuardTest*'`,
 * never to let something new in.
 *
 * A line that genuinely needs a literal says why: `// design-exception: PDF paper is white`. The
 * marker excuses its own line, or the line after it when it stands alone. A marker with no reason
 * excuses nothing.
 */
class DesignGuardTest {

    private val root: File = findRoot()
    private val baselineFile = File(root, "core/designsystem/design-guard-baseline.txt")

    @Test
    fun featureCodeStaysOnTheDesignSystem() {
        val sources = DesignGuard.sources(root)
        assertTrue(sources.isNotEmpty(), "found no sources under $root")
        val counts = DesignGuard.count(root, sources)
        if (System.getenv("LOCUS_DESIGN_GUARD_UPDATE") == "1") {
            baselineFile.writeText(DesignGuard.formatBaseline(counts))
            println("Design guard: baseline rewritten with ${counts.values.sum()} entries.")
            return
        }
        val baseline = DesignGuard.parseBaseline(baselineFile.takeIf { it.exists() }?.readText().orEmpty())
        writeReport(counts, baseline)
        val problems = DesignGuard.regressions(counts, baseline)
        if (problems.isNotEmpty()) {
            fail(
                "Design guard: feature code outside core/designsystem went off the design system.\n" +
                    problems.joinToString("\n") { "  $it" } +
                    "\n\nFix: ${DesignGuard.hints(problems)}" +
                    "\nIf a line really needs the literal, end it with `// design-exception: <reason>`." +
                    "\nSee docs/design/design-system.md, \"Rules\".",
            )
        }
    }

    private fun writeReport(counts: Map<Pair<String, String>, Int>, baseline: Map<Pair<String, String>, Int>) {
        val report = DesignGuard.formatReport(counts) + DesignGuard.formatSlack(counts, baseline)
        File("build/reports/design-guard").apply { mkdirs() }.resolve("report.txt").writeText(report)
        println(report)
    }

    private fun findRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir ?: error("no settings.gradle.kts above ${File("").absolutePath}")
    }
}

/** The guard's rules and bookkeeping, separate from the file walk so they can be tested on strings. */
internal object DesignGuard {
    class Rule(val id: String, val pattern: Regex, val fix: String)

    val rules = listOf(
        Rule("raw-colour", Regex("""\bColor\(\s*0[xX]"""), "take colours from MaterialTheme.colorScheme, Locus.colors or a Tone"),
        Rule(
            "named-colour",
            Regex("""\bColor\.(White|Black|Red|Green|Blue|Yellow|Gray|DarkGray|LightGray|Cyan|Magenta)\b"""),
            "use a theme colour (onPrimary rather than Color.White)",
        ),
        Rule("system-dark-check", Regex("""isSystemInDarkTheme\(|luminance\(\)\s*<\s*0?\.5"""), "read Locus.isDark"),
        // 0.dp and 0.sp are harmless (no padding, no size); anything else is a token.
        Rule(
            "raw-dp",
            Regex("""(?<![\w.])(?!0\.(dp|sp)\b)\d+(\.\d+)?\.(dp|sp)\b"""),
            "use Spacing, IconSize, Stroke, Sizes or Radius for dp and a LocusType role for sp",
        ),
        Rule("alpha-literal", Regex("""alpha\s*=\s*0?\.\d+f"""), "use an Emphasis level"),
        Rule("raw-shape", Regex("""\bRoundedCornerShape\("""), "use MaterialTheme.shapes or Radius.pill"),
        Rule("material-dialog", Regex("""\b(Basic)?AlertDialog\s*\("""), "use ConfirmSheet, or LocusSheet for anything bigger"),
        Rule("material-switch", Regex("""\bSwitch\s*\("""), "use SwitchRow"),
        Rule("material-top-bar", Regex("""\b(CenterAligned|Medium|Large)?TopAppBar\s*\("""), "use LocusTopBar"),
        Rule(
            "material-feedback",
            Regex("""\bSnackbar(Host)?\s*\(|\bToast\.makeText\s*\("""),
            "use Locus.feedback for transient messages and Banner for persistent ones",
        ),
        Rule(
            "material-progress",
            Regex("""\b(Linear|Circular)(Wavy)?ProgressIndicator\s*\("""),
            "use Meter for progress, InlineSpinner or LoadingState for waiting",
        ),
        Rule(
            "material-chip",
            Regex("""\b(Elevated)?(Assist|Filter|Suggestion|Input)Chip\s*\("""),
            "use LocusChip (variant Status, Filter or Count)",
        ),
        Rule(
            "material-type",
            Regex("""MaterialTheme\.typography\.(titleLarge|titleMedium|titleSmall|bodyLarge|bodyMedium|bodySmall|labelLarge|labelMedium)\b"""),
            "use the LocusType role (screenTitle, stateTitle, cardTitle, rowTitle, rowSupporting, supporting, label, chip)",
        ),
        Rule(
            "muted-text",
            Regex("""color\s*=\s*MaterialTheme\.colorScheme\.onSurfaceVariant\b|onSurfaceVariant\.copy\(\s*alpha"""),
            "use SupportingText (or LocusType + onSurfaceVariant only inside a shared component)",
        ),
    )

    private val byId = rules.associateBy { it.id }

    /** `// design-exception: <reason>`; the reason is required. */
    private val exception = Regex("""design-exception:\s*\S""")

    /** Main Kotlin sources of every module except the design system, which is where tokens live. */
    fun sources(root: File): List<File> =
        listOf("app", "core", "feature").map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { f ->
                val path = f.relativeTo(root).invariantSeparatorsPath
                "/src/main/" in path && !path.startsWith("core/designsystem/") && "/build/" !in path
            }
            .sortedBy { it.path }

    /** (rule id, path) → count, skipping comment lines and excused lines. */
    fun count(root: File, files: List<File>, rules: List<Rule> = this.rules): Map<Pair<String, String>, Int> {
        val out = sortedMapOf<Pair<String, String>, Int>(compareBy({ it.first }, { it.second }))
        for (f in files) {
            val path = f.relativeTo(root).invariantSeparatorsPath
            countText(f.readText(), rules).forEach { (rule, n) -> out[rule to path] = n }
        }
        return out
    }

    fun countText(text: String, rules: List<Rule> = this.rules): Map<String, Int> {
        val code = codeLines(text)
        return rules.associate { rule -> rule.id to code.sumOf { rule.pattern.findAll(it).count() } }.filterValues { it > 0 }
    }

    /** The lines that count: not comments, and not excused by a `design-exception:` marker with a reason. */
    fun codeLines(text: String): List<String> {
        val out = ArrayList<String>()
        var excuseNext = false
        for (raw in text.lineSequence()) {
            val line = raw.trimStart()
            val isComment = line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
            val excused = exception.containsMatchIn(line)
            when {
                isComment -> if (excused) excuseNext = true
                excused -> excuseNext = false
                excuseNext -> excuseNext = false
                else -> out += raw
            }
        }
        return out
    }

    fun formatBaseline(counts: Map<Pair<String, String>, Int>): String = buildString {
        appendLine("# Design guard baseline: what feature code outside core/designsystem still does by hand.")
        appendLine("# rule<TAB>file<TAB>count. A file may not exceed its count, and a file not listed must have none.")
        appendLine("# Regenerate only to lower it: LOCUS_DESIGN_GUARD_UPDATE=1 ./gradlew :core:designsystem:testDebugUnitTest --tests '*DesignGuardTest*'")
        counts.forEach { (key, n) -> appendLine("${key.first}\t${key.second}\t$n") }
    }

    fun parseBaseline(text: String): Map<Pair<String, String>, Int> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 3) (parts[0] to parts[1]) to (parts[2].toIntOrNull() ?: return@mapNotNull null) else null
            }
            .toMap()

    /** Lines describing every (rule, file) whose count went above its baseline. */
    fun regressions(counts: Map<Pair<String, String>, Int>, baseline: Map<Pair<String, String>, Int>): List<String> =
        counts.mapNotNull { (key, n) ->
            val allowed = baseline[key] ?: 0
            if (n > allowed) "${key.second}: ${key.first} $n (baseline $allowed)" else null
        }

    /** One fix hint per rule named in [problems]. */
    fun hints(problems: List<String>): String =
        problems.mapNotNull { p -> byId.keys.firstOrNull { ": $it " in p } }.distinct()
            .joinToString("; ") { id -> "$id → ${byId.getValue(id).fix}" }

    fun formatReport(counts: Map<Pair<String, String>, Int>): String = buildString {
        appendLine("Design guard report (outside core/designsystem): ${counts.values.sum()} in ${counts.keys.map { it.second }.distinct().size} files")
        val byRule = counts.entries.groupBy { it.key.first }
        for (rule in rules) {
            val entries = byRule[rule.id] ?: continue
            appendLine()
            appendLine("${rule.id}: ${entries.sumOf { it.value }} in ${entries.size} files")
            val byModule = entries.groupBy { moduleOf(it.key.second) }
            byModule.entries.sortedByDescending { e -> e.value.sumOf { it.value } }.forEach { (module, es) ->
                appendLine("  $module: ${es.sumOf { it.value }}")
            }
        }
    }

    /** Baseline entries that are now higher than the code: the migration PR should lower them. */
    fun formatSlack(counts: Map<Pair<String, String>, Int>, baseline: Map<Pair<String, String>, Int>): String {
        val slack = baseline.filter { (key, allowed) -> (counts[key] ?: 0) < allowed }
        if (slack.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("Baseline can be lowered (${slack.size} entries): run with LOCUS_DESIGN_GUARD_UPDATE=1.")
            slack.entries.sortedBy { it.key.second }.forEach { (key, allowed) ->
                appendLine("  ${key.second}: ${key.first} ${counts[key] ?: 0} (baseline $allowed)")
            }
        }
    }

    fun moduleOf(path: String): String =
        path.split('/').take(if (path.startsWith("app/")) 1 else 2).joinToString("/")
}
