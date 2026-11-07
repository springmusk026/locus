package dev.locus.core.agent.tools

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.ReviewerClearance
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.FileTrees
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
 * existing files need a read first; [changes] is told about every path a tool changes, for the
 * chat's changes card and undo.
 */
fun workspaceTools(ws: Workspace, syntaxCheck: SyntaxCheck? = null, reads: FileReads = FileReads(), changes: ChangeRecorder? = null): List<Tool> = listOf(
    ListFiles(ws), ReadFile(ws, reads), SearchFiles(ws), FindFiles(ws), WriteFile(ws, reads, syntaxCheck), EditFile(ws, reads, syntaxCheck), MultiEdit(ws, reads, syntaxCheck),
    MovePath(ws), CopyPath(ws), DeletePath(ws), MakeDirectory(ws),
).onEach { it.recorder = changes } + codeTools(ws)

/** A path a tool changed, with where its earlier state is kept (see [FileChange]). */
internal data class Touched(val path: String, val existedBefore: Boolean, val beforeTrashId: String?, val isDirectory: Boolean = false)

private abstract class WorkspaceTool(protected val ws: Workspace) : Tool {
    /** Told about the paths this tool changes; set once when the tools are made. */
    var recorder: ChangeRecorder? = null

    protected fun arg(args: JsonObject, key: String) = args.str(key) ?: throw ToolException("Missing '$key'.")

    /** The argument naming the path this tool changes, for [indirectTarget]: copy_path's destination, else `path`. */
    protected open val changedPathKey: String = "path"

    override suspend fun indirectTarget(args: JsonObject): String? {
        val path = args.str(changedPathKey) ?: return null
        return withContext(Dispatchers.IO) { runCatching { ws.indirection(path) }.getOrElse { it.message ?: "The path can't be checked." } }
    }

    /** The arguments naming paths this tool changes, for the connected-folder rules; none for a tool that only reads. */
    protected open val changedKeys: List<String> = emptyList()

    /** The connected-folder path [args]' [key] names, or null for a workspace path (or none). */
    protected fun connectedTarget(args: JsonObject, key: String = "path"): ConnectedFiles.Target? =
        ws.connected?.target(args.str(key) ?: return null)

    /** The connected targets this call changes. Paths that can't be read here are left to [run] to report. */
    private fun changedTargets(args: JsonObject) = changedKeys.mapNotNull { k -> runCatching { connectedTarget(args, k) }.getOrNull() }

    /** A write to a connected folder asks even when the tool is set to Allow; "Always for this folder" can still cover it. */
    override suspend fun asksFirst(args: JsonObject): Boolean = withContext(Dispatchers.IO) { changedTargets(args).isNotEmpty() }

    /**
     * A change to a connected folder is to the user's own files outside Locus: the safety reviewer may clear it
     * only when the user let it approve outward-facing actions. Deleting there (delete_path, or move_path out of
     * the folder or over a file in it: their [mustConfirm] says "always asks") is the user's alone.
     */
    override suspend fun reviewerClearance(args: JsonObject): ReviewerClearance = when {
        withContext(Dispatchers.IO) { changedTargets(args).isEmpty() } -> ReviewerClearance.REVIEWER
        mustConfirm(args) != null -> ReviewerClearance.USER_ONLY
        else -> ReviewerClearance.REVIEWER_IF_OUTWARD_ALLOWED
    }

    /** A read-only connected folder (or a hidden folder in one) refuses before anyone is asked. */
    override suspend fun precheck(args: JsonObject): String? = connectedProblem(args)

    protected suspend fun connectedProblem(args: JsonObject): String? = withContext(Dispatchers.IO) {
        val c = ws.connected ?: return@withContext null
        changedTargets(args).firstNotNullOfOrNull { c.writeProblem(it) }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val ctx = currentCoroutineContext()[ToolCallContext]
        val touched = mutableListOf<Touched>()
        val result = withContext(Dispatchers.IO) { run(args, ctx?.conversationId, touched) }
        val owner = ctx?.changeOwner
        val recorder = recorder
        if (owner != null && recorder != null) {
            touched.forEach { t ->
                // Tracking is a convenience: it never fails the change itself.
                try {
                    recorder.record(FileChange(owner, ctx.callId, spec.name, t.path, t.existedBefore, t.beforeTrashId, t.isDirectory))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        return result
    }

    /**
     * [chat] is the conversation (or helper) making the call, or null outside a chat (tests, tools calling
     * tools). A tool that changes paths adds each to [changes], once the change is made.
     */
    protected abstract fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult
}

/**
 * A tool that writes an existing or new file: it needs the chat to have read an existing file first
 * (refused before the user is asked, through [precheck]), and runs the [check] afterwards.
 */
private abstract class ChangingTool(ws: Workspace, protected val reads: FileReads, private val check: SyntaxCheck?) : WorkspaceTool(ws) {
    /** Whether a file changed since the chat read it may still be changed, given an exact match (edits); a whole-file write may not. */
    protected abstract val staleAllowed: Boolean

    override val changedKeys = listOf("path")

    override suspend fun precheck(args: JsonObject): String? {
        connectedProblem(args)?.let { return it }
        val chat = currentCoroutineContext()[ToolCallContext]?.conversationId ?: return null
        // Anything unexpected here is left for execute to report.
        return withContext(Dispatchers.IO) {
            runCatching {
                existingTarget(args.str("path").orEmpty())?.let { readProblem(chat, it) }
            }.getOrNull()
        }
    }

    /** The existing file [path] names, in the workspace or a connected folder, or null when there's none. */
    protected fun existingTarget(path: String): EditTarget? {
        val c = ws.connected
        c?.target(path)?.let { t ->
            val e = c.stat(t, fresh = true) ?: return null
            return if (e.isDirectory) null else ConnectedEdit(c, reads, t)
        }
        return ws.resolve(path).takeIf { it.isFile }?.let { FileEdit(ws, reads, it) }
    }

    /** Why [chat] may not change [target] yet, or null. */
    protected fun readProblem(chat: String, target: EditTarget): String? = when (target.state(chat)) {
        FileReads.State.NOT_READ ->
            "This chat hasn't read ${target.display} yet (or Locus restarted since it did). Read it with read_file first, then make the change with text copied from what you read."
        FileReads.State.REVERTED ->
            "The user undid changes to ${target.display} since this chat read it, so what you read is out of date. Read it again with read_file before changing it."
        FileReads.State.CHANGED -> if (staleAllowed) null else
            "${target.display} has changed on disk since this chat read it (a command, another tool, another app or the user changed it). Read it again with read_file before replacing it, so those changes aren't lost."
        else -> null
    }

    /**
     * Applies [edits] to [file] in order, all or nothing, for [chat]. The chat must have read the file;
     * if it changed on disk since then, only exact matches are trusted (as Claude Code does) and the
     * result says so. Returns the result's text, or throws with what to fix.
     */
    protected fun applyEdits(file: EditTarget, chat: String?, edits: List<Replacement>, multi: Boolean, changes: MutableList<Touched>): String {
        val state = chat?.let { file.state(it) }
        if (chat != null && (state == FileReads.State.NOT_READ || state == FileReads.State.REVERTED)) throw ToolException(readProblem(chat, file).orEmpty())
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
                val why = if (stale) "${file.display} has changed on disk since this chat read it, so only an exact match is used. " else ""
                throw ToolException(prefix + why + e.message + if (stale) " Read the file again with read_file." else "")
            }
        }
        val before = file.backup()
        file.write(content)
        changes += Touched(file.stored, existedBefore = true, beforeTrashId = before.id)
        if (chat != null) file.record(chat)
        val what = when {
            multi -> " (${edits.size} ${if (edits.size == 1) "change" else "changes"})"
            replacements > 1 -> " ($replacements replacements)"
            else -> ""
        }
        if (stale) notes += "The file had other changes since this chat read it; read it again before edits that depend on the lines around this one."
        return "Edited ${file.display}$what." + notes.joinToString("") { "\n$it" } + (if (notes.isEmpty()) " " else "\n") +
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
        "List files and folders in the workspace. Directories end with '/'. Sizes are in bytes. " +
            "Folders the user connected from their phone's storage (a notes vault, a synced folder) are under folders/<name>/: list 'folders' to see them.",
        ObjectSchema()
            .string("path", "Folder to list, relative to the workspace root. Defaults to the root.", required = false)
            .boolean("recursive", "Include everything below the folder. Defaults to false.")
            .build(),
    )

    override fun describe(args: JsonObject) = "List ${args.str("path") ?: "workspace"}"

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        val c = ws.connected
        val path = args.str("path") ?: "."
        if (c != null && c.isTop(path)) return listTop(c)
        c?.target(path)?.let { t -> return listConnected(c, t, args.bool("recursive") == true) }
        val dir = ws.existing(path)
        if (!dir.isDirectory) throw ToolException("'${ws.relative(dir)}' is a file, not a folder.")
        // Recursive listing never enters a linked folder, which could be anywhere on the phone.
        val files = if (args.bool("recursive") == true) below(dir) else dir.listFiles().orEmpty().asSequence()
        val root = ws.root
        val lines = files.sortedBy { it.path }.take(MAX_ENTRIES + 1).map { f ->
            when {
                // A link is shown by its own name, never by (or with the size of) what it points at.
                FileTrees.isLink(f) -> "${f.relativeTo(root).path}@  (link)"
                f.isDirectory -> "${ws.relative(f)}/"
                else -> "${ws.relative(f)}  (${f.length()})"
            }
        }.toList()
        // The top of the chat's folder names the connected folders, which aren't files on disk.
        val connected = if (dir == ws.root) c?.visible().orEmpty() else emptyList()
        val folders = if (connected.isEmpty()) "" else
            "\n${dev.locus.core.model.ConnectedFolders.PREFIX}/  (connected folders: ${connected.joinToString { it.name }}; list 'folders' for details)"
        if (lines.isEmpty() && folders.isEmpty()) return ToolResult("(empty)")
        val more = if (lines.size > MAX_ENTRIES) "\n…more entries not shown; list a subfolder." else ""
        return ToolResult((lines.take(MAX_ENTRIES).joinToString("\n") + more + folders).trimStart('\n'))
    }

    /** `folders`: the connected folders this chat sees, with what the agent may do in each. */
    private fun listTop(c: ConnectedFiles): ToolResult {
        val visible = c.visible()
        if (visible.isEmpty()) return ToolResult("No connected folders are linked to this project. The user can link one in Workspace → Connected folders.")
        val lines = visible.sortedBy { it.name }.map { f ->
            val access = if (f.readOnly) "read-only" else "read and write; writes ask the user"
            "${dev.locus.core.model.ConnectedFolders.pathOf(f)}/  ($access)"
        }
        return ToolResult(
            lines.joinToString("\n") + "\nThese are folders on the phone the user connected through Android's storage access. The file tools work in them; " +
                "Linux and run_command can't see them. Hidden folders in them (.obsidian, .git, .trash) are read-only.",
        )
    }

    private fun listConnected(c: ConnectedFiles, t: ConnectedFiles.Target, recursive: Boolean): ToolResult {
        val top = c.existing(t)
        if (!top.isDirectory) throw ToolException("'${t.path}' is a file, not a folder.")
        val lines = mutableListOf<String>()
        val queue = ArrayDeque(listOf(t))
        while (queue.isNotEmpty() && lines.size <= MAX_ENTRIES) {
            val dir = queue.removeFirst()
            for (child in c.list(dir).sortedBy { it.name }) {
                val at = dir.child(child.name)
                lines += if (child.isDirectory) "${at.path}/" else "${at.path}  (${child.size})"
                if (recursive && child.isDirectory) queue += at
                if (lines.size > MAX_ENTRIES) break
            }
        }
        if (lines.isEmpty()) return ToolResult("(empty)")
        val more = if (lines.size > MAX_ENTRIES) "\n…more entries not shown; list a subfolder." else ""
        return ToolResult(lines.take(MAX_ENTRIES).joinToString("\n") + more)
    }

    /** Everything below [dir], lazily. A link (to a folder or a file) is listed but never entered. */
    private fun below(dir: File): Sequence<File> = sequence {
        for (child in dir.listFiles().orEmpty()) {
            yield(child)
            if (!FileTrees.isLink(child) && child.isDirectory) yieldAll(below(child))
        }
    }

    companion object {
        const val MAX_ENTRIES = 500
    }
}

private class ReadFile(ws: Workspace, private val reads: FileReads) : WorkspaceTool(ws) {
    override val risk = ToolRisk.READ

    // It pages itself (start_line, max_lines); saving a file it's reading to another file helps no one.
    override val pagesOwnOutput = true
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

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        connectedTarget(args)?.let { t -> return readConnected(t, args, chat) }
        val file = ws.existing(arg(args, "path"))
        if (file.isDirectory) throw ToolException("'${ws.relative(file)}' is a folder; use list_files or find_files.")
        if (!CodeFiles.isText(file)) {
            // Seeing that it's binary is all there is to read; write_file may then replace it.
            if (chat != null) reads.record(chat, file)
            return ToolResult("'${ws.relative(file)}' is a binary file (${file.length()} bytes).")
        }
        return render(ws.relative(file), args, file.bufferedReader()) { if (chat != null) reads.record(chat, file) }
    }

    private fun readConnected(t: ConnectedFiles.Target, args: JsonObject, chat: String?): ToolResult {
        val c = ws.connected!!
        if (c.existing(t).isDirectory) throw ToolException("'${t.path}' is a folder; use list_files or find_files.")
        val bytes = c.readBytes(t)
        val seen = { if (chat != null) c.recordRead(reads, chat, t, bytes) }
        if ((0 until minOf(bytes.size, 4096)).any { bytes[it] == 0.toByte() }) {
            seen()
            return ToolResult("'${t.path}' is a binary file (${bytes.size} bytes).")
        }
        return render(t.path, args, bytes.inputStream().bufferedReader(), seen)
    }

    /** The numbered lines [args] ask for from [reader] ([display] names the file); [seen] runs once the read counts. */
    private fun render(display: String, args: JsonObject, reader: java.io.BufferedReader, seen: () -> Unit): ToolResult {
        val start = (args.int("start_line") ?: 1).coerceAtLeast(1)
        val max = (args.int("max_lines") ?: 2000).coerceIn(1, 5000)
        val out = StringBuilder()
        var total = 0
        var last = 0
        var capped = false
        // A file may have been downloaded or written by someone else: hidden characters (Unicode tags,
        // bidi controls, zero-width text) are taken out of what the model reads (docs/design/taint.md).
        var hidden = 0
        reader.useLines { lines ->
            lines.forEachIndexed { i, raw ->
                total = i + 1
                if (total >= start && total < start + max && !capped) {
                    val cleaned = dev.locus.core.model.HiddenText.clean(raw)
                    val line = cleaned.text
                    val text = if (line.length > TextEdits.MAX_LINE) line.take(TextEdits.MAX_LINE) + "… [line cut: ${line.length} characters]" else line
                    if (last > 0 && out.length + text.length > MAX_CHARS) {
                        capped = true
                    } else {
                        out.append(total).append('\t').append(text).append('\n')
                        last = total
                        hidden += cleaned.removed
                    }
                }
            }
        }
        if (total == 0) {
            seen()
            return ToolResult("(empty file)")
        }
        if (start > total) throw ToolException("'$display' has $total lines; start_line $start is past the end.")
        seen()
        val note = if (last < total) "\n[lines $start–$last of $total${if (capped) ", output limit reached" else ""}; continue with start_line=${last + 1}]" else ""
        return ToolResult(out.toString() + note + hiddenNote(hidden))
    }

    companion object {
        const val MAX_CHARS = 100_000

        /** The line after the file's text when hidden characters were taken out of it. */
        internal fun hiddenNote(removed: Int): String =
            dev.locus.core.model.HiddenText.note(removed, "these lines")
                ?.let { "\n$it To change those lines, give edit_file whole lines as old_text." }
                .orEmpty()
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

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        val pattern = arg(args, "pattern")
        val regex = runCatching { CodeFiles.globRegex(pattern) }.getOrElse { throw ToolException("Invalid glob '$pattern': ${it.message}") }
        val max = (args.int("max_results") ?: 200).coerceIn(1, 1000)
        connectedTarget(args)?.let { t ->
            val c = ws.connected!!
            if (!c.existing(t).isDirectory) throw ToolException("'${t.path}' is a file, not a folder.")
            val walk = c.walk(t, args.bool("include_ignored") == true)
            val hits = walk.files
                .filter { (at, _) -> regex.matches(at.segments.drop(t.segments.size).joinToString("/")) }
                .sortedWith(compareByDescending<Pair<ConnectedFiles.Target, DocEntry>> { it.second.modified }.thenBy { it.first.path })
            val capped = if (walk.capped) "\n…stopped after ${ConnectedFiles.MAX_WALK} entries in this connected folder; search a subfolder." else ""
            if (hits.isEmpty()) return ToolResult("No files match '$pattern'.$capped")
            val more = if (hits.size > max) "\n…${hits.size - max} more; narrow the pattern or the folder." else ""
            return ToolResult(hits.take(max).joinToString("\n") { it.first.path } + more + capped)
        }
        val dir = ws.existing(args.str("path") ?: ".")
        if (!dir.isDirectory) throw ToolException("'${ws.relative(dir)}' is a file, not a folder.")
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

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
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
        var capped = ""
        val items: Sequence<Item> = connectedTarget(args)?.let { t ->
            val c = ws.connected!!
            val walk = c.walk(t, args.bool("include_ignored") == true)
            if (walk.capped) capped = "\n…stopped after ${ConnectedFiles.MAX_WALK} entries in this connected folder; search a subfolder."
            val isFile = !c.existing(t).isDirectory
            walk.files.asSequence()
                .filter { (at, entry) ->
                    (suffix == null || at.name.endsWith(suffix)) && entry.size < MAX_FILE_BYTES &&
                        (glob == null || glob.matches(if (isFile) at.name else at.segments.drop(t.segments.size).joinToString("/")))
                }
                .sortedBy { it.first.path }
                .map { (at, entry) ->
                    Item(at.path, entry.modified) {
                        runCatching { c.readBytes(at, MAX_FILE_BYTES) }.getOrNull()
                            ?.takeIf { b -> (0 until minOf(b.size, 4096)).none { b[it] == 0.toByte() } }
                            ?.inputStream()?.bufferedReader()?.readLines()
                    }
                }
        } ?: run {
            val start = ws.existing(args.str("path") ?: ".")
            CodeFiles.walk(ws, start, args.bool("include_ignored") == true)
                .filter { f ->
                    (suffix == null || f.name.endsWith(suffix)) && f.length() < MAX_FILE_BYTES &&
                        (glob == null || glob.matches((if (start.isFile) f.name else f.relativeTo(start).path).replace(File.separatorChar, '/')))
                }
                .sortedBy { it.path }
                .filter { CodeFiles.isText(it) }
                .map { f -> Item(ws.relative(f), f.lastModified()) { runCatching { f.readLines() }.getOrNull() } }
        }

        val out = StringBuilder()
        var hits = 0
        var stopped = false
        val counts = mutableListOf<Pair<Item, Int>>()
        for (file in items) {
            if (stopped) break
            val lines = file.lines() ?: continue
            val matched = lines.indices.filter { regex.containsMatchIn(lines[it]) }
            if (matched.isEmpty()) continue
            if (mode != "content") {
                counts += file to matched.size
                if (counts.size >= max) stopped = true
                continue
            }
            val path = file.display
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
        val more = (if (stopped) "\n…stopped at $max ${if (mode == "content") "matches" else "files"}; narrow the search." else "") + capped
        return when (mode) {
            "files" -> if (counts.isEmpty()) ToolResult("No matches.$capped")
            else ToolResult(counts.sortedWith(compareByDescending<Pair<Item, Int>> { it.first.modified }.thenBy { it.first.display }).joinToString("\n") { it.first.display } + more)
            "count" -> if (counts.isEmpty()) ToolResult("No matches.$capped")
            else ToolResult(counts.joinToString("\n") { "${it.first.display}: ${it.second}" } + "\n${counts.sumOf { it.second }} matching lines in ${counts.size} files." + more)
            else -> if (out.isEmpty()) ToolResult("No matches.$capped") else ToolResult(out.toString().trimEnd('\n') + more)
        }
    }

    /** A file to search: its path as shown, when it changed, and its lines (null when it can't be read as text). */
    private class Item(val display: String, val modified: Long, val lines: () -> List<String>?)

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

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        connectedTarget(args)?.let { t -> return writeConnected(t, args, chat, changes) }
        val file = ws.resolve(arg(args, "path"))
        if (file.isDirectory) throw ToolException("'${ws.relative(file)}' is a folder.")
        val existed = file.exists()
        if (existed && chat != null) readProblem(chat, FileEdit(ws, reads, file))?.let { throw ToolException(it) }
        val content = arg(args, "content")
        val before = if (existed) ws.backup(file, TrashEntry.Reason.OVERWRITTEN) else null
        // Folders are created and the file opened without following a link (Workspace.writeText).
        ws.writeText(file, content)
        changes += Touched(ws.stored(file), existedBefore = existed, beforeTrashId = before?.id)
        if (chat != null) reads.record(chat, file)
        return ToolResult("${if (existed) "Replaced" else "Created"} ${ws.relative(file)} (${file.length()} ${if (file.length() == 1L) "byte" else "bytes"}).")
    }

    private fun writeConnected(t: ConnectedFiles.Target, args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        val c = ws.connected!!
        c.requireWritable(t)
        val e = c.stat(t, fresh = true)
        if (e?.isDirectory == true) throw ToolException("'${t.path}' is a folder.")
        if (e != null && chat != null) readProblem(chat, ConnectedEdit(c, reads, t))?.let { throw ToolException(it) }
        val bytes = arg(args, "content").toByteArray()
        if (bytes.size > ConnectedFiles.MAX_FILE_BYTES) throw ToolException("That's over ${ConnectedFiles.MAX_FILE_BYTES / 1024 / 1024} MB, too large to write to a connected folder.")
        val before = if (e != null) c.backup(t, TrashEntry.Reason.OVERWRITTEN) else null
        c.writeFile(t, bytes)
        changes += Touched(t.path, existedBefore = e != null, beforeTrashId = before?.id)
        if (chat != null) c.recordRead(reads, chat, t, bytes)
        return ToolResult("${if (e != null) "Replaced" else "Created"} ${t.path} (${bytes.size} ${if (bytes.size == 1) "byte" else "bytes"}).")
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

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        val file = editable(ws, reads, arg(args, "path"))
        val edit = Replacement(arg(args, "old_text"), args.str("new_text") ?: throw ToolException("Missing 'new_text'."), args.bool("replace_all") == true)
        return ToolResult(applyEdits(file, chat, listOf(edit), multi = false, changes))
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

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        val edits = args["edits"] as? JsonArray ?: throw ToolException("Missing 'edits'.")
        if (edits.isEmpty()) throw ToolException("'edits' is empty.")
        if (edits.size > MAX_EDITS) throw ToolException("At most $MAX_EDITS edits per call.")
        val file = editable(ws, reads, arg(args, "path"))
        val replacements = edits.mapIndexed { i, element ->
            val edit = element as? JsonObject ?: throw ToolException("Edit ${i + 1} is not an object with old_text and new_text.")
            Replacement(
                edit.str("old_text") ?: throw ToolException("Edit ${i + 1} is missing 'old_text'."),
                edit.str("new_text") ?: throw ToolException("Edit ${i + 1} is missing 'new_text'."),
                edit.bool("replace_all") == true,
            )
        }
        return ToolResult(applyEdits(file, chat, replacements, multi = true, changes))
    }

    companion object {
        const val MAX_EDITS = 50
    }
}

/** An existing text file the edit tools may change, in the workspace or a connected folder. */
private fun editable(ws: Workspace, reads: FileReads, path: String): EditTarget {
    val c = ws.connected
    c?.target(path)?.let { t ->
        c.requireWritable(t)
        val e = c.stat(t, fresh = true) ?: throw ToolException("'${t.path}' does not exist.")
        if (e.isDirectory) throw ToolException("'${t.path}' is a folder.")
        if (e.size > MAX_EDIT_BYTES) throw ToolException("'${t.path}' is over ${MAX_EDIT_BYTES / 1024 / 1024} MB, too large to edit in a connected folder.")
        if (!c.isText(t)) throw ToolException("'${t.path}' is a binary file; edit tools only change text files.")
        return ConnectedEdit(c, reads, t)
    }
    return FileEdit(ws, reads, editableFile(ws, path))
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

    override val changedKeys = listOf("from", "to")

    /** Moving something out of a connected folder, or over something in one, deletes there: that always asks. */
    override suspend fun mustConfirm(args: JsonObject): String? = withContext(Dispatchers.IO) {
        val from = runCatching { connectedTarget(args, "from") }.getOrNull()
        val to = runCatching { connectedTarget(args, "to") }.getOrNull()
        when {
            from != null && (to == null || to.folder.id != from.folder.id) ->
                "This moves ${from.path} out of the connected folder ${from.folder.name}, which deletes it there. Deleting from a connected folder always asks."
            to != null && args.bool("overwrite") == true ->
                "This may replace ${to.path} in a connected folder. Deleting from a connected folder always asks."
            else -> null
        }
    }

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        if (connectedTarget(args, "from") != null || connectedTarget(args, "to") != null) return moveConnected(args, changes)
        val from = ws.existing(arg(args, "from"))
        val to = ws.resolve(arg(args, "to"))
        if (from == ws.resolve(".")) throw ToolException("The workspace root cannot be moved.")
        if (to.path.startsWith(from.path + File.separator)) throw ToolException("Cannot move a folder into itself.")
        if (to.exists() && args.bool("overwrite") != true) throw ToolException("'${ws.relative(to)}' already exists. Set overwrite to true to replace it.")
        // A moved file can be undone: a copy of it stays in the trash (folders aren't tracked; they can be large).
        val fromPath = ws.stored(from)
        val kept = if (from.isFile) ws.backup(from, TrashEntry.Reason.MOVED_AWAY) else null
        val replaced = if (to.exists()) ws.trash(to, TrashEntry.Reason.MOVED_OVER) else null
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            FileTrees.copy(from, to, overwrite = true)
            FileTrees.delete(from)
        }
        if (kept != null) {
            changes += Touched(fromPath, existedBefore = true, beforeTrashId = kept.id)
            changes += Touched(ws.stored(to), existedBefore = replaced != null, beforeTrashId = replaced?.id)
        }
        return ToolResult("Moved ${args.str("from")} → ${ws.relative(to)}.")
    }

    /** A move with a connected folder at either end: a rename inside one folder, otherwise a copy and a delete. */
    private fun moveConnected(args: JsonObject, changes: MutableList<Touched>): ToolResult {
        val c = ws.connected!!
        val fromT = connectedTarget(args, "from")
        val toT = connectedTarget(args, "to")
        fromT?.let { c.requireWritable(it); if (it.segments.isEmpty()) throw ToolException("A connected folder itself can't be moved.") }
        toT?.let { c.requireWritable(it); if (it.segments.isEmpty()) throw ToolException("'${it.path}' is the connected folder itself.") }
        val fromFile = if (fromT == null) ws.existing(arg(args, "from")) else null
        val fromEntry = fromT?.let(c::existing)
        if (fromFile != null && fromFile == ws.resolve(".")) throw ToolException("The workspace root cannot be moved.")
        val toFile = if (toT == null) ws.resolve(arg(args, "to")) else null
        if (fromT != null && toT != null && fromT.folder.id == toT.folder.id && toT.segments.take(fromT.segments.size) == fromT.segments) {
            throw ToolException("Cannot move a folder into itself.")
        }
        val toExists = toT?.let { c.stat(it, fresh = true) != null } ?: FileTrees.exists(toFile!!)
        val toShown = toT?.path ?: ws.relative(toFile!!)
        if (toExists && args.bool("overwrite") != true) throw ToolException("'$toShown' already exists. Set overwrite to true to replace it.")
        val isFile = fromEntry?.isDirectory == false || fromFile?.isFile == true
        val fromStored = fromT?.path ?: ws.stored(fromFile!!)
        val replaced = if (!toExists) null else toT?.let { c.trash(it, TrashEntry.Reason.MOVED_OVER) } ?: ws.trash(toFile!!, TrashEntry.Reason.MOVED_OVER)
        val kept: TrashEntry?
        if (fromT != null && toT != null && fromT.folder.id == toT.folder.id && fromT.parent == toT.parent) {
            // The provider's own rename: nothing is copied. A file keeps an earlier copy for undo.
            kept = if (isFile) c.backup(fromT, TrashEntry.Reason.MOVED_AWAY) else null
            c.rename(fromT, toT.name)
        } else {
            when {
                fromT != null && toT != null -> c.copyWithin(fromT, toT)
                toT != null -> c.copyIn(fromFile!!, toT)
                else -> if (isFile) ws.write(toFile!!) { out -> c.open(fromT!!).use { it.copyTo(out) } } else c.copyOut(fromT!!, toFile!!)
            }
            // The source goes to the trash: the copy kept there is what undo puts back.
            val trashed = fromT?.let { c.trash(it, TrashEntry.Reason.MOVED_AWAY) } ?: ws.trash(fromFile!!, TrashEntry.Reason.MOVED_AWAY)
            kept = trashed.takeIf { isFile }
        }
        if (kept != null) {
            changes += Touched(fromStored, existedBefore = true, beforeTrashId = kept.id)
            changes += Touched(toT?.path ?: ws.stored(toFile!!), existedBefore = replaced != null, beforeTrashId = replaced?.id)
        }
        return ToolResult("Moved ${args.str("from")} → $toShown.")
    }
}

private class CopyPath(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.WRITE
    override val changedPathKey = "to"
    override val spec = ToolSpec(
        "copy_path",
        "Copy a file or folder inside the workspace. Fails if the destination exists.",
        ObjectSchema()
            .string("from", "Existing path relative to the workspace root.")
            .string("to", "Destination path relative to the workspace root.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Copy ${args.str("from")} → ${args.str("to")}"

    override val changedKeys = listOf("to")

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        val fromT = connectedTarget(args, "from")
        val toT = connectedTarget(args, "to")
        if (fromT != null || toT != null) return copyConnected(fromT, toT, args, changes)
        val from = ws.existing(arg(args, "from"))
        val to = ws.resolve(arg(args, "to"))
        if (to.exists()) throw ToolException("'${ws.relative(to)}' already exists.")
        if (to.path.startsWith(from.path + File.separator)) throw ToolException("Cannot copy a folder into itself.")
        // Links inside the folder are copied as links: a folder Linux made can link to Locus's own files.
        FileTrees.copy(from, to)
        changes += Touched(ws.stored(to), existedBefore = false, beforeTrashId = null, isDirectory = to.isDirectory)
        return ToolResult("Copied to ${ws.relative(to)}.")
    }

    /** A copy into, out of or within connected folders: how files get from a vault into the workspace for Linux, and back. */
    private fun copyConnected(fromT: ConnectedFiles.Target?, toT: ConnectedFiles.Target?, args: JsonObject, changes: MutableList<Touched>): ToolResult {
        val c = ws.connected!!
        toT?.let { c.requireWritable(it); if (it.segments.isEmpty()) throw ToolException("'${it.path}' is the connected folder itself.") }
        val fromFile = if (fromT == null) ws.existing(arg(args, "from")) else null
        val fromEntry = fromT?.let(c::existing)
        val toFile = if (toT == null) ws.resolve(arg(args, "to")) else null
        if (toT != null && c.stat(toT, fresh = true) != null) throw ToolException("'${toT.path}' already exists.")
        if (toFile != null && FileTrees.exists(toFile)) throw ToolException("'${ws.relative(toFile)}' already exists.")
        if (fromT != null && toT != null && fromT.folder.id == toT.folder.id && toT.segments.take(fromT.segments.size) == fromT.segments) {
            throw ToolException("Cannot copy a folder into itself.")
        }
        when {
            fromT != null && toT != null -> c.copyWithin(fromT, toT)
            toT != null -> c.copyIn(fromFile!!, toT)
            fromEntry?.isDirectory == false -> ws.write(toFile!!) { out -> c.open(fromT!!).use { it.copyTo(out) } }
            else -> c.copyOut(fromT!!, toFile!!)
        }
        val isDirectory = fromEntry?.isDirectory ?: fromFile!!.isDirectory
        changes += Touched(toT?.path ?: ws.stored(toFile!!), existedBefore = false, beforeTrashId = null, isDirectory = isDirectory)
        val note = if (toT == null) " Linux sees it at /workspace/${ws.relative(toFile!!)}." else ""
        return ToolResult("Copied to ${toT?.path ?: ws.relative(toFile!!)}.$note")
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

    override val changedKeys = listOf("path")

    /** Deleting from a connected folder always asks, whatever the tool's setting or remembered approvals. */
    override suspend fun mustConfirm(args: JsonObject): String? = withContext(Dispatchers.IO) {
        runCatching { connectedTarget(args) }.getOrNull()?.let { t ->
            "This deletes ${t.path} from the connected folder ${t.folder.name} (a copy is kept in Locus's trash). Deleting from a connected folder always asks."
        }
    }

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        connectedTarget(args)?.let { t ->
            val c = ws.connected!!
            c.requireWritable(t)
            val entry = c.trash(t, TrashEntry.Reason.DELETED)
            changes += Touched(entry.originalPath, existedBefore = true, beforeTrashId = entry.id, isDirectory = entry.isDirectory)
            return ToolResult("Deleted ${t.path} from the connected folder; a copy is in Locus's trash.")
        }
        val file = ws.existing(arg(args, "path"))
        if (file == ws.resolve(".")) throw ToolException("The workspace root cannot be deleted.")
        val entry = ws.trash(file, TrashEntry.Reason.DELETED)
        changes += Touched(entry.originalPath, existedBefore = true, beforeTrashId = entry.id, isDirectory = entry.isDirectory)
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

    override val changedKeys = listOf("path")

    override fun run(args: JsonObject, chat: String?, changes: MutableList<Touched>): ToolResult {
        connectedTarget(args)?.let { t ->
            ws.connected!!.requireWritable(t)
            ws.connected.mkdirs(t)
            return ToolResult("Folder ${t.path} is ready.")
        }
        val dir = ws.resolve(arg(args, "path"))
        if (dir.isFile) throw ToolException("'${ws.relative(dir)}' is a file.")
        dir.mkdirs()
        return ToolResult("Folder ${ws.relative(dir)} is ready.")
    }
}
