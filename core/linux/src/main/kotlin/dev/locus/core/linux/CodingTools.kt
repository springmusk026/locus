package dev.locus.core.linux

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * Linux tools for coding work that only look: a background service's log, and git's view of a
 * repository. Both are reads, so they run without asking, unlike `run_command`.
 */
fun codingLinuxTools(linux: LinuxEnvironment, ws: Workspace): List<Tool> = listOf(ServiceLog(linux), GitInspect(linux, ws))

private class ServiceLog(private val linux: LinuxEnvironment) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "linux_service_log",
        "Read a background service's output (from linux_service_start): the last lines, optionally only those matching a regular expression. " +
            "Use it to check a dev server or watcher for errors after a change, or to find the port it listens on.",
        ObjectSchema()
            .string("name", "Service name.")
            .integer("lines", "How many of the last lines to return. Defaults to 100, max 2000.")
            .string("filter", "Only lines matching this regular expression (Java syntax), e.g. \"error|warn\".", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Read the log of “${args.str("name")}”"

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args.str("name").orEmpty()
        val status = linux.serviceStatuses().firstOrNull { it.name == name }
            ?: return ToolResult.error(
                "No service “$name”. " + linux.serviceStatuses().joinToString { it.name }.let { if (it.isEmpty()) "No services have been started." else "Services: $it." },
            )
        val filter = args.str("filter")?.let { f -> runCatching { Regex(f) }.getOrElse { return ToolResult.error("Invalid filter: ${it.message?.lineSequence()?.firstOrNull()}") } }
        val log = linux.serviceLog(name, LOG_WINDOW).orEmpty()
        return ToolResult(describeService(status) + "\n" + lastLines(log, (args.int("lines") ?: 100).coerceIn(1, 2000), filter))
    }

    private companion object {
        const val LOG_WINDOW = 512 * 1024
    }
}

/** The last [count] lines of [log] (those matching [filter], if given), capped in size. */
internal fun lastLines(log: String, count: Int, filter: Regex?, maxChars: Int = 30_000): String {
    if (log.isBlank()) return "(no output yet)"
    val lines = log.lines().dropLastWhile { it.isEmpty() }.let { all -> if (filter == null) all else all.filter { filter.containsMatchIn(it) } }
    if (lines.isEmpty()) return "(no lines match the filter)"
    val picked = lines.takeLast(count)
    val text = picked.joinToString("\n").takeLast(maxChars)
    val skipped = lines.size - picked.size
    return (if (skipped > 0) "[${skipped} earlier lines not shown]\n" else "") + text
}

private class GitInspect(private val linux: LinuxEnvironment, private val ws: Workspace) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "git_inspect",
        "Look at a git repository in the workspace without changing it (runs git in Linux): " +
            "status (changed files and branch), diff (unstaged changes, or staged ones, or against a ref), log (recent commits), show (one commit), branches, blame (who changed each line of a file). " +
            "Use it to review your own changes before saying a task is done. To commit, branch or push, use run_command, and only when the user asks.",
        ObjectSchema()
            .string("action", "status, diff, log, show, branches or blame.")
            .string("repo", "The repository's folder, relative to the workspace root. Defaults to the root.", required = false)
            .string("path", "Limit to this file or folder, relative to the repository. Required for blame.", required = false)
            .string("ref", "A commit, branch or range, e.g. HEAD~3, main, abc123 or main..HEAD. For show, defaults to HEAD.", required = false)
            .boolean("staged", "For diff: show staged changes instead of unstaged ones. Defaults to false.")
            .integer("max_count", "For log: how many commits. Defaults to 20.")
            .build(),
    )

    override fun describe(args: JsonObject) = "git ${args.str("action")}${args.str("path")?.let { " $it" }.orEmpty()}"

    override suspend fun execute(args: JsonObject): ToolResult {
        linuxNotReadyMessage(linux.status.value)?.let { return ToolResult.error(it) }
        val repo = ws.existing(args.str("repo") ?: ".")
        if (!repo.isDirectory) throw ToolException("'${args.str("repo")}' is a file; give the repository's folder.")
        val path = args.str("path")?.let { p ->
            val file = ws.resolve(if (ws.relative(repo) == ".") p else "${ws.relative(repo)}/$p")
            file.relativeTo(repo.canonicalFile).path.ifEmpty { "." }
        }
        val command = gitCommand(args.str("action").orEmpty(), path, args.str("ref"), args.bool("staged") == true, args.int("max_count"))
        val rel = ws.relative(repo)
        val result = linux.run(command, cwd = if (rel == ".") "/workspace" else "/workspace/$rel", timeoutMs = 60_000)
        val body = result.output.ifBlank { if (result.exitCode == 0) "(nothing to show)" else "(no output)" }
        return when {
            result.timedOut -> ToolResult.error("git took too long and was stopped.\n$body")
            result.exitCode == 0 -> ToolResult(body)
            else -> ToolResult.error("git failed (exit code ${result.exitCode}).\n$body")
        }
    }
}

private val SAFE_REF = Regex("^[A-Za-z0-9_./~^@{}:+-]+$")

/**
 * The shell command for one [GitInspect] action. Every value the model gives is checked or quoted, and
 * repository settings that could run a program (fsmonitor, external diff, textconv) are switched off,
 * so looking at a cloned repository can't run code from it.
 */
internal fun gitCommand(action: String, path: String?, ref: String?, staged: Boolean, maxCount: Int?): String {
    if (ref != null && (ref.startsWith("-") || !SAFE_REF.matches(ref))) throw ToolException("'$ref' isn't a valid ref.")
    if (path != null && path.startsWith("-")) throw ToolException("'$path' isn't a valid path.")
    val pathArg = path?.let { " -- ${quote(it)}" }.orEmpty()
    val refArg = ref?.let { " ${quote(it)}" }.orEmpty()
    val git = "git -c safe.directory='*' -c core.fsmonitor=false -c core.quotepath=off -c color.ui=never --no-pager"
    val body = when (action.trim().lowercase()) {
        "status" -> "$git status --short --branch$pathArg"
        "diff" -> {
            val opts = "--no-ext-diff --no-textconv${if (staged) " --staged" else ""}$refArg$pathArg"
            "$git diff --stat $opts && echo && $git diff $opts"
        }
        "log" -> "$git log --no-decorate --format='%h %ad %an  %s' --date=short -n ${(maxCount ?: 20).coerceIn(1, 200)}$refArg$pathArg"
        "show" -> "$git show --stat --patch --no-ext-diff --no-textconv ${quote(ref ?: "HEAD")}$pathArg"
        "branches" -> "$git branch -a -vv"
        "blame" -> {
            if (path == null) throw ToolException("blame needs a path.")
            "$git blame --date=short$refArg$pathArg"
        }
        else -> throw ToolException("action must be status, diff, log, show, branches or blame.")
    }
    return "command -v git >/dev/null 2>&1 || { echo \"git isn't installed in Linux yet: run 'apk add git' with run_command.\"; exit 127; }; $body"
}

private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
