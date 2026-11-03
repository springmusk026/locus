package dev.locus.core.model

/**
 * Build and test runs in `run_command`, recognised so the chat can show a verification card (pass or
 * fail, duration, failing tests or the first errors) instead of only the raw output. Pure text work:
 * the command the agent ran and the output it got back. See docs/design/change-tracking.md.
 */
object Verification {
    enum class Kind { TEST, BUILD, CHECK }

    /**
     * What a card may claim about a run. [UNCERTAIN] is a run whose exit status can't be read as the
     * check's own (a pipe, a `;` chain, `|| true`) or whose output contradicts a zero exit (failing tests
     * listed): it "finished", and the card shows what the output says instead of "passed".
     */
    enum class Verdict { PASSED, FAILED, TIMED_OUT, UNCERTAIN }

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
        /** The SSH machine it ran on, for a project linked to one (docs/design/remote-execution.md); null on the phone. */
        val host: String? = null,
        /**
         * Whether [exitCode] is the check's own status. False when the command pipes the check into
         * another program (`npm test | tail`: the exit is tail's), runs something after it with `;`, or
         * can hide a failure with `||`. See [exitTrusted].
         */
        val exitTrusted: Boolean = true,
    ) {
        /** What the card may say: never "passed" when the exit status can't be trusted or the output lists failures. */
        val verdict: Verdict
            get() = when (passed) {
                null -> Verdict.TIMED_OUT
                false -> Verdict.FAILED
                true -> if (!exitTrusted || failureCount > 0) Verdict.UNCERTAIN else Verdict.PASSED
            }
    }

    const val MAX_ITEMS = 8

    /** The command [line] runs, when it's a build, test or check; null otherwise. Looks at each `&&`/`;`/`||` step and takes the last match. */
    fun detect(line: String): Command? =
        line.split("&&", "||", ";", "\n").map { normalize(it) }.mapNotNull(::detectStep).lastOrNull()

    /**
     * A recognised command with what it runs on: its words after the program that aren't flags (subcommands,
     * Gradle tasks, test paths and filters), in order. See [covers].
     */
    data class Scope(val command: Command, val targets: List<String>)

    /** [line]'s [Scope], from the same step [detect] picks; null when it isn't a build, test or check. */
    fun scope(line: String): Scope? =
        line.split("&&", "||", ";", "\n").map { normalize(it) }.mapNotNull { step ->
            detectStep(step)?.let { c ->
                val words = step.split(WHITESPACE).filter { it.isNotEmpty() }
                // `python -m pytest` runs what `pytest` does.
                val args = if (words.getOrNull(1) == "-m") words.drop(3) else words.drop(1)
                Scope(c, args.filterNot { it.startsWith("-") })
            }
        }.lastOrNull()

    /**
     * Whether a passing run [later] re-checks what a failed run [earlier] checked: the same runner and kind,
     * and at least as wide. A later run is as wide when it adds no selector of its own and every selector of
     * the earlier one is still there or widened: a path to its folder (`tests/a.py::t` to `tests/a.py`) or a
     * Gradle task to every project's task (`:app:test` to `test`), a Go package to `./...`, or no selector at
     * all for a runner that then runs everything (`pytest`). Selectors are only ever kept or widened,
     * so dropping one (`cargo test parse` to `cargo test`) doesn't count: it's conservative, and an unrelated
     * or narrower run (`pytest tests/b.py` after `pytest tests/a.py`) never clears a failure.
     */
    fun covers(later: Scope, earlier: Scope): Boolean {
        if (later.command != earlier.command) return false
        // A runner given nothing to select (`pytest`, `jest`) runs everything.
        if (later.targets.isEmpty()) return true
        fun widens(p: String, f: String) = p == f ||
            (f.startsWith(p) && f.getOrNull(p.length) in PATH_BOUNDARY) ||
            (p.endsWith("/...") && f.startsWith(p.removeSuffix("..."))) ||
            (':' !in p && f.endsWith(":$p"))
        return later.targets.all { p -> earlier.targets.any { f -> widens(p, f) } } &&
            earlier.targets.all { f -> later.targets.any { p -> widens(p, f) } }
    }

    private val PATH_BOUNDARY = setOf('/', ':', '.')

    /**
     * Whether [line]'s exit status is the recognised check's own. Without `set -o pipefail` (run_command
     * doesn't set it) a pipeline exits with its last program's status, so `npm test 2>&1 | tail -50`
     * exits 0 even when tests fail. A step after the check joined by `;` or a newline sets the status
     * instead of it, and `||` anywhere can turn a failure into success (`npm test || true`). Steps joined
     * by `&&` keep it: the chain stops at the first failure. Quoted text is ignored.
     */
    fun exitTrusted(line: String): Boolean {
        val bare = line.replace(QUOTED, "''")
        if ("||" in bare) return false
        val pipefail = PIPEFAIL.containsMatchIn(bare)
        // Each step with the separator after it ("" for the last).
        val steps = mutableListOf<Pair<String, String>>()
        var rest = bare
        while (true) {
            val m = SEPARATOR.find(rest) ?: break
            steps += rest.substring(0, m.range.first) to m.value
            rest = rest.substring(m.range.last + 1)
        }
        steps += rest to ""
        // Every build, test or check step must decide the status: one whose failure is hidden (an earlier
        // build piped into tail, then `; npm run lint`) makes the whole run's status unreliable.
        return steps.indices.filter { detectStep(normalize(steps[it].first)) != null }.all { check ->
            if (!pipefail && PIPE.containsMatchIn(steps[check].first)) return@all false
            // From the check on, only `&&` may join steps, unless nothing follows it (a trailing `;`).
            steps.drop(check).dropLast(1).all { it.second == "&&" } || steps.drop(check + 1).all { it.first.isBlank() }
        }
    }

    private val QUOTED = Regex("'[^']*'|\"(?:[^\"\\\\]|\\\\.)*\"")
    private val SEPARATOR = Regex("&&|;|\n")
    private val PIPE = Regex("(?<![|>&])\\|(?!\\|)")
    private val PIPEFAIL = Regex("\\bset\\s+(-[a-zA-Z]*\\s+)*-[a-zA-Z]*o\\s+pipefail\\b")

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
        val rest = result.lines().drop(1)
        val host = rest.firstOrNull { it.startsWith(REMOTE_NOTE) }?.removePrefix(REMOTE_NOTE)?.substringBefore(" in ")?.trim()?.ifEmpty { null }
        // The safety envelope run_command puts around output is for the model; it isn't output.
        val output = Envelopes.stripLines(rest).filterNot { it.startsWith(SAVED_NOTE) || it.startsWith(REMOTE_NOTE) }
        val passed = when {
            timedOut -> null
            exit != null -> exit == 0
            else -> !isError
        }
        val failures = failingTests(output).distinct()
        // A zero exit can hide a failure (a pipe, `;`): look for errors then too, so the card can show them.
        val errors = if (failures.isEmpty()) errorLines(output, lastLines = passed != true) else emptyList()
        return Outcome(command, passed, exit, duration, summary(output), failures.take(MAX_ITEMS), failures.size, errors, host)
    }

    /**
     * The verification a finished `run_command` call was, from its [argumentsJson] and result: null for
     * other commands and for calls that didn't run (refused, invalid, still running).
     */
    fun ofRunCommand(argumentsJson: String, result: String, isError: Boolean): Outcome? {
        // Only a command that ran has a status line; a refused or invalid call doesn't.
        if (!result.startsWith("Exit code") && !result.startsWith("Stopped after")) return null
        val command = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson) as kotlinx.serialization.json.JsonObject)["command"]
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull() ?: return null
        val kind = detect(command) ?: return null
        return parse(kind, result, isError).copy(exitTrusted = exitTrusted(command))
    }

    /** "Tests passed", "Build failed", "Check timed out". */
    fun title(outcome: Outcome): String {
        val what = when (outcome.command.kind) {
            Kind.TEST -> "Tests"
            Kind.BUILD -> "Build"
            Kind.CHECK -> "Check"
        }
        return when (outcome.verdict) {
            Verdict.PASSED -> "$what passed"
            Verdict.FAILED -> "$what failed"
            Verdict.TIMED_OUT -> "$what timed out"
            Verdict.UNCERTAIN -> "$what finished"
        }
    }

    private fun failingTests(lines: List<String>): List<String> = lines.mapNotNull { raw ->
        val line = ANSI.replace(raw, "").trimEnd()
        GRADLE_FAILED.matchEntire(line)?.let { return@mapNotNull "${it.groupValues[1]} > ${it.groupValues[2]}" }
        PYTEST_FAILED.matchEntire(line)?.let { return@mapNotNull it.groupValues[1] }
        CARGO_FAILED.matchEntire(line)?.let { return@mapNotNull it.groupValues[1] }
        GO_FAILED.matchEntire(line)?.let { return@mapNotNull it.groupValues[1] }
        JEST_FAILED.matchEntire(line)?.let { m -> return@mapNotNull m.groupValues[1].takeUnless { it.startsWith("Console") } }
        VITEST_FAILED.matchEntire(line)?.let { m -> return@mapNotNull m.groupValues[1].takeUnless { BUNDLER_FAILED.matches(it) } }
        DOTNET_FAILED.matchEntire(line)?.let { return@mapNotNull it.groupValues[1] }
        UNITTEST_FAILED.matchEntire(line)?.let { return@mapNotNull "${it.groupValues[2]}.${it.groupValues[1]}" }
        null
    }

    /** The first lines that look like errors; failing that, the last lines of output. */
    private fun errorLines(lines: List<String>, lastLines: Boolean = true): List<String> {
        val clean = lines.map { ANSI.replace(it, "").trimEnd() }.filter { it.isNotBlank() }
        val wrong = clean.indexOfFirst { it.trim() == "* What went wrong:" }
        if (wrong >= 0) return clean.drop(wrong + 1).takeWhile { !it.startsWith("* ") }.take(MAX_ITEMS).map { it.trim().take(MAX_LINE) }
        val errors = clean.filter { ERROR_LINE.containsMatchIn(it) && !it.trimStart().startsWith("(") }
        return (errors.ifEmpty { if (lastLines) clean.takeLast(MAX_ITEMS) else emptyList() }).take(MAX_ITEMS).map { it.trim().take(MAX_LINE) }
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

    /** The line run_command adds when a project's commands run on an SSH machine: "[Ran on build-server in /home/me/locus/app. …]". */
    const val REMOTE_NOTE = "[Ran on "

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

    /** Vite, esbuild and Rollup print "✗ Build failed in 1.2s": an error, not a failing test of that name. */
    private val BUNDLER_FAILED = Regex("^Build failed\\b.*", RegexOption.IGNORE_CASE)
    private val UNITTEST_FAILED = Regex("^(?:FAIL|ERROR): (\\w+) \\(([\\w.]+)\\)$")

    private val PYTEST_SUMMARY = Regex("^=+ (.*\\b(?:passed|failed|error|errors|skipped)\\b.*?)(?: in [\\d.]+s.*)? =+$")
    private val JEST_SUMMARY = Regex("^Tests:?\\s+(.*\\b(?:passed|failed)\\b.*)$")
    private val CARGO_SUMMARY = Regex("^test result: \\w+\\. (.*?)(?:; finished in .*)?$")
    private val GRADLE_SUMMARY = Regex("^(\\d+ tests? completed, \\d+ failed.*)$")
    private val MAVEN_SUMMARY = Regex("^(?:\\[\\w+\\] )?(Tests run: \\d+, Failures: \\d+, Errors: \\d+, Skipped: \\d+)$")
    private val DOTNET_SUMMARY = Regex("^(?:Passed|Failed)!\\s+-\\s+(.*)$")
    private val UNITTEST_SUMMARY = Regex("^Ran \\d+ tests? in [\\d.]+s$")
    private val ERROR_LINE = Regex("(^e: )|(\\berror\\b[\\w\\[\\] ]{0,20}:)|(^Error:)|(^ERROR\\b)|(^\\S+\\.\\w+:\\d+(:\\d+)?: )|(\\bFAILED\\b)|(^FAIL\\b)|(\\bBuild failed\\b)", RegexOption.IGNORE_CASE)
}
