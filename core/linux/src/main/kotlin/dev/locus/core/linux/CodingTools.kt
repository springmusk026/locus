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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Linux tools for coding work: a background service's log and git's view of a repository, which only
 * look and so run without asking, and a git commit, which asks like any change.
 */
fun codingLinuxTools(linux: LinuxEnvironment, ws: Workspace): List<Tool> = listOf(ServiceLog(linux), GitInspect(linux, ws), GitCommit(linux, ws))

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
 * git with the repository's code-running settings switched off: fsmonitor and hooks (a hooks folder
 * that doesn't exist), commit signing (whose program the repository could name), colour and paging.
 * `safe.directory` is needed under proot, where the workspace looks owned by someone else.
 */
private const val GIT = "git -c safe.directory='*' -c core.fsmonitor=false -c core.hooksPath=/dev/null -c commit.gpgSign=false " +
    "-c core.quotepath=off -c color.ui=never --no-pager"

/**
 * Checks before any git command: git is installed, and the repository's own config doesn't name a
 * filter program (clean, smudge or process), which git runs on the files it stages, diffs or checks
 * out and which can't be switched off from the command line. Such a repository is refused (exit 3);
 * the user's global config (e.g. Git LFS) is trusted.
 */
internal const val GIT_PRELUDE =
    "command -v git >/dev/null 2>&1 || { echo \"git isn't installed in Linux yet: run 'apk add git' with run_command.\"; exit 127; }; " +
        "if git -c safe.directory='*' config --local --includes --name-only --get-regexp '^filter\\..*\\.(clean|smudge|process)$' >/dev/null 2>&1; then " +
        "echo \"This repository's own config (.git/config) names a filter program, which git would run on its files:\"; " +
        "git -c safe.directory='*' config --local --includes --get-regexp '^filter\\.'; " +
        "echo \"Locus's git tools don't run programs a repository names. If the user trusts this repository, use run_command instead.\"; exit 3; fi; "

/**
 * The shell command for one [GitInspect] action. Every value the model gives is checked or quoted, and
 * repository settings that could run a program (fsmonitor, external diff, textconv, filters) are
 * switched off or refused, so looking at a cloned repository can't run code from it.
 */
internal fun gitCommand(action: String, path: String?, ref: String?, staged: Boolean, maxCount: Int?): String {
    if (ref != null && (ref.startsWith("-") || !SAFE_REF.matches(ref))) throw ToolException("'$ref' isn't a valid ref.")
    if (path != null && path.startsWith("-")) throw ToolException("'$path' isn't a valid path.")
    val pathArg = path?.let { " -- ${quote(it)}" }.orEmpty()
    val refArg = ref?.let { " ${quote(it)}" }.orEmpty()
    val body = when (action.trim().lowercase()) {
        "status" -> "$GIT status --short --branch$pathArg"
        "diff" -> {
            val opts = "--no-ext-diff --no-textconv${if (staged) " --staged" else ""}$refArg$pathArg"
            "$GIT diff --stat $opts && echo && $GIT diff $opts"
        }
        "log" -> "$GIT log --no-decorate --format='%h %ad %an  %s' --date=short -n ${(maxCount ?: 20).coerceIn(1, 200)}$refArg$pathArg"
        "show" -> "$GIT show --stat --patch --no-ext-diff --no-textconv ${quote(ref ?: "HEAD")}$pathArg"
        "branches" -> "$GIT branch -a -vv"
        "blame" -> {
            if (path == null) throw ToolException("blame needs a path.")
            "$GIT blame --date=short$refArg$pathArg"
        }
        else -> throw ToolException("action must be status, diff, log, show, branches or blame.")
    }
    return GIT_PRELUDE + body
}

/**
 * The shell command for [GitCommit]: stage [paths] (relative to the repository; null or empty for every
 * change, new files included) and commit them with [message], then show the commit. Hooks never run
 * and nothing is signed or pushed.
 */
internal fun gitCommitCommand(paths: List<String>?, message: String): String {
    if (message.isBlank()) throw ToolException("message is empty; say what the commit changes.")
    if (message.length > MAX_COMMIT_MESSAGE) throw ToolException("message is over $MAX_COMMIT_MESSAGE characters.")
    if ('\u0000' in message) throw ToolException("message can't contain a NUL character.")
    paths?.forEach { if (it.isBlank() || it.startsWith("-")) throw ToolException("'$it' isn't a valid path.") }
    val add = if (paths.isNullOrEmpty()) "$GIT add -A" else "$GIT add -A -- ${paths.joinToString(" ") { quote(it) }}"
    return GIT_PRELUDE + "$add && $GIT commit --no-verify -q -m ${quote(message.trim())} && " +
        "$GIT log -1 --stat --format='Committed %h on %D%n%n%B'"
}

private const val MAX_COMMIT_MESSAGE = 10_000

private class GitCommit(private val linux: LinuxEnvironment, private val ws: Workspace) : Tool {
    override val risk = ToolRisk.WRITE
    override val spec = ToolSpec(
        "git_commit",
        "Commit changes to a git repository in the workspace (runs git in Linux): stages the given paths, or every change when none are given, and commits them with the message. " +
            "Commit only when the user asks; review the changes with git_inspect first. It never pushes, and the repository's hooks don't run.",
        ObjectSchema()
            .string("message", "The commit message: a short summary line, then a blank line and details if needed.")
            .string("repo", "The repository's folder, relative to the workspace root. Defaults to the root.", required = false)
            .array("paths", "Files or folders to stage and commit, relative to the repository. Leave out to commit every change, new files included.", buildJsonObject { put("type", "string") }, required = false)
            .build(),
    )

    override fun describe(args: JsonObject): String {
        val paths = (args["paths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val what = if (paths.isEmpty()) "all changes" else paths.joinToString()
        return "Commit $what: “${args.str("message").orEmpty().lineSequence().firstOrNull().orEmpty().take(80)}”"
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        linuxNotReadyMessage(linux.status.value)?.let { return ToolResult.error(it) }
        val repo = ws.existing(args.str("repo") ?: ".")
        if (!repo.isDirectory) throw ToolException("'${args.str("repo")}' is a file; give the repository's folder.")
        val rel = ws.relative(repo)
        val paths = (args["paths"] as? JsonArray)?.map { element ->
            val p = (element as? JsonPrimitive)?.content ?: throw ToolException("paths must be a list of strings.")
            // Resolved through the workspace, so a path can't reach outside it.
            val file = ws.resolve(if (rel == ".") p else "$rel/$p")
            file.relativeTo(repo.canonicalFile).path.ifEmpty { "." }
        }
        val command = gitCommitCommand(paths, args.str("message").orEmpty())
        val result = linux.run(command, cwd = if (rel == ".") "/workspace" else "/workspace/$rel", timeoutMs = 120_000)
        val body = result.output.ifBlank { "(no output)" }
        return when {
            result.timedOut -> ToolResult.error("git took too long and was stopped.\n$body")
            result.exitCode == 0 -> ToolResult(body)
            "tell me who you are" in body || "user.email" in body -> ToolResult.error(
                "git doesn't know who to commit as.\n$body\nAsk the user which name and email to use, then set them with run_command " +
                    "(git config --global user.name \"…\" && git config --global user.email \"…\") and try again.",
            )
            "nothing to commit" in body || "nothing added to commit" in body -> ToolResult.error("Nothing to commit: there are no changes${if (paths.isNullOrEmpty()) "" else " in those paths"}.\n$body")
            else -> ToolResult.error("git failed (exit code ${result.exitCode}).\n$body")
        }
    }
}

private fun quote(s: String) = shellQuote(s)
