package dev.locus.core.agent.tools

import java.io.File
import java.nio.file.Files

/**
 * Shared plumbing for the coding tools: walking a project without wandering into dependency and build
 * folders (or out of the workspace through a symlink), glob patterns, and telling text from binary.
 */
internal object CodeFiles {
    /**
     * Folders that are generated, vendored or tool state: searching them buries real matches (a
     * node_modules can hold 100k files). Skipped below the starting folder unless asked for.
     */
    val IGNORED_DIRS = setOf(
        ".git", ".hg", ".svn", "node_modules", "build", "dist", "out", "target", ".gradle", ".idea", ".vscode",
        "__pycache__", ".venv", "venv", ".tox", ".mypy_cache", ".pytest_cache", ".ruff_cache", ".next", ".nuxt",
        ".svelte-kit", ".cache", ".parcel-cache", ".turbo", "coverage", ".dart_tool", ".expo", "Pods", "DerivedData",
        // Locus's own state in the workspace, such as saved command output.
        ".locus",
    )

    /**
     * Every file below [start] (or [start] itself when it is a file), skipping [IGNORED_DIRS] and
     * `.gitignore`d paths unless [includeIgnored]. Never follows a symlink: one could point outside the
     * workspace, and the workspace is all the agent may read.
     */
    fun walk(ws: Workspace, start: File, includeIgnored: Boolean = false): Sequence<File> {
        if (start.isFile) return sequenceOf(start)
        val ignore = if (includeIgnored) null else GitIgnore.load(ws.resolve("."), start)
        return start.walkTopDown()
            .onEnter { dir -> dir == start || (!isLink(dir) && (ignore == null || (dir.name !in IGNORED_DIRS && !ignore.ignores(ws.relative(dir), true)))) }
            .filter { it.isFile && !isLink(it) && (ignore == null || !ignore.ignores(ws.relative(it), false)) }
    }

    private fun isLink(file: File) = Files.isSymbolicLink(file.toPath())

    /** True when the first bytes hold no NUL, the usual test for a text file. */
    fun isText(file: File): Boolean = runCatching { file.readHead(4096).none { it == 0.toByte() } }.getOrDefault(false)

    /**
     * A glob as a regex over a `/`-separated relative path: `*` and `?` stay within one folder, `**`
     * crosses folders (`**` followed by `/` also matches no folder at all), `{a,b}` is either, and
     * `[abc]` a class. A pattern without a `/` matches the file name at any depth, as in ripgrep and
     * .gitignore.
     */
    fun globRegex(glob: String, matchName: Boolean = true): Regex {
        val pattern = glob.trim().removePrefix("./").let { if (!matchName || '/' in it.trimEnd('/')) it.removePrefix("/") else "**/$it" }
        val sb = StringBuilder("^")
        var i = 0
        var braces = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && pattern.getOrNull(i + 1) == '*' -> {
                    if (pattern.getOrNull(i + 2) == '/') {
                        sb.append("(?:.*/)?"); i += 2
                    } else {
                        sb.append(".*"); i += 1
                    }
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c == '{' -> { sb.append("(?:"); braces++ }
                c == '}' && braces > 0 -> { sb.append(")"); braces-- }
                c == ',' && braces > 0 -> sb.append("|")
                c == '[' -> {
                    val end = pattern.indexOf(']', i + 1)
                    if (end < 0) sb.append("\\[") else {
                        val body = pattern.substring(i + 1, end).let { if (it.startsWith("!")) "^" + it.drop(1) else it }
                        sb.append('[').append(body.replace("\\", "\\\\")).append(']')
                        i = end
                    }
                }
                c.isLetterOrDigit() || c == '/' -> sb.append(c)
                else -> sb.append('\\').append(c)
            }
            i++
        }
        repeat(braces) { sb.append(")") }
        sb.append("$")
        return Regex(sb.toString())
    }

    /** First [max] bytes of the file. (InputStream.readNBytes needs API 33; minSdk is 26.) */
    fun File.readHead(max: Int): ByteArray = inputStream().use { input ->
        val buffer = ByteArray(max)
        var read = 0
        while (read < max) {
            val n = input.read(buffer, read, max - read)
            if (n < 0) break
            read += n
        }
        buffer.copyOf(read)
    }
}

/**
 * The parts of `.gitignore` worth honouring without git: the workspace root's file and the one in the
 * folder being searched (and its parents up to the root), plain and glob patterns, folder-only
 * patterns (`dir/`) and anchored ones (`/dir`). Negations (`!keep`) un-ignore. Nested `.gitignore`
 * files further down aren't read.
 */
internal class GitIgnore private constructor(private val rules: List<Rule>) {
    private class Rule(val regex: Regex, val dirOnly: Boolean, val negate: Boolean)

    /** Whether [relative] (to the workspace root) is ignored. */
    fun ignores(relative: String, isDir: Boolean): Boolean {
        val path = relative.replace(File.separatorChar, '/')
        var ignored = false
        for (rule in rules) {
            if ((rule.regex.matches(path) && (isDir || !rule.dirOnly)) || parentsMatch(rule, path)) ignored = !rule.negate
        }
        return ignored
    }

    private fun parentsMatch(rule: Rule, path: String): Boolean {
        var p = path.substringBeforeLast('/', "")
        while (p.isNotEmpty()) {
            if (rule.regex.matches(p)) return true
            p = p.substringBeforeLast('/', "")
        }
        return false
    }

    companion object {
        fun load(root: File, start: File): GitIgnore {
            val dirs = generateSequence(start.canonicalFile) { it.parentFile }.takeWhile { it.path.startsWith(root.path) }.toList().reversed()
            val rules = dirs.flatMap { dir ->
                val prefix = dir.relativeTo(root).path.replace(File.separatorChar, '/').let { if (it.isEmpty()) "" else "$it/" }
                File(dir, ".gitignore").takeIf { it.isFile && it.length() < 256 * 1024 }?.readLines().orEmpty().mapNotNull { parse(it, prefix) }
            }
            return GitIgnore(rules)
        }

        private fun parse(line: String, prefix: String = ""): Rule? {
            var l = line.trimEnd()
            if (l.isBlank() || l.startsWith("#")) return null
            val negate = l.startsWith("!")
            if (negate) l = l.drop(1)
            val dirOnly = l.endsWith("/")
            l = l.trimEnd('/')
            if (l.isEmpty()) return null
            val anchored = '/' in l
            val glob = prefix + if (anchored) l.removePrefix("/") else "**/$l"
            return runCatching { Rule(CodeFiles.globRegex(glob, matchName = false), dirOnly, negate) }.getOrNull()
        }
    }
}
