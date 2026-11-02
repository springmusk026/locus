package dev.locus.core.linux

import dev.locus.core.agent.tools.SyntaxCheck
import dev.locus.core.agent.tools.Workspace
import java.io.File

/**
 * Checks a file the agent just wrote or edited with a fast syntax checker in Linux, when Linux is
 * installed and the checker is there, so a broken edit shows up in the edit's own result (SWE-agent's
 * linter guard, Aider's lint-after-edit). It never installs anything, and stays quiet when it can't
 * check: no Linux, no toolchain, an unknown kind of file, or too slow.
 *
 * Only checkers that parse without running the file's code are used, from the system's PATH (never a
 * project's node_modules/.bin), run from `/` and with Python in isolated mode, so a project's own
 * files can't change what runs.
 */
class LinuxSyntaxCheck(private val linux: LinuxEnvironment, private val ws: Workspace) : SyntaxCheck {
    override suspend fun problems(file: File, path: String): String? {
        if (linux.status.value != LinuxStatus.Ready) return null
        val firstLine = runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull()
        val check = syntaxCheckFor(path, firstLine, typeScriptProject = hasTsconfig(file)) ?: return null
        val result = linux.run(check.command, cwd = "/", timeoutMs = check.timeoutMs, maxChars = 8_000)
        if (result.timedOut || result.exitCode == 0 || result.exitCode == NO_CHECKER) return null
        return describeProblems(check.name, result.output)
    }

    /** A tsconfig.json in the file's folder or one above it, within the workspace. */
    private fun hasTsconfig(file: File): Boolean {
        val root = ws.resolve(".")
        return generateSequence(file.parentFile) { it.parentFile }
            .takeWhile { it.path.startsWith(root.path) }
            .any { File(it, "tsconfig.json").isFile }
    }
}

/** The exit code a check uses when its program isn't installed. */
internal const val NO_CHECKER = 99

internal class Check(val name: String, val command: String, val timeoutMs: Long = 15_000)

/**
 * The check for the file at [path] (relative to the workspace), or null for kinds of file with no fast
 * check. [firstLine] picks bash over sh for shell scripts; TypeScript is checked only in a project
 * with a tsconfig.json ([typeScriptProject]) and only for syntax errors, since a single file can't be
 * type-checked without its project.
 */
internal fun syntaxCheckFor(path: String, firstLine: String?, typeScriptProject: Boolean): Check? {
    val file = shellQuote("/workspace/" + path.trimStart('/'))
    fun need(program: String) = "command -v $program >/dev/null 2>&1 || exit $NO_CHECKER; "
    return when (path.substringAfterLast('.', "").lowercase()) {
        "py" -> Check(
            "python3",
            need("python3") + "python3 -I -c " + shellQuote(PYTHON_CHECK) + " $file",
        )
        "js", "mjs", "cjs" -> Check(
            "node --check",
            // JSX in a .js file isn't JavaScript to node; that's not the agent's mistake, so say nothing.
            need("node") + "out=\$(node --check $file 2>&1) && exit 0; echo \"\$out\" | grep -q \"Unexpected token '<'\" && exit 0; echo \"\$out\"; exit 1",
        )
        "ts", "tsx", "mts", "cts" -> if (!typeScriptProject) null else Check(
            "tsc, syntax only",
            need("tsc") + "out=\$(tsc --noEmit --pretty false --noResolve --noLib --skipLibCheck --allowJs --target esnext --module esnext --jsx preserve $file 2>&1); " +
                // TS1xxx are the grammar errors; everything else needs the whole project to judge.
                "errors=\$(echo \"\$out\" | grep -E 'error TS1[0-9]{3}:'); [ -z \"\$errors\" ] && exit 0; echo \"\$errors\"; exit 1",
            timeoutMs = 25_000,
        )
        "go" -> Check("gofmt -e", need("gofmt") + "gofmt -e -l $file >/dev/null")
        "sh", "bash" -> if (path.endsWith(".bash") || (firstLine != null && firstLine.startsWith("#!") && "bash" in firstLine)) {
            Check("bash -n", need("bash") + "bash -n $file")
        } else {
            Check("sh -n", "sh -n $file")
        }
        "rb" -> Check("ruby -c", need("ruby") + "ruby -c $file >/dev/null")
        // php -l reports parse errors on stdout; only a failure is shown.
        "php" -> Check("php -l", need("php") + "php -n -l $file")
        else -> null
    }
}

/** Prints file:line:column: message for a syntax error, without running the file. */
private val PYTHON_CHECK = """
import sys
p = sys.argv[1]
try:
    compile(open(p, "rb").read(), p, "exec", dont_inherit=True)
except SyntaxError as e:
    print("%s:%s:%s: %s" % (e.filename, e.lineno, e.offset, e.msg))
    if e.text:
        print("    " + e.text.rstrip())
    sys.exit(1)
""".trimIndent()

/** A failed check's output for the edit result: workspace-relative paths, at most a few lines. */
internal fun describeProblems(name: String, output: String): String? {
    val lines = output.replace("/workspace/", "").lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    if (lines.isEmpty()) return null
    val shown = lines.take(MAX_LINES).joinToString("\n") { it.take(300) }
    val more = if (lines.size > MAX_LINES) "\n…${lines.size - MAX_LINES} more lines" else ""
    return "Syntax check ($name) found problems; fix them before going on:\n$shown$more"
}

private const val MAX_LINES = 15

internal fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
