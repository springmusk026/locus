package dev.locus.core.ondevice

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every regex literal in the repo must compile on Android, not only on the JVM.
 *
 * Android's java.util.regex is backed by ICU, which is stricter than the JVM engine these unit tests
 * run on. `Regex("""\{[{%]-?(.*?)-?[%}]}""")` compiled here and passed GgufTemplateTest, then threw
 * PatternSyntaxException from GgufTemplate's static initializer on the phone, so every GGUF import
 * failed. Robolectric doesn't help (it runs the JVM engine too), and the emulator suite is nightly, so
 * this guard reads the sources instead: it finds each `Regex(...)`, `Pattern.compile(...)`,
 * `.toRegex()` and `.toPattern()` whose pattern is a string literal (or literals joined with `+`, or
 * `Regex.escape(...)`), and checks it against the ICU rules the JVM doesn't enforce ([IcuRegex]).
 * Patterns built at runtime (globs, user input) aren't literals and are skipped; they escape what they
 * insert.
 *
 * The rules were checked against ICU itself (uregex_open in ICU4C, the library Android's Pattern
 * calls into).
 */
class IcuRegexGuardTest {

    @Test
    fun everyRegexLiteralCompilesUnderIcuRules() {
        val root = findRoot()
        val found = RegexLiterals.sources(root).flatMap { file ->
            val path = file.relativeTo(root).path
            val literals = runCatching { RegexLiterals.find(file.readText()) }.getOrElse { throw AssertionError("couldn't read $path", it) }
            literals.map { (line, pattern) -> Triple(path, line, pattern) }
        }
        // A broken extractor would find nothing and pass; the repo has a few hundred literals.
        assertTrue(found.size > 200, "found only ${found.size} regex literals under $root")
        // The patterns as checked, one per line, for feeding to a real ICU (uregex_open) by hand.
        File("build/reports/icu-regex").apply { mkdirs() }.resolve("patterns.txt").writeText(
            found.joinToString("") { (path, line, pattern) -> "$path:$line\t${pattern.replace("\\", "\\\\").replace("\n", "\\n")}\n" },
        )
        val problems = found.mapNotNull { (path, line, pattern) ->
            val jvm = runCatching { Regex(pattern) }.exceptionOrNull()?.message?.lineSequence()?.firstOrNull()
            val icu = IcuRegex.problems(pattern) + listOfNotNull(jvm?.let { "doesn't compile: $it" })
            if (icu.isEmpty()) null else "$path:$line  ${pattern.take(120)}\n    ${icu.joinToString("\n    ")}"
        }
        if (problems.isNotEmpty()) {
            fail(
                "Regex literals Android's ICU engine would reject (the JVM accepts them, so other tests pass):\n" +
                    problems.joinToString("\n") +
                    "\n\nEscape literal braces as \\{ and \\}, or put them in a class: [{] [}].",
            )
        }
    }

    @Test
    fun noOptionAndroidLacks() {
        val root = findRoot()
        val self = "IcuRegexGuardTest.kt"
        val hits = RegexLiterals.sources(root).filter { it.name != self }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (Regex("""\b(CANON_EQ|UNICODE_CHARACTER_CLASS)\b""").containsMatchIn(line)) "${file.relativeTo(root).path}:${i + 1}" else null
            }
        }
        assertEquals(emptyList(), hits, "Android's Pattern doesn't support CANON_EQ or UNICODE_CHARACTER_CLASS")
    }

    @Test
    fun theCheckerCatchesWhatIcuRejects() {
        // Each of these compiles on the JVM and fails in ICU's uregex_open.
        listOf(
            """\{[{%]-?(.*?)-?[%}]}""",
            "a}",
            "(})",
            "{a}",
            "x{,3}",
            "a{ 2}",
            "(?U)\\w",
            "(?iU:x)",
        ).forEach { assertTrue(IcuRegex.problems(it).isNotEmpty(), "missed: $it") }
    }

    @Test
    fun theCheckerAcceptsWhatIcuAccepts() {
        listOf(
            """\{[{%]-?(.*?)-?[%}]\}""",
            "x{2}",
            "x{2,}",
            "x{2,5}+",
            "\\d{1,3}(\\.\\d{1,3}){3}",
            "[{}]",
            "[^}]*",
            "[]}]",
            "\\}",
            "\\Q{}\\E",
            "\\p{L}+\\P{M}",
            "\\x{1F600}",
            "\\N{LATIN SMALL LETTER A}",
            "[a-z&&[^b]]",
            "(?<name>a)\\k<name>",
            "(?i)(?<![\\w.])x",
            "(?<=a{1,3})b",
        ).forEach { assertEquals(emptyList(), IcuRegex.problems(it), it) }
    }

    @Test
    fun literalsAreReadLikeKotlinReadsThem() {
        val src = requireNotNull(javaClass.getResource("/regex-literals.kt.txt")).readText()
        assertEquals(
            listOf(
                2 to """\{x\}""",
                3 to """\d{2}\}$""",
                7 to """a\}""",
                8 to """\Qx\E(\d+)""",
                11 to """x\}""",
                12 to "[x]",
                13 to "[\\r\\n]+",
                14 to "'\"",
            ),
            RegexLiterals.find(src),
        )
    }

    private fun findRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir ?: error("no settings.gradle.kts above ${File("").absolutePath}")
    }
}

/** Where ICU's regex syntax is stricter than java.util.regex on the JVM. */
internal object IcuRegex {
    private val interval = Regex("""\{\d+(,\d*)?\}""")
    private val flagGroup = Regex("""\(\?([a-zA-Z-]*)[:)]""")

    fun problems(pattern: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        var depth = 0 // character class nesting; ICU and the JVM both nest [a[b]]
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    i = skipEscape(pattern, i)
                    continue
                }
                c == '[' -> {
                    depth++
                    i++
                    if (pattern.getOrNull(i) == '^') i++
                    if (pattern.getOrNull(i) == ']') i++ // a leading ] is literal
                    continue
                }
                depth > 0 && c == ']' -> depth--
                depth > 0 -> Unit
                c == '{' -> {
                    val m = interval.matchAt(pattern, i)
                    if (m == null || i == 0 || pattern[i - 1] in "(|") {
                        out += "unescaped '{' at $i that isn't a {n}, {n,} or {n,m} quantifier"
                    } else {
                        i = m.range.last + 1
                        continue
                    }
                }
                c == '}' -> out += "unescaped '}' at $i (ICU rejects it; write \\})"
                c == '(' -> flagGroup.matchAt(pattern, i)?.let { m ->
                    if ('U' in m.groupValues[1]) out += "inline flag U at $i (UNICODE_CHARACTER_CLASS) isn't supported by ICU"
                }
            }
            i++
        }
        return out
    }

    /** Index after the escape at [i]: \Q...\E, \p{..}, \x{..}, \N{..}, or one escaped character. */
    private fun skipEscape(p: String, i: Int): Int {
        val next = p.getOrNull(i + 1) ?: return i + 1
        return when {
            next == 'Q' -> p.indexOf("\\E", i + 2).let { if (it < 0) p.length else it + 2 }
            next in "pPxN" && p.getOrNull(i + 2) == '{' -> p.indexOf('}', i + 3).let { if (it < 0) p.length else it + 1 }
            else -> i + 2
        }
    }
}

/** Regex literals in Kotlin and Java sources, as the compiler sees them (escapes and templates resolved). */
internal object RegexLiterals {
    private val skipDirs = setOf("build", ".gradle", ".git", ".idea", ".claude", "node_modules")

    fun sources(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it == root || it.name !in skipDirs }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") && "${File.separator}src${File.separator}" in it.path }
            .toList()

    private val forward = Regex("""(?<![\w.])(?:Regex|Pattern\.compile)\(""")
    private val backward = Regex("""\.to(?:Regex|Pattern)\(""")

    /** (line, pattern) for each regex whose pattern is a literal. */
    fun find(src: String): List<Pair<Int, String>> {
        val lexer = Lexer(src)
        val strings = lexer.strings()
        val code = blankOut(src, strings, lexer.comments())
        val out = mutableListOf<Pair<Int, String>>()
        for (m in forward.findAll(code)) {
            argument(src, m.range.last + 1, strings)?.let { out += line(src, m.range.first) to it }
        }
        for (m in backward.findAll(code)) {
            strings.firstOrNull { it.end == m.range.first }?.let { out += line(src, m.range.first) to it.value }
        }
        return out.sortedBy { it.first }
    }

    private fun line(src: String, at: Int) = src.substring(0, at).count { it == '\n' } + 1

    /** The first argument at [start] if it is literals and Regex.escape calls joined with +; null otherwise. */
    private fun argument(src: String, start: Int, strings: List<Lexer.Str>): String? {
        val sb = StringBuilder()
        var i = start
        while (true) {
            i = skipSpace(src, i)
            val s = strings.firstOrNull { it.start == i }
            when {
                s != null -> {
                    sb.append(s.value); i = s.end
                }
                src.startsWith("Regex.escape(", i) -> {
                    sb.append("\\Qx\\E"); i = matchingParen(src, i + "Regex.escape".length) ?: return null
                }
                else -> return null
            }
            i = skipSpace(src, i)
            when (src.getOrNull(i)) {
                '+' -> i++
                ',', ')' -> return sb.toString()
                else -> return null // e.g. .trimIndent(): not the literal as written
            }
        }
    }

    private fun skipSpace(src: String, from: Int): Int {
        var i = from
        while (i < src.length && src[i].isWhitespace()) i++
        return i
    }

    private fun matchingParen(src: String, open: Int): Int? {
        var depth = 0
        for (i in open until src.length) {
            if (src[i] == '(') depth++
            if (src[i] == ')' && --depth == 0) return i + 1
        }
        return null
    }

    /**
     * [src] with comments and string text replaced by spaces, so markers inside them don't count. The
     * code inside a string's templates stays: `"${s.replace(Regex("x"), "")}"` has a regex.
     */
    private fun blankOut(src: String, strings: List<Lexer.Str>, comments: List<IntRange>): String {
        val chars = src.toCharArray()
        fun blank(r: IntRange) {
            for (k in r) if (chars[k] != '\n') chars[k] = ' '
        }
        comments.forEach(::blank)
        // Outer strings start first; a nested string is blanked after its template is restored.
        strings.sortedBy { it.start }.forEach { s ->
            blank(s.start until s.end)
            s.templates.forEach { r -> for (k in r) chars[k] = src[k] }
        }
        return String(chars)
    }

    /** A small Kotlin/Java lexer: string literals (with their compiled value) and comments. */
    private class Lexer(private val src: String) {
        /** A literal from [start] to [end]; [templates] are the code ranges of its `${...}` expressions. */
        class Str(val start: Int, val end: Int, val value: String, val templates: List<IntRange>)

        private val strs = mutableListOf<Str>()
        private val coms = mutableListOf<IntRange>()

        init {
            var i = 0
            while (i < src.length) {
                i = when {
                    src.startsWith("//", i) -> src.indexOf('\n', i).let { e -> (if (e < 0) src.length else e).also { coms += i until it } }
                    src.startsWith("/*", i) -> blockComment(i).also { coms += i until it }
                    src[i] == '\'' -> charLiteral(i)
                    // A backticked name (`fun `it's "quoted"`()`) holds no string.
                    src[i] == '`' -> src.indexOf('`', i + 1).let { e -> if (e < 0 || '\n' in src.substring(i, e)) i + 1 else e + 1 }
                    src[i] == '"' -> string(i)
                    else -> i + 1
                }
            }
        }

        fun strings(): List<Str> = strs
        fun comments(): List<IntRange> = coms

        private fun blockComment(start: Int): Int {
            var depth = 0
            var i = start
            while (i < src.length) {
                when {
                    src.startsWith("/*", i) -> { depth++; i += 2 }
                    src.startsWith("*/", i) -> { i += 2; if (--depth == 0) return i }
                    else -> i++
                }
            }
            return src.length
        }

        private fun charLiteral(start: Int): Int {
            val close = if (src.getOrNull(start + 1) == '\\') src.indexOf('\'', start + 3) else start + 2
            return if (close in (start + 2)..(start + 8) && src.getOrNull(close) == '\'') close + 1 else start + 1
        }

        /** Records the string at [start] (templates become `x`); returns the index after it. */
        private fun string(start: Int): Int {
            val sb = StringBuilder()
            val templates = mutableListOf<IntRange>()
            val end = read(start, sb, templates)
            strs += Str(start, end, sb.toString(), templates)
            return end
        }

        private fun read(start: Int, sb: StringBuilder, templates: MutableList<IntRange>): Int {
            val raw = src.startsWith("\"\"\"", start)
            var i = start + if (raw) 3 else 1
            while (i < src.length) {
                val c = src[i]
                when {
                    raw && src.startsWith("\"\"\"", i) -> {
                        var end = i + 3
                        while (src.getOrNull(end) == '"') { sb.append('"'); end++ } // """a"""" ends with a quote
                        return end
                    }
                    !raw && c == '"' -> return i + 1
                    !raw && c == '\\' -> {
                        val e = src.getOrNull(i + 1)
                        when (e) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'u' -> src.substring(i + 2, minOf(i + 6, src.length)).toIntOrNull(16)?.let { sb.append(it.toChar()); i += 4 }
                            null -> {}
                            else -> sb.append(e)
                        }
                        i += 2
                        continue
                    }
                    c == '$' && src.getOrNull(i + 1) == '{' -> {
                        val end = template(i + 1)
                        templates += (i + 2) until (end - 1)
                        val expr = src.substring(i + 2, end - 1).trim()
                        sb.append(if (expr == "'$'") "$" else if (expr.startsWith("Regex.escape(")) "\\Qx\\E" else "x")
                        i = end
                        continue
                    }
                    c == '$' && src.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true -> {
                        i++
                        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                        sb.append('x')
                        continue
                    }
                    else -> sb.append(c)
                }
                i++
            }
            return src.length
        }

        /** Index after the `}` closing the template whose `{` is at [open], skipping nested strings. */
        private fun template(open: Int): Int {
            var depth = 0
            var i = open
            while (i < src.length) {
                when (src[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i + 1
                    '"' -> { i = string(i); continue }
                    '\'' -> { i = charLiteral(i); continue }
                }
                i++
            }
            return src.length
        }
    }
}
