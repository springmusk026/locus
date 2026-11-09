package dev.locus.core.designsystem

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps new UI text out of Kotlin while the app moves to string resources (docs/design/localization.md).
 * Counts, per module, the string literals handed to Compose's text APIs in the UI modules' `src/main`
 * Kotlin, and compares them with hardcoded-text-baseline.properties. A module may not go above its
 * line; a module that isn't listed may have none. When a module goes down, the test passes and says so;
 * lower its line with
 * `LOCUS_HARDCODED_TEXT_UPDATE=1 ./gradlew :core:designsystem:testDebugUnitTest --tests '*HardcodedTextGuardTest*'`,
 * never to let something new in.
 *
 * The heuristic is deliberately simple ([HardcodedText.patterns]): it looks for a literal that opens
 * right after `Text(`, `text =`, `title =` and the like on one line. It misses literals inside an `if`
 * or a helper, and counts a few that are fine (an id in a `label =`); what matters is that the count
 * for unchanged code stays the same. Comment lines don't count.
 */
class HardcodedTextGuardTest {

    private val root: File = findRoot()
    private val baselineFile = File(root, "core/designsystem/hardcoded-text-baseline.properties")

    @Test
    fun noNewHardcodedUiText() {
        val counts = HardcodedText.count(root)
        assertTrue(counts.isNotEmpty(), "found no UI modules under $root")
        if (System.getenv("LOCUS_HARDCODED_TEXT_UPDATE") == "1") {
            baselineFile.writeText(HardcodedText.formatBaseline(counts))
            println("Hardcoded text: baseline rewritten, ${counts.values.sum()} literals in ${counts.size} modules.")
            return
        }
        val baseline = HardcodedText.parseBaseline(baselineFile.takeIf { it.exists() }?.readText().orEmpty())
        val up = counts.filter { (module, n) -> n > (baseline[module] ?: 0) }
        val down = baseline.filter { (module, allowed) -> (counts[module] ?: 0) < allowed }
        if (down.isNotEmpty()) {
            println(
                "Hardcoded text went down; lower the baseline with LOCUS_HARDCODED_TEXT_UPDATE=1:\n" +
                    down.entries.joinToString("\n") { (m, allowed) -> "  $m: ${counts[m] ?: 0} (baseline $allowed)" },
            )
        }
        if (up.isNotEmpty()) {
            fail(
                "Hardcoded text: new UI text written as Kotlin literals.\n" +
                    up.entries.joinToString("\n") { (m, n) -> "  $m: $n (baseline ${baseline[m] ?: 0})" } +
                    "\n\nPut the text in the module's res/values/strings.xml and read it with stringResource(), or " +
                    "UiText outside a composable. See docs/design/localization.md.",
            )
        }
    }

    @Test
    fun patternsCountLiteralsHandedToTextApis() {
        fun count(line: String) = HardcodedText.countText(line)
        assertEquals(1, count("""Text("Hello", style = LocusType.rowTitle)"""))
        assertEquals(1, count("""TextButton(onClick = onDismiss) { Text("Not now") }"""))
        assertEquals(3, count("""ListRow(title = "Models", subtitle = "x", summary = "Loading")"""))
        assertEquals(1, count("""Icon(LocusIcons.Check, contentDescription = "Selected")"""))
        assertEquals(1, count("""LocusChip("Remote on", tone = Tone.Live)"""))
        assertEquals(1, count("""item { SectionLabel("Providers", SectionPadding) }"""))
        assertEquals(1, count("""feedback.show("Copied")"""))
        assertEquals(1, count("""OutlinedTextField(value, onChange, label = { Text("Name") })"""))
        assertEquals(1, count("""Text(text = "${'$'}n files")"""))
        // Not text a person reads, or not a literal.
        assertEquals(0, count("""Text(title, style = LocusType.rowTitle)"""))
        assertEquals(0, count("""Text(stringResource(R.string.settings_hub_title))"""))
        assertEquals(0, count("""Text("")"""))
        assertEquals(0, count("""val subtitleText = "x""""))
        assertEquals(0, count("""// Text("in a comment")"""))
    }

    private fun findRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir ?: error("no settings.gradle.kts above ${File("").absolutePath}")
    }
}

/** The hardcoded-text count, separate from the test so its patterns can be checked on strings. */
internal object HardcodedText {

    /** The modules with UI text: the app, every feature, and the core modules that draw screens. */
    private val coreModules = listOf("core/designsystem", "core/ui", "core/browser", "core/markdown", "core/terminal")

    /**
     * A literal (not `""`) that opens straight after a text API, on the same line. A Button's label is
     * its `{ Text("…") }`, so `Text(` covers it.
     */
    val patterns = listOf(
        Regex("""\bText\(\s*"(?!")"""),
        Regex("""\b(text|title|subtitle|label|summary|contentDescription|placeholder)\s*=\s*"(?!")"""),
        Regex("""\b(LocusChip|SectionLabel)\(\s*"(?!")"""),
        Regex("""\b(showMessage|showSnackbar|feedback\.show)\(\s*"(?!")"""),
    )

    fun modules(root: File): List<String> {
        val features = File(root, "feature").listFiles { f -> f.isDirectory && File(f, "src/main").isDirectory }
            .orEmpty().map { "feature/${it.name}" }
        return (listOf("app") + coreModules + features).filter { File(root, "$it/src/main").isDirectory }.sorted()
    }

    /** Module → count, for every module in [modules] (zero included, so a new one shows up). */
    fun count(root: File): Map<String, Int> = modules(root).associateWith { module ->
        File(root, "$module/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sumOf { countText(it.readText()) }
    }

    fun countText(text: String): Int = DesignGuard.codeLines(text).sumOf { line -> patterns.sumOf { it.findAll(line).count() } }

    fun formatBaseline(counts: Map<String, Int>): String = buildString {
        appendLine("# Hardcoded UI text per module: string literals passed to Compose text APIs (HardcodedTextGuardTest).")
        appendLine("# A module may not go above its count. Lower it as text moves to strings.xml (docs/design/localization.md):")
        appendLine("# LOCUS_HARDCODED_TEXT_UPDATE=1 ./gradlew :core:designsystem:testDebugUnitTest --tests '*HardcodedTextGuardTest*'")
        counts.toSortedMap().forEach { (module, n) -> appendLine("$module=$n") }
    }

    fun parseBaseline(text: String): Map<String, Int> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val key = line.substringBefore('=', "").trim()
                val n = line.substringAfter('=').trim().toIntOrNull()
                if (key.isEmpty() || n == null) null else key to n
            }
            .toMap()
}
