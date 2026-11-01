package dev.locus.core.agent.tools

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * A quick check of a source file the agent just changed (a syntax check or linter), whose problems are
 * added to the edit's result, as SWE-agent, Aider and opencode do. It lives in core/linux, which
 * depends on this module, so it is passed in, like the Linux tools are added in ToolCatalog.
 */
fun interface SyntaxCheck {
    /**
     * Problems found in [file] ([path] relative to the workspace), briefly and ready to show the
     * model, or null when there were none or no check could run (no Linux, no toolchain, an unknown
     * kind of file, too slow). Never changes the file.
     */
    suspend fun problems(file: File, path: String): String?
}

/**
 * File tools over the agent's [Workspace]. All paths are relative to the workspace root. [syntaxCheck]
 * runs after each write or edit when given; [reads] remembers what each chat has read, so edits to
 * existing files need a read first.
 */
fun workspaceTools(ws: Workspace, syntaxCheck: SyntaxCheck? = null, reads: FileReads = FileReads()): List<Tool> = listOf(
    ListFiles(ws), ReadFile(ws, reads), SearchFiles(ws), FindFiles(ws), WriteFile(ws, reads, syntaxCheck), EditFile(ws, reads, syntaxCheck), MultiEdit(ws, reads, syntaxCheck),
    MovePath(ws), CopyPath(ws), DeletePath(ws), MakeDirectory(ws),
) + codeTools(ws)

private abstract class WorkspaceTool(protected val ws: Workspace) : Tool {
    protected fun arg(args: JsonObject, key: String) = args.str(key) ?: throw ToolException("Missing '$key'.")

    override suspend fun execute(args: JsonObject): ToolResult {
        val chat = currentCoroutineContext()[ToolCallContext]?.conversationId
        return withContext(Dispatchers.IO) { run(args, chat) }
    }

    /** [chat] is the conversation (or helper) making the call, or null outside a chat (tests, tools calling tools). */
    protected abstract fun run(args: JsonObject, chat: String?): ToolResult
}

/**
 * A tool that writes an existing or new file: it needs the chat to have read an existing file first
 * (refused before the user is asked, through [precheck]), and runs the [check] afterwards.
 */
private abstract class ChangingTool(ws: Workspace, protected val reads: FileReads, private val check: SyntaxCheck?) : WorkspaceTool(ws) {
    /** Whether a file changed since the chat read it may still be changed, given an exact match (edits); a whole-file write may not. */
    protected abstract val staleAllowed: Boolean

    override suspend fun precheck(args: JsonObject): String? {
        val chat = currentCoroutineContext()[ToolCallContext]?.conversationId ?: return null
        val file = runCatching { ws.resolve(args.str("path").orEmpty()) }.getOrNull()?.takeIf { it.isFile } ?: return null
        // Anything unexpected here is left for execute to report.
        return withContext(Dispatchers.IO) { runCatching { readProblem(chat, file) }.getOrNull() }
    }

    /** Why [chat] may not change [file] yet, or null. */
    protected fun readProblem(chat: String, file: File): String? = when (reads.state(chat, file)) {
        FileReads.State.NOT_READ ->
            "This chat hasn't read ${ws.relative(file)} yet (or Locus restarted since it did). Read it with read_file first, then make the change with text copied from what you read."
        FileReads.State.CHANGED -> if (staleAllowed) null else
            "${ws.relative(file)} has changed on disk since this chat read it (a command, another tool or the user changed it). Read it again with read_file before replacing it, so those changes aren't lost."
        else -> null
    }

    /**
     * Applies [edits] to [file] in order, all or nothing, for [chat]. The chat must have read the file;
     * if it changed on disk since then, only exact matches are trusted (as Claude Code does) and the
     * result says so. Returns the result's text, or throws with what to fix.
     */
    protected fun applyEdits(file: File, chat: String?, edits: List<Replacement>, multi: Boolean): String {
        val state = chat?.let { reads.state(it, file) }
        if (chat != null && state == FileReads.State.NOT_READ) throw ToolException(readProblem(chat, file).orEmpty())
        val stale = state == FileReads.State.CHANGED
        val original = file.readText()
        var content = original
        val notes = mutableListOf<String>()
        var replacements = 0
        edits.forEachIndexed { i, edit ->
            try {
                val applied = TextEdits.apply(content, edit.oldText, edit.newText, edit.replaceAll, lenient = !stale)
                content = applied.content
                replacements += applied.replacements
                applied.note?.let { notes += if (multi) "Edit ${i + 1}: $it" else it }
            } catch (e: ToolException) {
                val prefix = if (multi) "Edit ${i + 1} of ${edits.size} failed, so no changes were made. " else ""
                val why = if (stale) "${ws.relative(file)} has changed on disk since this chat read it, so only an exact match is used. " else ""
                throw ToolException(prefix + why + e.message + if (stale) " Read the file again with read_file." else "")
            }
        }
        ws.backup(file, TrashEntry.Reason.OVERWRITTEN)
        file.writeText(content)
        if (chat != null) reads.record(chat, file)
        val what = when {
            multi -> " (${edits.size} ${if (edits.size == 1) "change" else "changes"})"
            replacements > 1 -> " ($replacements replacements)"
            else -> ""
        }
        if (stale) notes += "The file had other changes since this chat read it; read it again before edits that depend on the lines around this one."
        return "Edited ${ws.relative(file)}$what." + notes.joinToString("") { "\n$it" } + (if (notes.isEmpty()) " " else "\n") +
            "It now reads:\n${changedLines(original, content)}"
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val result = super.execute(args)
        if (result.isError || check == null) return result
        val file = runCatching { ws.resolve(args.str("path").orEmpty()) }.getOrNull()?.takeIf { it.isFile } ?: return result
        // Optional and time-boxed: a slow, missing or failing check never fails the edit itself.
        val problems = try {
            withTimeoutOrNull(SYNTAX_CHECK_MS) { check.problems(file, ws.relative(file)) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (problems.isNullOrBlank()) result else result.copy(content = result.content + "\n\n" + problems.trim())
    }

    companion object {
        const val SYNTAX_CHECK_MS = 25_000L
    }
}

/** One replacement for [ChangingTool.applyEdits]. */
private class Replacement(val oldText: String, val newText: String, val replaceAll: Boolean)

private class ListFiles(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "list_files",
        "List files and folders in the workspace. Directories end with '/'. Sizes are in bytes.",
        ObjectSchema()
            .string("path", "Folder to list, relative to the workspace root. Defaults to the root.", required = false)
            .boolean("recursive", "Include everything below the folder. Defaults to false.")
            .build(),
    )

    override fun describe(args: JsonObject) = "List ${args.str("path") ?: "workspace"}"

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val dir = ws.existing(args.str("path") ?: ".")
        if (!dir.isDirectory) throw ToolException("'${ws.relative(dir)}' is a file, not a folder.")
        val files = if (args.bool("recursive") == true) dir.walkTopDown().drop(1) else dir.listFiles().orEmpty().asSequence()
        val lines = files.sortedBy { it.path }.take(MAX_ENTRIES + 1).map { f ->
            if (f.isDirectory) "${ws.relative(f)}/" else "${ws.relative(f)}  (${f.length()})"
        }.toList()
        if (lines.isEmpty()) return ToolResult("(empty)")
        val more = if (lines.size > MAX_ENTRIES) "\n…more entries not shown; list a subfolder." else ""
        return ToolResult(lines.take(MAX_ENTRIES).joinToString("\n") + more)
    }

    companion object {
        const val MAX_ENTRIES = 500
    }
}

private class ReadFile(ws: Workspace, private val reads: FileReads) : WorkspaceTool(ws) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "read_file",
        "Read a text file from the workspace. Each line is prefixed with its 1-based number and a tab; the prefix is not part of the file, so leave it out of edit_file's old_text. " +
            "Returns up to 2000 lines; for long files read the part you need with start_line and max_lines. Lines over 2000 characters are cut.",
        ObjectSchema()
            .string("path", "File path relative to the workspace root.")
            .integer("start_line", "First line to return, 1-based. Defaults to 1.")
            .integer("max_lines", "How many lines to return. Defaults to 2000.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Read ${args.str("path")}"

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val file = ws.existing(arg(args, "path"))
        if (file.isDirectory) throw ToolException("'${ws.relative(file)}' is a folder; use list_files or find_files.")
        if (!CodeFiles.isText(file)) {
            // Seeing that it's binary is all there is to read; write_file may then replace it.
            if (chat != null) reads.record(chat, file)
            return ToolResult("'${ws.relative(file)}' is a binary file (${file.length()} bytes).")
        }
        val start = (args.int("start_line") ?: 1).coerceAtLeast(1)
        val max = (args.int("max_lines") ?: 2000).coerceIn(1, 5000)
        val out = StringBuilder()
        var total = 0
        var last = 0
        var capped = false
        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { i, line ->
                total = i + 1
                if (total >= start && total < start + max && !capped) {
                    val text = if (line.length > TextEdits.MAX_LINE) line.take(TextEdits.MAX_LINE) + "… [line cut: ${line.length} characters]" else line
                    if (last > 0 && out.length + text.length > MAX_CHARS) {
                        capped = true
                    } else {
                        out.append(total).append('\t').append(text).append('\n')
                        last = total
                    }
                }
            }
        }
        if (total == 0) {
            if (chat != null) reads.record(chat, file)
            return ToolResult("(empty file)")
        }
        if (start > total) throw ToolException("'${ws.relative(file)}' has $total lines; start_line $start is past the end.")
        if (chat != null) reads.record(chat, file)
        val note = if (last < total) "\n[lines $start–$last of $total${if (capped) ", output limit reached" else ""}; continue with start_line=${last + 1}]" else ""
        return ToolResult(out.toString() + note)
    }

    companion object {
        const val MAX_CHARS = 100_000
    }
}

private class FindFiles(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "find_files",
        "Find files by name with a glob pattern, e.g. '**/*.kt', 'src/**/*.test.ts', '*.{yml,yaml}', 'README*'. A pattern without '/' matches file names at any depth; " +
            "one with '/' matches the path from the search folder. Skips dependency and build folders (node_modules, build, .git, …) and .gitignore'd paths unless include_ignored is true. " +
            "Returns paths relative to the workspace root, most recently changed first.",
        ObjectSchema()
            .string("pattern", "Glob pattern.")
            .string("path", "Folder to search, relative to the workspace root. Defaults to the root.", required = false)
            .boolean("include_ignored", "Also look in dependency, build and .gitignore'd folders. Defaults to false.")
            .integer("max_results", "At most this many paths. Defaults to 200, max 1000.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Find ${args.str("pattern")}"

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val pattern = arg(args, "pattern")
        val regex = runCatching { CodeFiles.globRegex(pattern) }.getOrElse { throw ToolException("Invalid glob '$pattern': ${it.message}") }
        val dir = ws.existing(args.str("path") ?: ".")
        if (!dir.isDirectory) throw ToolException("'${ws.relative(dir)}' is a file, not a folder.")
        val max = (args.int("max_results") ?: 200).coerceIn(1, 1000)
        val found = CodeFiles.walk(ws, dir, args.bool("include_ignored") == true)
            .filter { regex.matches(it.relativeTo(dir).path.replace(File.separatorChar, '/')) }
            .toList()
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.path })
        if (found.isEmpty()) return ToolResult("No files match '$pattern'.")
        val more = if (found.size > max) "\n…${found.size - max} more; narrow the pattern or the folder." else ""
        return ToolResult(found.take(max).joinToString("\n") { ws.relative(it) } + more)
    }
}

private class SearchFiles(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "search_files",
        "Search file contents with a regular expression, like grep or ripgrep. Skips binary files, dependency and build folders (node_modules, build, .git, …) and .gitignore'd paths unless include_ignored is true. " +
            "output=content (default) gives path:line: text for each match, with context lines as path-line- text and groups split by --; " +
            "output=files gives only the paths (most recently changed first); output=count gives matches per file.",
        ObjectSchema()
            .string("pattern", "Regular expression (Java syntax). Escape special characters such as ( [ . for literal text, or set literal to true.")
            .string("path", "File or folder to search, relative to the workspace root. Defaults to the root.", required = false)
            .string("glob", "Only search files matching this glob, e.g. '*.kt', '**/*.{ts,tsx}', 'src/**/test_*.py'.", required = false)
            .string("file_suffix", "Only search files whose name ends with this, e.g. '.md'.", required = false)
            .string("output", "content, files or count. Defaults to content.", required = false)
            .integer("context", "Lines to show before and after each match, 0 to 10. Defaults to 0.")
            .boolean("ignore_case", "Match regardless of case. Defaults to false.")
            .boolean("literal", "Treat pattern as plain text rather than a regular expression. Defaults to false.")
            .boolean("include_ignored", "Also search dependency, build and .gitignore'd folders. Defaults to false.")
            .integer("max_results", "Stop after this many matching lines (or files for files and count). Defaults to 200, max 1000.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Search for \"${args.str("pattern")}\""

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val raw = arg(args, "pattern")
        val options = if (args.bool("ignore_case") == true) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = runCatching { Regex(if (args.bool("literal") == true) Regex.escape(raw) else raw, options) }
            .getOrElse { throw ToolException("Invalid pattern: ${it.message?.lineSequence()?.firstOrNull()}. Escape special characters, or set literal to true.") }
        val glob = args.str("glob")?.let { g -> runCatching { CodeFiles.globRegex(g) }.getOrElse { throw ToolException("Invalid glob '$g'.") } }
        val suffix = args.str("file_suffix")
        val mode = args.str("output")?.lowercase() ?: "content"
        if (mode !in setOf("content", "files", "count")) throw ToolException("output must be content, files or count.")
        val context = (args.int("context") ?: 0).coerceIn(0, 10)
        val max = (args.int("max_results") ?: MAX_MATCHES).coerceIn(1, 1000)
        val start = ws.existing(args.str("path") ?: ".")
        val files = CodeFiles.walk(ws, start, args.bool("include_ignored") == true)
            .filter { f ->
                (suffix == null || f.name.endsWith(suffix)) && f.length() < MAX_FILE_BYTES &&
                    (glob == null || glob.matches((if (start.isFile) f.name else f.relativeTo(start).path).replace(File.separatorChar, '/')))
            }
            .sortedBy { it.path }
            .filter { CodeFiles.isText(it) }

        val out = StringBuilder()
        var hits = 0
        var stopped = false
        val counts = mutableListOf<Pair<File, Int>>()
        for (file in files) {
            if (stopped) break
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            val matched = lines.indices.filter { regex.containsMatchIn(lines[it]) }
            if (matched.isEmpty()) continue
            if (mode != "content") {
                counts += file to matched.size
                if (counts.size >= max) stopped = true
                continue
            }
            val path = ws.relative(file)
            val matchSet = matched.toSet()
            var printedTo = -1
            for (m in matched) {
                if (hits >= max || out.length > MAX_CHARS) {
                    stopped = true
                    break
                }
                if (m <= printedTo) {
                    hits++
                    continue
                }
                val from = maxOf(m - context, printedTo + 1)
                if (context > 0 && out.isNotEmpty() && (printedTo < 0 || from > printedTo + 1)) out.append("--\n")
                for (i in from..minOf(m + context, lines.lastIndex)) {
                    val sep = if (i in matchSet) ':' else '-'
                    out.append(path).append(sep).append(i + 1).append(sep).append(' ').append(lines[i].take(300)).append('\n')
                    printedTo = i
                }
                hits++
            }
        }
        val more = if (stopped) "\n…stopped at $max ${if (mode == "content") "matches" else "files"}; narrow the search." else ""
        return when (mode) {
            "files" -> if (counts.isEmpty()) ToolResult("No matches.")
            else ToolResult(counts.sortedWith(compareByDescending<Pair<File, Int>> { it.first.lastModified() }.thenBy { it.first.path }).joinToString("\n") { ws.relative(it.first) } + more)
            "count" -> if (counts.isEmpty()) ToolResult("No matches.")
            else ToolResult(counts.joinToString("\n") { "${ws.relative(it.first)}: ${it.second}" } + "\n${counts.sumOf { it.second }} matching lines in ${counts.size} files." + more)
            else -> if (out.isEmpty()) ToolResult("No matches.") else ToolResult(out.toString().trimEnd('\n') + more)
        }
    }

    companion object {
        const val MAX_MATCHES = 200
        const val MAX_CHARS = 60_000
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
    }
}

private class WriteFile(ws: Workspace, reads: FileReads, check: SyntaxCheck?) : ChangingTool(ws, reads, check) {
    override val risk = ToolRisk.WRITE
    override val staleAllowed = false
    override val spec = ToolSpec(
        "write_file",
        "Create a text file, or replace an existing file's entire contents. Missing parent folders are created. To replace a file that exists, read it with read_file first. " +
            "The previous version of a replaced file is kept in the workspace trash and can be restored by the user.",
        ObjectSchema()
            .string("path", "File path relative to the workspace root.")
            .string("content", "The complete new file contents.")
            .build(),
    )

    override fun describe(args: JsonObject): String {
        val path = args.str("path")
        val existed = runCatching { ws.resolve(path.orEmpty()).exists() }.getOrDefault(false)
        return "${if (existed) "Overwrite" else "Create"} $path (${args.str("content")?.length ?: 0} characters)"
    }

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val file = ws.resolve(arg(args, "path"))
        if (file.isDirectory) throw ToolException("'${ws.relative(file)}' is a folder.")
        val existed = file.exists()
        if (existed && chat != null) readProblem(chat, file)?.let { throw ToolException(it) }
        val content = arg(args, "content")
        if (existed) ws.backup(file, TrashEntry.Reason.OVERWRITTEN)
        file.parentFile?.mkdirs()
        file.writeText(content)
        if (chat != null) reads.record(chat, file)
        return ToolResult("${if (existed) "Replaced" else "Created"} ${ws.relative(file)} (${file.length()} ${if (file.length() == 1L) "byte" else "bytes"}).")
    }
}

private class EditFile(ws: Workspace, reads: FileReads, check: SyntaxCheck?) : ChangingTool(ws, reads, check) {
    override val risk = ToolRisk.WRITE
    override val staleAllowed = true
    override val spec = ToolSpec(
        "edit_file",
        "Replace exact text in a text file. Read the file with read_file first: edits to a file this chat hasn't read are refused. " +
            "old_text must match the file exactly, whitespace and indentation included, without read_file's line-number prefixes; " +
            "it must occur once unless replace_all is true, so include a few surrounding lines to make it unique. " +
            "If it only matches after ignoring trailing spaces or a uniform difference in indentation, and in one place only, the edit is made there and the result says so. " +
            "Returns the changed lines. For several changes to one file, use multi_edit. The previous version is kept in the trash.",
        ObjectSchema()
            .string("path", "File path relative to the workspace root.")
            .string("old_text", "Exact text to replace, including whitespace.")
            .string("new_text", "Replacement text.")
            .boolean("replace_all", "Replace every occurrence of old_text, e.g. to rename something. Defaults to false.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Edit ${args.str("path")}"

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val file = editableFile(ws, arg(args, "path"))
        val edit = Replacement(arg(args, "old_text"), args.str("new_text") ?: throw ToolException("Missing 'new_text'."), args.bool("replace_all") == true)
        return ToolResult(applyEdits(file, chat, listOf(edit), multi = false))
    }
}

private class MultiEdit(ws: Workspace, reads: FileReads, check: SyntaxCheck?) : ChangingTool(ws, reads, check) {
    override val risk = ToolRisk.WRITE
    override val staleAllowed = true
    override val spec = ToolSpec(
        "multi_edit",
        "Make several exact-text replacements in one file at once. Edits apply in order, each to the result of the one before, with the same rules as edit_file (read the file first). " +
            "All or nothing: if any edit fails, the file is left unchanged. Returns the changed lines. The previous version is kept in the trash.",
        ObjectSchema()
            .string("path", "File path relative to the workspace root.")
            .array(
                "edits",
                "The replacements, in order.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("old_text") { put("type", "string"); put("description", "Exact text to replace, including whitespace.") }
                        putJsonObject("new_text") { put("type", "string"); put("description", "Replacement text.") }
                        putJsonObject("replace_all") { put("type", "boolean"); put("description", "Replace every occurrence. Defaults to false.") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("old_text")); add(JsonPrimitive("new_text")) }
                    put("additionalProperties", false)
                },
            )
            .build(),
    )

    override fun describe(args: JsonObject) = "Edit ${args.str("path")} (${(args["edits"] as? JsonArray)?.size ?: 0} changes)"

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val edits = args["edits"] as? JsonArray ?: throw ToolException("Missing 'edits'.")
        if (edits.isEmpty()) throw ToolException("'edits' is empty.")
        if (edits.size > MAX_EDITS) throw ToolException("At most $MAX_EDITS edits per call.")
        val file = editableFile(ws, arg(args, "path"))
        val replacements = edits.mapIndexed { i, element ->
            val edit = element as? JsonObject ?: throw ToolException("Edit ${i + 1} is not an object with old_text and new_text.")
            Replacement(
                edit.str("old_text") ?: throw ToolException("Edit ${i + 1} is missing 'old_text'."),
                edit.str("new_text") ?: throw ToolException("Edit ${i + 1} is missing 'new_text'."),
                edit.bool("replace_all") == true,
            )
        }
        return ToolResult(applyEdits(file, chat, replacements, multi = true))
    }

    companion object {
        const val MAX_EDITS = 50
    }
}

/** An existing text file the edit tools may change. */
private fun editableFile(ws: Workspace, path: String): File {
    val file = ws.existing(path)
    if (file.isDirectory) throw ToolException("'${ws.relative(file)}' is a folder.")
    if (!CodeFiles.isText(file)) throw ToolException("'${ws.relative(file)}' is a binary file; edit tools only change text files.")
    if (file.length() > MAX_EDIT_BYTES) throw ToolException("'${ws.relative(file)}' is over ${MAX_EDIT_BYTES / 1024 / 1024} MB; change it with a command in run_command instead.")
    return file
}

private const val MAX_EDIT_BYTES = 10L * 1024 * 1024

/** The span of lines that differ between [before] and [after], with a little context, numbered. */
private fun changedLines(before: String, after: String): String {
    var prefix = 0
    val shortest = minOf(before.length, after.length)
    while (prefix < shortest && before[prefix] == after[prefix]) prefix++
    var suffix = 0
    while (suffix < shortest - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
    return TextEdits.snippet(after, prefix, (after.length - prefix - suffix).coerceAtLeast(0))
}

private class MovePath(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.WRITE
    override val spec = ToolSpec(
        "move_path",
        "Move or rename a file or folder inside the workspace (like mv). Fails if the destination exists unless overwrite is true, in which case the destination is moved to the trash first.",
        ObjectSchema()
            .string("from", "Existing path relative to the workspace root.")
            .string("to", "New path relative to the workspace root.")
            .boolean("overwrite", "Replace an existing destination. Defaults to false.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Move ${args.str("from")} → ${args.str("to")}"

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val from = ws.existing(arg(args, "from"))
        val to = ws.resolve(arg(args, "to"))
        if (from == ws.resolve(".")) throw ToolException("The workspace root cannot be moved.")
        if (to.path.startsWith(from.path + File.separator)) throw ToolException("Cannot move a folder into itself.")
        if (to.exists()) {
            if (args.bool("overwrite") != true) throw ToolException("'${ws.relative(to)}' already exists. Set overwrite to true to replace it.")
            ws.trash(to, TrashEntry.Reason.MOVED_OVER)
        }
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        }
        return ToolResult("Moved ${args.str("from")} → ${ws.relative(to)}.")
    }
}

private class CopyPath(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.WRITE
    override val spec = ToolSpec(
        "copy_path",
        "Copy a file or folder inside the workspace. Fails if the destination exists.",
        ObjectSchema()
            .string("from", "Existing path relative to the workspace root.")
            .string("to", "Destination path relative to the workspace root.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Copy ${args.str("from")} → ${args.str("to")}"

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val from = ws.existing(arg(args, "from"))
        val to = ws.resolve(arg(args, "to"))
        if (to.exists()) throw ToolException("'${ws.relative(to)}' already exists.")
        if (to.path.startsWith(from.path + File.separator)) throw ToolException("Cannot copy a folder into itself.")
        from.copyRecursively(to)
        return ToolResult("Copied to ${ws.relative(to)}.")
    }
}

private class DeletePath(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.WRITE
    override val spec = ToolSpec(
        "delete_path",
        "Delete a file or folder by moving it to the workspace trash, where the user can restore it.",
        ObjectSchema().string("path", "Path relative to the workspace root.").build(),
    )

    override fun describe(args: JsonObject) = "Delete ${args.str("path")} (recoverable from trash)"

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val file = ws.existing(arg(args, "path"))
        if (file == ws.resolve(".")) throw ToolException("The workspace root cannot be deleted.")
        ws.trash(file, TrashEntry.Reason.DELETED)
        return ToolResult("Moved ${ws.relative(file).ifEmpty { args.str("path").orEmpty() }} to the trash.")
    }
}

private class MakeDirectory(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.WRITE
    override val spec = ToolSpec(
        "make_directory",
        "Create a folder (and any missing parents) in the workspace.",
        ObjectSchema().string("path", "Folder path relative to the workspace root.").build(),
    )

    override fun describe(args: JsonObject) = "Create folder ${args.str("path")}"

    override fun run(args: JsonObject, chat: String?): ToolResult {
        val dir = ws.resolve(arg(args, "path"))
        if (dir.isFile) throw ToolException("'${ws.relative(dir)}' is a file.")
        dir.mkdirs()
        return ToolResult("Folder ${ws.relative(dir)} is ready.")
    }
}
