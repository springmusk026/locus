package dev.locus.core.agent.tools

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/** File tools over the agent's [Workspace]. All paths are relative to the workspace root. */
fun workspaceTools(ws: Workspace): List<Tool> = listOf(
    ListFiles(ws), ReadFile(ws), SearchFiles(ws), WriteFile(ws), EditFile(ws), MovePath(ws), CopyPath(ws), DeletePath(ws), MakeDirectory(ws),
)

private abstract class WorkspaceTool(protected val ws: Workspace) : Tool {
    protected fun arg(args: JsonObject, key: String) = args.str(key) ?: throw ToolException("Missing '$key'.")

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) { run(args) }

    protected abstract fun run(args: JsonObject): ToolResult
}

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

    override fun run(args: JsonObject): ToolResult {
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

private class ReadFile(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "read_file",
        "Read a text file from the workspace. Lines are prefixed with their 1-based number. For long files, read in ranges using start_line and max_lines.",
        ObjectSchema()
            .string("path", "File path relative to the workspace root.")
            .integer("start_line", "First line to return, 1-based. Defaults to 1.")
            .integer("max_lines", "How many lines to return. Defaults to 2000.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Read ${args.str("path")}"

    override fun run(args: JsonObject): ToolResult {
        val file = ws.existing(arg(args, "path"))
        if (file.isDirectory) throw ToolException("'${ws.relative(file)}' is a folder; use list_files.")
        val head = file.readHead(8192)
        if (head.any { it == 0.toByte() }) return ToolResult("'${ws.relative(file)}' is a binary file (${file.length()} bytes).")
        val start = (args.int("start_line") ?: 1).coerceAtLeast(1)
        val max = (args.int("max_lines") ?: 2000).coerceIn(1, 5000)
        val out = StringBuilder()
        var total = 0
        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { i, line ->
                total = i + 1
                if (total >= start && total < start + max) out.append(total).append('\t').append(line).append('\n')
            }
        }
        if (total == 0) return ToolResult("(empty file)")
        val end = minOf(total, start + max - 1)
        val note = if (end < total) "\n[lines $start–$end of $total]" else ""
        return ToolResult(out.toString() + note)
    }
}

private class SearchFiles(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "search_files",
        "Search text files in the workspace for a regular expression. Returns path:line: text for each match.",
        ObjectSchema()
            .string("pattern", "Regular expression (Java syntax).")
            .string("path", "Folder to search, relative to the workspace root. Defaults to the root.", required = false)
            .string("file_suffix", "Only search files whose name ends with this, e.g. '.md'.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Search for \"${args.str("pattern")}\""

    override fun run(args: JsonObject): ToolResult {
        val regex = runCatching { Regex(arg(args, "pattern")) }.getOrElse { throw ToolException("Invalid pattern: ${it.message}") }
        val suffix = args.str("file_suffix")
        val matches = mutableListOf<String>()
        ws.existing(args.str("path") ?: ".").walkTopDown()
            .filter { it.isFile && (suffix == null || it.name.endsWith(suffix)) && it.length() < MAX_FILE_BYTES }
            .forEach { file ->
                if (matches.size >= MAX_MATCHES) return@forEach
                val isText = file.readHead(4096).none { it == 0.toByte() }
                if (!isText) return@forEach
                file.bufferedReader().useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        if (matches.size < MAX_MATCHES && regex.containsMatchIn(line)) {
                            matches += "${ws.relative(file)}:${i + 1}: ${line.take(300)}"
                        }
                    }
                }
            }
        if (matches.isEmpty()) return ToolResult("No matches.")
        val more = if (matches.size >= MAX_MATCHES) "\n…stopped at $MAX_MATCHES matches; narrow the search." else ""
        return ToolResult(matches.joinToString("\n") + more)
    }

    companion object {
        const val MAX_MATCHES = 200
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
    }
}

private class WriteFile(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.WRITE
    override val spec = ToolSpec(
        "write_file",
        "Create a text file, or replace an existing file's entire contents. Missing parent folders are created. The previous version of a replaced file is kept in the workspace trash and can be restored by the user.",
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

    override fun run(args: JsonObject): ToolResult {
        val file = ws.resolve(arg(args, "path"))
        if (file.isDirectory) throw ToolException("'${ws.relative(file)}' is a folder.")
        val existed = file.exists()
        if (existed) ws.backup(file, TrashEntry.Reason.OVERWRITTEN)
        file.parentFile?.mkdirs()
        file.writeText(arg(args, "content"))
        return ToolResult("${if (existed) "Replaced" else "Created"} ${ws.relative(file)} (${file.length()} ${if (file.length() == 1L) "byte" else "bytes"}).")
    }
}

private class EditFile(ws: Workspace) : WorkspaceTool(ws) {
    override val risk = ToolRisk.WRITE
    override val spec = ToolSpec(
        "edit_file",
        "Replace one exact occurrence of old_text with new_text in a text file. old_text must appear exactly once; include surrounding lines to make it unique. The previous version is kept in the trash.",
        ObjectSchema()
            .string("path", "File path relative to the workspace root.")
            .string("old_text", "Exact text to replace, including whitespace.")
            .string("new_text", "Replacement text.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Edit ${args.str("path")}"

    override fun run(args: JsonObject): ToolResult {
        val file = ws.existing(arg(args, "path"))
        val old = arg(args, "old_text")
        val content = file.readText()
        when (val count = content.windowedCount(old)) {
            0 -> throw ToolException("old_text was not found in ${ws.relative(file)}.")
            1 -> Unit
            else -> throw ToolException("old_text appears $count times in ${ws.relative(file)}; include more context so it is unique.")
        }
        ws.backup(file, TrashEntry.Reason.OVERWRITTEN)
        file.writeText(content.replaceFirst(old, arg(args, "new_text")))
        return ToolResult("Edited ${ws.relative(file)}.")
    }

    private fun String.windowedCount(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val i = indexOf(needle, from)
            if (i < 0) return count
            count++
            from = i + needle.length
        }
    }
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

    override fun run(args: JsonObject): ToolResult {
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

    override fun run(args: JsonObject): ToolResult {
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

    override fun run(args: JsonObject): ToolResult {
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

    override fun run(args: JsonObject): ToolResult {
        val dir = ws.resolve(arg(args, "path"))
        if (dir.isFile) throw ToolException("'${ws.relative(dir)}' is a file.")
        dir.mkdirs()
        return ToolResult("Folder ${ws.relative(dir)} is ready.")
    }
}

/** First [max] bytes of the file. (InputStream.readNBytes needs API 33; minSdk is 26.) */
private fun File.readHead(max: Int): ByteArray = inputStream().use { input ->
    val buffer = ByteArray(max)
    var read = 0
    while (read < max) {
        val n = input.read(buffer, read, max - read)
        if (n < 0) break
        read += n
    }
    buffer.copyOf(read)
}
