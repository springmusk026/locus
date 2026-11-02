package dev.locus.core.agent.tools

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.str
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Tools that orient the agent in a code project before it changes anything: what kind of project it is
 * and how to build and test it ([ProjectInfo]), and a compact map of its declarations ([CodeOutline],
 * a light version of Aider's repo map). Both read the workspace in-process, so they work without Linux.
 */
fun codeTools(ws: Workspace): List<Tool> = listOf(CodeOutline(ws), ProjectInfo(ws))

internal class CodeOutline(private val ws: Workspace) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "code_outline",
        "Outline source code: the classes, functions and other declarations in a file, or in every source file under a folder, each with its line number. " +
            "A cheap map of a project or a big file before reading it; then read_file the lines you need. Skips dependency and build folders.",
        ObjectSchema()
            .string("path", "File or folder relative to the workspace root. Defaults to the root.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Outline ${args.str("path") ?: "workspace"}"

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val start = ws.existing(args.str("path") ?: ".")
        val files = CodeFiles.walk(ws, start).filter { Outline.languageOf(it.name) != null && it.length() < MAX_FILE_BYTES }.sortedBy { it.path }.toList()
        if (files.isEmpty()) {
            return@withContext ToolResult(if (start.isFile) "No outline for '${start.name}': not a recognised source file. Use read_file." else "No source files found under '${ws.relative(start)}'.")
        }
        val out = StringBuilder()
        var shown = 0
        for (file in files) {
            val symbols = Outline.of(file.name, runCatching { file.readLines() }.getOrDefault(emptyList()))
            val block = buildString {
                append(ws.relative(file)).append(" (").append(symbols.lines).append(" lines)\n")
                symbols.entries.take(MAX_PER_FILE).forEach { (line, text) -> append("  ").append(line).append(": ").append(text).append('\n') }
                if (symbols.entries.size > MAX_PER_FILE) append("  …${symbols.entries.size - MAX_PER_FILE} more; outline this file alone\n")
            }
            if (out.length + block.length > MAX_CHARS && shown > 0) break
            out.append(block)
            shown++
        }
        val more = if (shown < files.size) "…${files.size - shown} more files not shown; outline a subfolder." else ""
        ToolResult(out.toString() + more)
    }

    private companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val MAX_PER_FILE = 150
        const val MAX_CHARS = 24_000
    }
}

/** Declarations found by per-language patterns: not a parser, but enough to navigate by. */
internal object Outline {
    data class Result(val lines: Int, val entries: List<Pair<Int, String>>)

    private val KOTLIN = Regex("^\\s{0,8}(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|private|internal|protected|open|abstract|sealed|data|enum|inline|value|annotation|inner|override|suspend|operator|infix|tailrec|external|const|lateinit|actual|expect|companion)\\s+)*(?:class|interface|object|fun|typealias|val|var)\\b")
    private val JAVA = Regex("^\\s{0,8}(?:(?:public|protected|private|static|final|abstract|sealed|synchronized|native|default)\\s+)*(?:class|interface|enum|record|@interface)\\s+\\w+|^\\s{0,8}(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+)+[\\w<>\\[\\],.? ]+\\s+\\w+\\s*\\(")
    private val PYTHON = Regex("^\\s{0,8}(?:async\\s+)?(?:def|class)\\s+\\w+")
    private val JS = Regex(
        "^(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:abstract\\s+)?(?:async\\s+)?(?:function\\*?|class|interface|type|enum|namespace)\\s+\\w+" +
            "|^(?:export\\s+)?(?:const|let|var)\\s+\\w+\\s*(?::[^=]+)?=\\s*(?:async\\s+)?(?:\\([^)]*\\)|\\w+)\\s*=>" +
            "|^(?:export\\s+)?(?:const|let|var)\\s+\\w+\\s*=\\s*(?:async\\s+)?function" +
            "|^\\s{2,4}(?:(?:public|private|protected|static|async|readonly|get|set)\\s+)*(?!if\\b|for\\b|while\\b|switch\\b|catch\\b|return\\b)[A-Za-z_$][\\w$]*\\s*\\([^)]*\\)\\s*(?::[^{]+)?\\{\\s*$",
    )
    private val GO = Regex("^(?:func|type)\\s+")
    private val RUST = Regex("^\\s{0,4}(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?(?:fn|struct|enum|trait|impl|mod|type|macro_rules!)\\b")
    private val C = Regex("^(?:struct|class|enum|union|namespace|typedef)\\b|^[A-Za-z_][\\w:<>,*& ]*[\\s*&]+\\*?[A-Za-z_][\\w:~]*\\s*\\([^;]*$")
    private val RUBY = Regex("^\\s{0,6}(?:def|class|module)\\s+")
    private val PHP = Regex("^\\s{0,4}(?:(?:abstract|final|public|private|protected|static)\\s+)*(?:function|class|interface|trait|enum)\\s+\\w+")
    private val SWIFT = Regex("^\\s{0,8}(?:@\\w+\\s+)*(?:(?:public|private|fileprivate|internal|open|static|final|override|mutating)\\s+)*(?:func|class|struct|enum|protocol|extension|actor)\\s+")
    private val DART = Regex("^\\s{0,2}(?:abstract\\s+)?(?:class|mixin|extension|enum)\\s+\\w+|^\\s{0,2}[\\w<>?,]+\\s+\\w+\\s*\\([^;]*\\)\\s*(?:async\\s*)?\\{")
    private val SHELL = Regex("^(?:function\\s+)?[A-Za-z_][\\w-]*\\s*\\(\\)\\s*\\{")
    private val MARKDOWN = Regex("^#{1,4}\\s+\\S")

    private val BY_EXTENSION: Map<String, Regex> = mapOf(
        "kt" to KOTLIN, "kts" to KOTLIN, "java" to JAVA, "py" to PYTHON,
        "js" to JS, "jsx" to JS, "mjs" to JS, "cjs" to JS, "ts" to JS, "tsx" to JS, "mts" to JS,
        "go" to GO, "rs" to RUST, "c" to C, "h" to C, "cc" to C, "cpp" to C, "hpp" to C, "cs" to JAVA,
        "rb" to RUBY, "php" to PHP, "swift" to SWIFT, "dart" to DART, "sh" to SHELL, "bash" to SHELL, "md" to MARKDOWN,
    )

    fun languageOf(name: String): Regex? = BY_EXTENSION[name.substringAfterLast('.', "").lowercase()]

    fun of(name: String, lines: List<String>): Result {
        val regex = languageOf(name) ?: return Result(lines.size, emptyList())
        val isKotlin = regex === KOTLIN
        var inFence = false
        val entries = lines.mapIndexedNotNull { i, line ->
            if (regex === MARKDOWN && line.trimStart().startsWith("```")) inFence = !inFence
            if (inFence || !regex.containsMatchIn(line)) return@mapIndexedNotNull null
            // Kotlin properties only at the top level or one level in, so locals inside functions don't flood the outline.
            if (isKotlin && Regex("^\\s{5,}(?:val|var)\\b").containsMatchIn(line)) return@mapIndexedNotNull null
            (i + 1) to line.trim().removeSuffix("{").trimEnd().take(160)
        }
        return Result(lines.size, entries)
    }
}

internal class ProjectInfo(private val ws: Workspace) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "project_info",
        "Describe a code project in the workspace: its languages, build system and package manager, scripts, how to install its toolchain in Locus's Alpine Linux, " +
            "and the commands to build, test and run it. Also names the project's own instruction files (AGENTS.md, CLAUDE.md, README) to read first. Use it before working on an unfamiliar project.",
        ObjectSchema()
            .string("path", "Project folder relative to the workspace root. Defaults to the root.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Look at project ${args.str("path") ?: "workspace"}"

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val dir = ws.existing(args.str("path") ?: ".")
        if (!dir.isDirectory) throw ToolException("'${ws.relative(dir)}' is a file; give the project's folder.")
        ToolResult(Projects.describe(ws, dir))
    }
}

/** How to recognise a project and work on it in Alpine Linux (musl, `apk`, no glibc). */
internal object Projects {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun describe(ws: Workspace, dir: File): String {
        val rel = ws.relative(dir)
        val cd = if (rel == ".") "" else "cd /workspace/$rel && "
        val out = StringBuilder()
        val found = mutableListOf<String>()
        fun has(name: String) = File(dir, name).exists()
        fun section(title: String, vararg lines: String) {
            found += title
            out.append("\n## ").append(title).append('\n')
            lines.filter { it.isNotBlank() }.forEach { out.append("- ").append(it).append('\n') }
        }

        val instructions = listOf("AGENTS.md", "CLAUDE.md", ".cursorrules", "CONTRIBUTING.md", "README.md", "README").filter(::has)
        if (instructions.isNotEmpty()) out.append("Read first: ").append(instructions.joinToString()).append(" (the project's own instructions and conventions).\n")

        if (has("package.json")) {
            val pkg = runCatching { json.parseToJsonElement(File(dir, "package.json").readText()) as JsonObject }.getOrNull()
            val scripts = (pkg?.get("scripts") as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { "$k: $it" } }.orEmpty()
            val pm = when {
                has("pnpm-lock.yaml") -> "pnpm"
                has("yarn.lock") -> "yarn"
                has("bun.lockb") || has("bun.lock") -> "bun"
                else -> "npm"
            }
            val run = if (pm == "npm") "npm run" else pm
            val install = when (pm) {
                "pnpm" -> "apk add nodejs npm && npm install -g pnpm"
                "yarn" -> "apk add nodejs yarn"
                "bun" -> "bun has no musl build for every phone; try npm instead (apk add nodejs npm)"
                else -> "apk add nodejs npm"
            }
            section(
                "Node.js ($pm)",
                "Toolchain: $install",
                "Dependencies: ${cd}$pm install" + if (has("node_modules")) " (node_modules exists)" else " (not installed yet)",
                if (scripts.isNotEmpty()) "Scripts (run with `$run <name>`):\n" + scripts.take(30).joinToString("\n") { "    $it" } else "No scripts in package.json.",
                if (scripts.any { it.startsWith("test:") }) "Test: ${cd}$run test" else "",
                if (scripts.any { it.startsWith("dev:") || it.startsWith("start:") }) "Dev server: start it with linux_service_start, not run_command, then open http://localhost:PORT with browser_open." else "",
                if (has("tsconfig.json")) "TypeScript: type-check with ${cd}npx tsc --noEmit" else "",
            )
        }
        if (has("pyproject.toml") || has("requirements.txt") || has("setup.py") || has("Pipfile")) {
            val pyproject = File(dir, "pyproject.toml").takeIf { it.isFile }?.readText().orEmpty()
            val tool = when {
                has("uv.lock") || "[tool.uv]" in pyproject -> "uv"
                has("poetry.lock") || "[tool.poetry]" in pyproject -> "poetry"
                else -> "pip"
            }
            val usesPytest = "pytest" in pyproject || File(dir, "requirements.txt").takeIf { it.isFile }?.readText().orEmpty().contains("pytest") ||
                has("pytest.ini") || has("conftest.py") || has("tests")
            section(
                "Python ($tool)",
                "Toolchain: apk add python3 py3-pip" + if (tool == "uv") " uv" else if (tool == "poetry") " poetry" else "",
                "Alpine's Python is externally managed: make a virtualenv first, ${cd}python3 -m venv .venv && . .venv/bin/activate, then " +
                    when { has("requirements.txt") -> "pip install -r requirements.txt"; tool == "uv" -> "uv sync"; tool == "poetry" -> "poetry install"; else -> "pip install -e ." },
                "Packages with C extensions may not have musl wheels; if a build fails, install the Alpine package (e.g. apk add py3-numpy) or apk add build-base python3-dev.",
                if (usesPytest) "Test: ${cd}. .venv/bin/activate && python -m pytest -q" else "Test: ${cd}python3 -m unittest discover",
            )
        }
        if (has("build.gradle.kts") || has("build.gradle") || has("settings.gradle.kts") || has("settings.gradle")) {
            val android = listOf("build.gradle.kts", "build.gradle", "app/build.gradle.kts", "app/build.gradle").any { File(dir, it).takeIf(File::isFile)?.readText()?.contains("com.android") == true }
            section(
                "Gradle" + if (android) " (Android)" else "",
                "Toolchain: apk add openjdk21-jdk" + if (!has("gradlew")) " gradle" else "",
                "Build: ${cd}${if (has("gradlew")) "./gradlew" else "gradle"} build    Test: ${cd}${if (has("gradlew")) "./gradlew" else "gradle"} test (gradle is slow on a phone: give run_command a long timeout_seconds)",
                if (android) "Android apps need the Android SDK, which isn't available in Locus's Linux; edit the code here, and build it on a computer or in CI." else "",
            )
        }
        if (has("pom.xml")) section("Maven", "Toolchain: apk add openjdk21-jdk maven", "Build: ${cd}mvn -q package    Test: ${cd}mvn -q test")
        if (has("Cargo.toml")) section("Rust (cargo)", "Toolchain: apk add cargo (or rust cargo)", "Build: ${cd}cargo build    Test: ${cd}cargo test    Check fast: ${cd}cargo check")
        if (has("go.mod")) section("Go", "Toolchain: apk add go", "Build: ${cd}go build ./...    Test: ${cd}go test ./...    Vet: ${cd}go vet ./...")
        if (has("CMakeLists.txt")) section("CMake", "Toolchain: apk add build-base cmake", "Build: ${cd}cmake -B build && cmake --build build    Test: ${cd}ctest --test-dir build")
        if (has("Makefile") || has("makefile")) {
            val targets = File(dir, if (has("Makefile")) "Makefile" else "makefile").readLines()
                .mapNotNull { Regex("^([A-Za-z0-9_.-]+)\\s*:(?!=)").find(it)?.groupValues?.get(1) }.filter { !it.startsWith(".") }.distinct()
            section("Make", "Toolchain: apk add make build-base", "Targets: ${targets.take(20).joinToString().ifEmpty { "(none found)" }}", "Run: ${cd}make <target>")
        }
        if (has("Gemfile")) section("Ruby (bundler)", "Toolchain: apk add ruby ruby-bundler ruby-dev build-base", "Dependencies: ${cd}bundle install", if (has("Rakefile")) "Tasks: ${cd}bundle exec rake -T" else "")
        if (has("composer.json")) section("PHP (composer)", "Toolchain: apk add php composer", "Dependencies: ${cd}composer install")
        if (has("deno.json") || has("deno.jsonc")) section("Deno", "Deno has no official musl build; it may not run in Locus's Linux.")
        if (has("pubspec.yaml")) section("Dart / Flutter", "The Flutter and Dart SDKs need glibc and don't run in Locus's Alpine Linux; edit the code here and build it elsewhere.")
        if (dir.listFiles().orEmpty().any { it.name.endsWith(".csproj") || it.name.endsWith(".sln") }) section(".NET", "Toolchain: apk add dotnet8-sdk (not available on every architecture)", "Build: ${cd}dotnet build    Test: ${cd}dotnet test")
        if (found.isEmpty() && (has("index.html"))) {
            section("Static website", "Preview: linux_service_start with `python3 -m http.server 8000` (after apk add python3) in this folder, then browser_open http://localhost:8000")
        }

        val git = generateSequence(dir) { it.parentFile }.takeWhile { it.path.startsWith(ws.resolve(".").path) }.firstOrNull { File(it, ".git").exists() }
        out.append("\n## Version control\n- ")
        out.append(if (git != null) "Git repository at ${ws.relative(git)}. See changes with git_inspect (status, diff, log). Commit only when the user asks." else "Not a git repository.")
        out.append('\n')

        val counts = CodeFiles.walk(ws, dir).take(20_000).groupingBy { it.name.substringAfterLast('.', "(none)").lowercase() }.eachCount()
        val top = counts.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key} ${it.value}" }
        val header = "Project ${if (rel == ".") "at the workspace root" else rel}: " +
            (if (found.isEmpty()) "no build system recognised." else found.joinToString(", ") + ".") +
            "\nFiles by extension (dependency and build folders skipped): ${top.ifEmpty { "none" }}\n"
        return header + out.toString().trimEnd() +
            "\n\nAfter changing code, run the project's test or build command with run_command and fix what fails before saying it's done."
    }
}
