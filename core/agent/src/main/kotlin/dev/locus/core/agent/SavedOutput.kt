package dev.locus.core.agent

import dev.locus.core.agent.tools.Workspace
import dev.locus.core.model.FileTrees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where long tool output goes when only part of it fits in the transcript: one file per result under
 * [DIR] in the workspace, which the agent can search or page through with its file tools. Like
 * Cursor's and Claude Code's handling of long output, the model sees the start and end inline and
 * reads the rest only when it needs to. See docs/design/tool-output.md.
 */
object SavedOutput {
    /** Relative to the workspace root. `.locus/` holds Locus's own state and is kept out of git. */
    const val DIR = ".locus/out"

    /** How many saved outputs are kept; older ones are deleted. */
    const val KEEP = 30

    /**
     * A file name for one result's saved output, sortable by time: `20261101-142233-3fa-web_read.log`.
     * Older versions named run_command's files `cmd-<time>-<rand>.log`; [prune] sorts both by time.
     */
    fun name(toolName: String, now: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date(now))
        val rand = (0..0xfff).random().toString(16).padStart(3, '0')
        val tool = toolName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifEmpty { "tool" }
        return "$stamp-$rand-$tool.log"
    }

    /** Deletes all but the newest [keep] saved outputs in [dir], and keeps the folder out of git. */
    fun prune(dir: File, keep: Int = KEEP) {
        // Both folders are in /workspace, where Linux can swap either for a link to Locus's own data.
        val parent = dir.parentFile ?: return
        if (FileTrees.isLink(dir) || FileTrees.isLink(parent) || !dir.isDirectory) return
        // Not File.exists/writeText: a dangling link at the name would be written through.
        if (!FileTrees.exists(File(parent, ".gitignore"))) FileTrees.replaceFile(parent, ".gitignore", "*\n".toByteArray())
        dir.listFiles { f -> f.isFile && OURS.matches(f.name) }.orEmpty()
            .sortedByDescending { it.name.removePrefix("cmd-") }
            .drop(keep)
            .forEach { it.delete() }
    }

    private val OURS = Regex("(?:cmd-)?\\d{8}-\\d{6}-[0-9a-f]{3}(?:-[A-Za-z0-9_-]+)?\\.log")
}

/** Saves a tool's full output so a shortened result can point to it. */
fun interface OutputSpill {
    /**
     * Writes [text] (the whole result of a [toolName] call) to a file and returns its path as the
     * agent's file tools name it, or null when it couldn't be saved.
     */
    suspend fun save(toolName: String, text: String): String?
}

/**
 * Saves into the workspace's [SavedOutput.DIR], scrubbed of the vault's secrets and credential-looking
 * tokens first ([scrubber]): the file is readable by every later call, the user's Files tab and Linux.
 */
class WorkspaceOutputSpill(
    private val workspace: Workspace,
    private val scrubber: suspend () -> SecretScrubber = { SecretScrubber() },
) : OutputSpill {
    override suspend fun save(toolName: String, text: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = workspace.resolve(SavedOutput.DIR)
            val file = File(dir, SavedOutput.name(toolName))
            // Linux can leave a link at the name, or swap a folder on the way for one; replace the
            // name, and refuse a linked folder, rather than write through either.
            FileTrees.writeReplacing(workspace.base, file, scrubber().scrub(text))
            runCatching { SavedOutput.prune(dir) }
            workspace.relative(file)
        }.getOrNull()
    }
}
