package dev.locus.core.model

/**
 * Build and test runs in `run_command`, recognised so the chat can show a verification card (pass or
 * fail, duration, failing tests or the first errors) instead of only the raw output. Pure text work:
 * the command the agent ran and the output it got back. See docs/design/change-tracking.md.
 */
object Verification {
    enum class Kind { TEST, BUILD, CHECK }

    /** A recognised command: what it does and which tool runs it ("Gradle", "pytest"). */
    data class Command(val kind: Kind, val tool: String)

    /** What the card shows. [passed] is null when the run timed out. */
    data class Outcome(
        val command: Command,
        val passed: Boolean?,
        val exitCode: Int?,
        val durationMs: Long?,
        /** The runner's own tally, e.g. "12 passed, 1 failed", when it printed one. */
        val summary: String?,
        /** Names of failing tests, in order, at most [MAX_ITEMS]. */
        val failures: List<String>,
        /** Total failing tests found, which may be more than [failures] holds. */
        val failureCount: Int,
        /** The first error lines, when no failing test names were found. */
        val errors: List<String>,
    )

    const val MAX_ITEMS = 8

    /** The command [line] runs, when it's a build, test or check; null otherwise. Looks at each `&&`/`;`/`||` step and takes the last match. */
    fun detect(line: String): Command? =
        line.split("&&", "||", ";", "\n").map { normalize(it) }.mapNotNull(::detectStep).lastOrNull()

    /** Strips what doesn't change which program runs: env assignments, `timeout N`, `uv run`, `poetry run`, `npx`, `bundle exec`. */
    private fun normalize(step: String): String {
        var s = step.substringBefore('|').substringBefore(" 2>").substringBefore(" >").trim()
        var changed = true
        while (changed) {
            val before = s
            s = s.replace(LEADING_ENV, "").replace(LEADING_WRAPPER, "").trim()
            changed = s != before
        }
        return s
    }

    private fun detectStep(s: String): Command? {
        val words = s.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val program = words[0].substringAfterLast('/')
        val args = words.drop(1).filterNot { it.startsWith("-") }
        fun has(vararg names: String) = args.any { a -> names.any { a == it || a.substringAfterLast(':') == it } }
        return when (program) {
            "gradlew", "gradle" -> when {
                args.any { a -> a.substringAfterLast(':').let { it == "test" || it == "check" || it.matches(GRADLE_TEST) } } -> Command(Kind.TEST, "Gradle")
                args.any { a -> a.substringAfterLast(':').let { it == "build" || it.startsWith("assemble") || it.startsWith("compile") } } -> Command(Kind.BUILD, "Gradle")
                args.any { a -> a.substringAfterLast(':').startsWith("lint") } -> Command(Kind.CHECK, "Gradle")
                else -> null
            }
            "npm", "pnpm", "yarn", "bun" -> {
                val script = if (args.firstOrNull() == "run" || args.firstOrNull() == "run-script") args.getOrNull(1) else args.firstOrNull()
                when {
                    script == null -> null
                    script == "test" || script.startsWith("test:") || script == "t" -> Command(Kind.TEST, program)
                    script == "build" || script.startsWith("build:") -> Command(Kind.BUILD, program)
                    script in setOf("lint", "typecheck", "check", "tsc") -> Command(Kind.CHECK, program)
                    else -> null
                }
            }
            "jest", "vitest", "mocha" -> Command(Kind.TEST, program)
            "tsc" -> Command(Kind.CHECK, "tsc")
            "eslint" -> Command(Kind.CHECK, "ESLint")
            "pytest", "py.test" -> Command(Kind.TEST, "pytest")
            "python", "python3" -> when {
                words.getOrNull(1) == "-m" && words.getOrNull(2) == "pytest" -> Command(Kind.TEST, "pytest")
                words.getOrNull(1) == "-m" && words.getOrNull(2) == "unittest" -> Command(Kind.TEST, "unittest")
                else -> null
            }
            "cargo" -> when (args.firstOrNull()) {
                "test", "nextest" -> Command(Kind.TEST, "cargo")
                "build" -> Command(Kind.BUILD, "cargo")
                "check", "clippy" -> Command(Kind.CHECK, "cargo")
                else -> null
            }
            "go" -> when (args.firstOrNull()) {
                "test" -> Command(Kind.TEST, "go")
                "build" -> Command(Kind.BUILD, "go")
                "vet" -> Command(Kind.CHECK, "go")
                else -> null
            }
            "mvn", "mvnw" -> when {
                has("test", "verify", "install") -> Command(Kind.TEST, "Maven")
                has("package", "compile") -> Command(Kind.BUILD, "Maven")
                else -> null
            }
            "make" -> when {
                args.isEmpty() || has("all", "build") -> Command(Kind.BUILD, "make")
                has("test", "check") -> Command(Kind.TEST, "make")
                else -> null
            }
            "ctest" -> Command(Kind.TEST, "CTest")
            "cmake" -> if (words.contains("--build")) Command(Kind.BUILD, "CMake") else null
            "dotnet" -> when (args.firstOrNull()) {
                "test" -> Command(Kind.TEST, ".NET")
                "build" -> Command(Kind.BUILD, ".NET")
                else -> null
            }
            "rspec" -> Command(Kind.TEST, "RSpec")
            "rake" -> if (args.isEmpty() || has("test", "spec")) Command(Kind.TEST, "rake") else null
            "phpunit" -> Command(Kind.TEST, "PHPUnit")
            else -> null
        }
    }

    /**
     * Reads `run_command`'s [result] for [command]: its status line ("Exit code 1 (12.3 s)." or "Stopped
     * after …"), then the output. [isError] is the tool result's flag.
     */
    fun parse(command: Command, result: String, isError: Boolean): Outcome {
        val status = result.lineSequence().firstOrNull().orEmpty()
        val timedOut = status.startsWith("Stopped after")
        val exit = EXIT.find(status)?.groupValues?.get(1)?.toIntOrNull()
        val duration = DURATION.find(status)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val output = result.lines().drop(1).filterNot { it.startsWith(SAVED_NOTE) }
        val passed = when {
            timedOut -> null
            exit != null -> exit == 0
            else -> !isError
        }
        val failures = failingTests(output).distinct()
        val errors = if (failures.isEmpty() && passed != true) errorLines(output) else emptyList()
        return Outcome(command, passed, exit, duration, summary(output), failures.take(MAX_ITEMS), failures.size, errors)
    }

    /** "Tests passed", "Build failed", "Check timed out". */
    fun title(outcome: Outcome): String {
        val what = when (outcome.command.kind) {
            Kind.TEST -> "Tests"
            Kind.BUILD -> "Build"
            Kind.CHECK -> "Check"
        }
        return when (outcome.passed) {
            true -> "$what passed"
            false -> "$what failed"
            null -> "$what timed out"
        }
    }

    private fun failingTests(lines: List<String>): List<String> = lines.mapNotNull { raw ->
        val line = ANSI.replace(raw, "").trimEnd()
        GRADLE_FAILED.matchEntire(line)?.let { return@mapNotNull "${it.groupValues[1]} > ${it.groupValues[2]}" }
        PYTEST_FAILED.matchEntire(line)?.let { return@mapNotNull it.groupValues[1] }
        CARGO_FAILED.matchEntire(line)?.let { return@mapNotNull it.groupValues[1] }
        GO_FAILED.matchEntire(line)?.let { return@mapNotNull it.groupValues[1] }
        JEST_FAILED.matchEntire(line)?.let { m -> return@mapNotNull m.groupValues[1].takeUnless { it.startsWith("Console") } }
        VITEST_FAILED.matchEntire(line)?.let { return@mapNotNull it.groupValues[1] }
        DOTNET_FAILED.matchEntire(line)?.let { return@mapNotNull it.groupValues[1] }
        UNITTEST_FAILED.matchEntire(line)?.let { return@mapNotNull "${it.groupValues[2]}.${it.groupValues[1]}" }
        null
    }

    /** The first lines that look like errors; failing that, the last lines of output. */
    private fun errorLines(lines: List<String>): List<String> {
        val clean = lines.map { ANSI.replace(it, "").trimEnd() }.filter { it.isNotBlank() }
        val wrong = clean.indexOfFirst { it.trim() == "* What went wrong:" }
        if (wrong >= 0) return clean.drop(wrong + 1).takeWhile { !it.startsWith("* ") }.take(MAX_ITEMS).map { it.trim().take(MAX_LINE) }
        val errors = clean.filter { ERROR_LINE.containsMatchIn(it) }
        return (errors.ifEmpty { clean.takeLast(MAX_ITEMS) }).take(MAX_ITEMS).map { it.trim().take(MAX_LINE) }
    }

    /** The runner's last tally line, cleaned up. */
    private fun summary(lines: List<String>): String? = lines.map { ANSI.replace(it, "").trim() }.asReversed().firstNotNullOfOrNull { line ->
        PYTEST_SUMMARY.matchEntire(line)?.groupValues?.get(1)
            ?: JEST_SUMMARY.matchEntire(line)?.groupValues?.get(1)?.trim()
            ?: CARGO_SUMMARY.matchEntire(line)?.groupValues?.get(1)
            ?: GRADLE_SUMMARY.matchEntire(line)?.groupValues?.get(1)
            ?: MAVEN_SUMMARY.matchEntire(line)?.groupValues?.get(1)
            ?: DOTNET_SUMMARY.matchEntire(line)?.groupValues?.get(1)
            ?: UNITTEST_SUMMARY.matchEntire(line)?.value
    }

    private const val MAX_LINE = 300
    private const val SAVED_NOTE = "[The output was too long to show in full"

    private val WHITESPACE = Regex("\\s+")
    private val LEADING_ENV = Regex("^([A-Za-z_][A-Za-z0-9_]*=\\S*\\s+)+")
    private val LEADING_WRAPPER = Regex("^(timeout\\s+\\S+|uv\\s+run|poetry\\s+run|pipenv\\s+run|npx|pnpm\\s+exec|bundle\\s+exec|exec|time|env)\\s+")
    private val GRADLE_TEST = Regex("test\\w*|\\w*Test|connected\\w*Test")
    private val EXIT = Regex("^Exit code (-?\\d+)")
    private val DURATION = Regex("\\(([\\d.]+) s\\)")
    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    private val GRADLE_FAILED = Regex("^\\s*([\\w.$]+) > (.+?) FAILED$")
    private val PYTEST_FAILED = Regex("^(?:FAILED|ERROR) (\\S+)(?: - .*)?$")
    private val CARGO_FAILED = Regex("^test (\\S+) \\.\\.\\. FAILED$")
    private val GO_FAILED = Regex("^\\s*--- FAIL: (\\S+).*$")
    private val JEST_FAILED = Regex("^\\s*● (.+)$")
    private val VITEST_FAILED = Regex("^\\s*(?:×|✗|✕) (.+?)(?: \\d+ms)?$")
    private val DOTNET_FAILED = Regex("^\\s*Failed (\\S+) \\[.*$")
    private val UNITTEST_FAILED = Regex("^(?:FAIL|ERROR): (\\w+) \\(([\\w.]+)\\)$")

    private val PYTEST_SUMMARY = Regex("^=+ (.*\\b(?:passed|failed|error|errors|skipped)\\b.*?)(?: in [\\d.]+s.*)? =+$")
    private val JEST_SUMMARY = Regex("^Tests:?\\s+(.*\\b(?:passed|failed)\\b.*)$")
    private val CARGO_SUMMARY = Regex("^test result: \\w+\\. (.*?)(?:; finished in .*)?$")
    private val GRADLE_SUMMARY = Regex("^(\\d+ tests? completed, \\d+ failed.*)$")
    private val MAVEN_SUMMARY = Regex("^(?:\\[\\w+\\] )?(Tests run: \\d+, Failures: \\d+, Errors: \\d+, Skipped: \\d+)$")
    private val DOTNET_SUMMARY = Regex("^(?:Passed|Failed)!\\s+-\\s+(.*)$")
    private val UNITTEST_SUMMARY = Regex("^Ran \\d+ tests? in [\\d.]+s$")
    private val ERROR_LINE = Regex("(^e: )|(\\berror\\b[\\w\\[\\] ]{0,20}:)|(^Error:)|(^ERROR\\b)|(^\\S+\\.\\w+:\\d+(:\\d+)?: )|(\\bFAILED\\b)|(^FAIL\\b)", RegexOption.IGNORE_CASE)
}
