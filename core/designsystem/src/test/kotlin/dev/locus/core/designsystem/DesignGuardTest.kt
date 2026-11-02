package dev.locus.core.designsystem

import java.io.File
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps raw colours and hand-rolled dark-mode checks out of feature code (docs/design/design-system.md,
 * "Guardrail"). Scans every module's `src/main` Kotlin except this one.
 *
 * Enforced, against a baseline (design-guard-baseline.txt, one line per rule and file with today's
 * count): a file may not gain a `Color(0x…)` literal or an `isSystemInDarkTheme()` / luminance check.
 * Counts going down are welcome; regenerate the baseline then. Report-only: raw `N.dp` values,
 * `alpha = 0.N` literals and named colours (`Color.White`), counted into
 * build/reports/design-guard/report.txt so a migration PR can show the numbers falling.
 *
 * To regenerate the baseline after removing violations (never to add new ones):
 * `LOCUS_DESIGN_GUARD_UPDATE=1 ./gradlew :core:designsystem:testDebugUnitTest --tests '*DesignGuardTest*'`
 */
class DesignGuardTest {

    private val root: File = findRoot()
    private val baselineFile = File(root, "core/designsystem/design-guard-baseline.txt")

    @Test
    fun noNewRawColoursOrDarkChecksOutsideTheDesignSystem() {
        val sources = DesignGuard.sources(root)
        assertTrue(sources.isNotEmpty(), "found no sources under $root")
        val counts = DesignGuard.count(root, sources, DesignGuard.enforced)
        if (System.getenv("LOCUS_DESIGN_GUARD_UPDATE") == "1") {
            baselineFile.writeText(DesignGuard.formatBaseline(counts))
            return
        }
        val baseline = DesignGuard.parseBaseline(baselineFile.takeIf { it.exists() }?.readText().orEmpty())
        val problems = DesignGuard.regressions(counts, baseline)
        writeReport(sources)
        if (problems.isNotEmpty()) {
            fail(
                "Design guard: new raw colours or dark-mode checks outside core/designsystem.\n" +
                    problems.joinToString("\n") { "  $it" } +
                    "\nUse a theme colour (MaterialTheme.colorScheme, Locus.colors, Tone) and Locus.isDark. " +
                    "See docs/design/design-system.md.",
            )
        }
    }

    private fun writeReport(sources: List<File>) {
        val all = DesignGuard.count(root, sources, DesignGuard.enforced + DesignGuard.reportOnly)
        val report = DesignGuard.formatReport(all)
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
    class Rule(val id: String, val pattern: Regex)

    val enforced = listOf(
        Rule("raw-colour", Regex("""\bColor\(\s*0[xX]""")),
        Rule("system-dark-check", Regex("""isSystemInDarkTheme\(|luminance\(\)\s*<\s*0?\.5""")),
    )

    val reportOnly = listOf(
        // 0.dp is harmless (no padding); anything else should be a token.
        Rule("raw-dp", Regex("""(?<![\w.])(?!0\.dp\b)\d+(\.\d+)?\.dp\b""")),
        Rule("alpha-literal", Regex("""alpha\s*=\s*0?\.\d+f""")),
        Rule("named-colour", Regex("""\bColor\.(White|Black|Red|Green|Blue|Yellow|Gray|DarkGray|LightGray|Cyan|Magenta)\b""")),
    )

    /** Main Kotlin sources of every module except the design system, which is where tokens live. */
    fun sources(root: File): List<File> =
        listOf("app", "core", "feature").map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { f ->
                val path = f.relativeTo(root).invariantSeparatorsPath
                "/src/main/" in path && !path.startsWith("core/designsystem/") && "/build/" !in path
            }
            .sortedBy { it.path }

    /** (rule id, path) → count, skipping comment lines. */
    fun count(root: File, files: List<File>, rules: List<Rule>): Map<Pair<String, String>, Int> {
        val out = sortedMapOf<Pair<String, String>, Int>(compareBy({ it.first }, { it.second }))
        for (f in files) {
            val path = f.relativeTo(root).invariantSeparatorsPath
            countText(f.readText(), rules).forEach { (rule, n) -> out[rule to path] = n }
        }
        return out
    }

    fun countText(text: String, rules: List<Rule>): Map<String, Int> {
        val code = text.lineSequence().filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }.toList()
        return rules.associate { rule -> rule.id to code.sumOf { rule.pattern.findAll(it).count() } }.filterValues { it > 0 }
    }

    fun formatBaseline(counts: Map<Pair<String, String>, Int>): String = buildString {
        appendLine("# Design guard baseline: existing raw colours and dark-mode checks outside core/designsystem.")
        appendLine("# rule<TAB>file<TAB>count. A file may not exceed its count; regenerate only to lower it.")
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

    fun formatReport(counts: Map<Pair<String, String>, Int>): String = buildString {
        appendLine("Design guard report (outside core/designsystem)")
        val byRule = counts.entries.groupBy { it.key.first }
        for ((rule, entries) in byRule) {
            appendLine()
            appendLine("$rule: ${entries.sumOf { it.value }} in ${entries.size} files")
            val byModule = entries.groupBy { it.key.second.split('/').take(if (it.key.second.startsWith("app/")) 1 else 2).joinToString("/") }
            byModule.entries.sortedByDescending { e -> e.value.sumOf { it.value } }.forEach { (module, es) ->
                appendLine("  $module: ${es.sumOf { it.value }}")
            }
        }
    }
}
