package dev.locus.core.model

import kotlinx.serialization.Serializable

/** When a [ProjectRule] fires (docs/design/rules.md). The trigger decides the action. */
@Serializable
enum class RuleTrigger(val wire: String) {
    /** After write_file, edit_file or multi_edit succeeded on a matching path: runs [ProjectRule.command]. */
    AFTER_EDIT("after_edit"),

    /** When the agent is about to finish a task: runs [ProjectRule.command]; a failure sends it back (bounded). */
    BEFORE_FINISH("before_finish"),

    /** Before run_command (or linux_service_start) with a matching command: blocks it with [ProjectRule.message]. */
    BEFORE_COMMAND("before_command"),
    ;

    val runsCommand: Boolean get() = this != BEFORE_COMMAND
}

/**
 * A rule the user set for a project (docs/design/rules.md): after an edit or before finishing it runs
 * [command] in the project's Linux workspace; before a command it blocks matching commands with
 * [message]. Stored in the sealed settings ([AppSettings.projectRules], [PolicySeal]); only the UI
 * writes them, and no tool can.
 */
@Serializable
data class ProjectRule(
    val id: String,
    val name: String,
    val trigger: RuleTrigger,
    /** AFTER_EDIT: file globs, comma separated (empty: any file). BEFORE_COMMAND: the command pattern (empty: any command). */
    val pattern: String = "",
    /** The shell command, for the triggers that run one. */
    val command: String = "",
    val timeoutSeconds: Int = DEFAULT_TIMEOUT,
    /** BEFORE_COMMAND: what the agent is told when the rule blocks a command. */
    val message: String = "",
    /** The user's own switch. */
    val enabled: Boolean = true,
    /** Set when the settings' seal failed: the rule does nothing until the user looks at it and resumes it. */
    val suspended: Boolean = false,
    val createdAt: Long = 0,
) {
    /** In force now: switched on and not suspended. */
    val active: Boolean get() = enabled && !suspended

    /** The rule's timeout, kept within [MIN_TIMEOUT]..[MAX_TIMEOUT]. */
    val timeout: Int get() = timeoutSeconds.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)

    /** The globs of an AFTER_EDIT rule; empty means every file. */
    val globs: List<String> get() = pattern.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Whether this AFTER_EDIT rule covers [path] (a path from the project's folder, see [ProjectRules.normalizePath]). */
    fun matchesFile(path: String): Boolean {
        val clean = ProjectRules.normalizePath(path) ?: return false
        val globs = globs
        if (globs.isEmpty()) return true
        return globs.any { g ->
            val glob = g.removePrefix("./").removePrefix("/")
            if ('/' in glob) ProjectRules.glob(glob).matches(clean) else ProjectRules.glob(glob).matches(clean.substringAfterLast('/'))
        }
    }

    /** Whether this BEFORE_COMMAND rule covers [command]: its pattern anywhere in it, whitespace collapsed, `*` for any text. */
    fun matchesCommand(command: String): Boolean {
        val text = ProjectRules.collapse(command)
        val p = ProjectRules.collapse(pattern)
        if (p.isEmpty()) return true
        return ProjectRules.commandPattern(p).containsMatchIn(text)
    }

    /** Why this rule can't be saved as it is, or null; see [RuleProblem]. */
    fun problem(): RuleProblem? = when {
        name.isBlank() -> RuleProblem.NAME
        name.length > MAX_NAME -> RuleProblem.TOO_LONG
        trigger.runsCommand && command.isBlank() -> RuleProblem.COMMAND
        !trigger.runsCommand && message.isBlank() -> RuleProblem.MESSAGE
        command.length > MAX_TEXT || message.length > MAX_TEXT || pattern.length > MAX_TEXT -> RuleProblem.TOO_LONG
        trigger.runsCommand && timeoutSeconds !in MIN_TIMEOUT..MAX_TIMEOUT -> RuleProblem.TIMEOUT
        else -> null
    }

    companion object {
        const val DEFAULT_TIMEOUT = 60
        const val MIN_TIMEOUT = 5
        const val MAX_TIMEOUT = 600
        const val MAX_NAME = 60
        const val MAX_TEXT = 2_000

        /** Rules per project. */
        const val MAX_PER_PROJECT = 20
    }
}

/** What's wrong with a rule the user is editing. */
enum class RuleProblem { NAME, COMMAND, MESSAGE, TIMEOUT, TOO_LONG }

/** Matching helpers and the shape of rule notes, shared by the engine and the chat screen. */
object ProjectRules {
    /** The tools whose success fires AFTER_EDIT rules. */
    val EDIT_TOOLS = setOf("write_file", "edit_file", "multi_edit")

    /** The tools BEFORE_COMMAND rules check. */
    val COMMAND_TOOLS = setOf("run_command", "linux_service_start")

    /** Extra rounds a task gets from failing BEFORE_FINISH rules. */
    const val MAX_FINISH_ROUNDS = 2

    /** Activity's tool name for a rule run (not a tool). */
    const val ENTRY = "project_rule"

    /** The environment variable an AFTER_EDIT command reads its matching paths from, one per line. */
    const val FILES_VAR = "LOCUS_RULE_FILES"

    /** [path] relative to the project's folder: no `/workspace/`, `./` or leading `/`; null for an empty or `..` path. */
    fun normalizePath(path: String): String? {
        var p = path.trim().replace('\\', '/')
        if (p == "/workspace") return null
        p = p.removePrefix("/workspace/").removePrefix("./").trimStart('/')
        while (p.startsWith("./")) p = p.removePrefix("./")
        if (p.isEmpty() || p.split('/').any { it == ".." }) return null
        return p
    }

    /** A file glob as a regex: `**` any text, `*` any text without `/`, `?` one character but `/`. */
    fun glob(glob: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && glob.getOrNull(i + 1) == '*' -> {
                    // `**/` also matches no folder at all.
                    if (glob.getOrNull(i + 2) == '/') {
                        out.append("(?:.*/)?")
                        i += 3
                        continue
                    }
                    out.append(".*")
                    i += 2
                    continue
                }
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(out.toString())
    }

    /** A command pattern as a regex that may match anywhere: `*` any text, everything else literal. */
    fun commandPattern(pattern: String): Regex =
        Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })

    fun collapse(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    /** Single-quoted for `sh`. */
    fun shQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** The command a rule runs: [command] with [files] exported in [FILES_VAR] first (AFTER_EDIT). */
    fun commandLine(command: String, files: List<String>): String =
        if (files.isEmpty()) command else "$FILES_VAR=${shQuote(files.joinToString("\n"))}\nexport $FILES_VAR\n$command"
}

/**
 * The note a rule run leaves for the agent (docs/design/rules.md, "The note"): a header inside
 * [ModeNote.wrap], followed by its output inside [ModeNote.result]. The header is English, for the
 * model; [parse] reads it back for the chat screen and for taint.
 */
object RuleNote {
    private const val PREFIX = "Project rule “"
    private const val AFTER = "after an edit"
    private const val FINISH = "before finishing"

    /** In the untrusted marker's note when the command is one plain local command ([dev.locus.core.agent] decides). */
    const val LOCAL_OUTPUT = "output of a project rule's local command"
    const val FETCH_OUTPUT = "output of a project rule's command, which may have fetched from the network"

    /** How a rule run ended. [exitCode] null: it timed out after [seconds]. */
    data class Info(val name: String, val trigger: RuleTrigger, val exitCode: Int?, val passed: Boolean, val finalRound: Boolean, val nowPasses: Boolean)

    private val HEADER = Regex("^" + Regex.escape(PREFIX) + "([^”]*)” (after an edit|before finishing)( now passes)?: (?:exit code (-?\\d+)|timed out)")

    private fun clean(name: String) = name.replace("”", "'").replace("“", "'").replace('\n', ' ').take(ProjectRule.MAX_NAME)

    private fun status(exitCode: Int?, timeout: Int, durationMs: Long): String =
        if (exitCode == null) "timed out after $timeout s" else "exit code $exitCode (%.1f s)".format(java.util.Locale.ROOT, durationMs / 1000.0)

    /**
     * The header of one run. [round] counts BEFORE_FINISH failures in the task, this one included: the
     * agent's extra round it starts. [final] when no more are left (after [ProjectRules.MAX_FINISH_ROUNDS]).
     */
    fun header(rule: ProjectRule, exitCode: Int?, durationMs: Long, files: List<String>, round: Int = 0, final: Boolean = false): String = buildString {
        val passed = exitCode == 0
        append(PREFIX).append(clean(rule.name)).append("” ").append(if (rule.trigger == RuleTrigger.AFTER_EDIT) AFTER else FINISH).append(": ")
        append(status(exitCode, rule.timeout, durationMs)).append(". It ran `").append(rule.command.lineSequence().first().take(200))
        if (rule.command.lines().size > 1) append(" …")
        append('`')
        if (files.isNotEmpty()) {
            append(" on ").append(files.take(MAX_FILES).joinToString(", "))
            if (files.size > MAX_FILES) append(" and ${files.size - MAX_FILES} more")
        }
        append(". Its output is below.")
        when {
            rule.trigger == RuleTrigger.AFTER_EDIT && !passed ->
                append(" The user set this rule for the project; fix what it reports about your change.")
            rule.trigger == RuleTrigger.AFTER_EDIT -> Unit
            final -> append(
                " The user set this check to pass before a task is finished, and it still fails after ${ProjectRules.MAX_FINISH_ROUNDS} more tries. Locus won't send you back again in this task; the user can see it failed.",
            )
            else -> append(
                " The user set this check to pass before a task is finished. Fix what it reports, then finish; Locus runs it again when you end your turn " +
                    "(extra try $round of ${ProjectRules.MAX_FINISH_ROUNDS}). If it can't pass, say so plainly in your reply.",
            )
        }
    }

    /** The short note when a BEFORE_FINISH rule that failed earlier in the task passes. */
    fun nowPasses(rule: ProjectRule, durationMs: Long): String =
        PREFIX + clean(rule.name) + "” " + FINISH + " now passes: " + status(0, rule.timeout, durationMs) + "."

    /** What [text] (a note's text, with or without its [ModeNote] markers) says, or null when it isn't a rule's header. */
    fun parse(text: String): Info? {
        val body = text.trim().removePrefix("<locus_mode>")
        val m = HEADER.find(body) ?: return null
        val trigger = if (m.groupValues[2] == AFTER) RuleTrigger.AFTER_EDIT else RuleTrigger.BEFORE_FINISH
        val exit = m.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull()
        return Info(
            name = m.groupValues[1],
            trigger = trigger,
            exitCode = exit,
            passed = exit == 0,
            finalRound = "Locus won't send you back again" in body,
            nowPasses = m.groupValues[3].isNotEmpty(),
        )
    }

    /** Each rule run in [message], with its output part's text (null when it has none, as a "now passes" note). */
    fun runs(message: Message): List<Pair<Info, String?>> {
        if (message.role != Role.USER) return emptyList()
        val parts = message.parts.filterIsInstance<Part.Text>()
        return parts.mapIndexedNotNull { i, p ->
            if (!ModeNote.isNote(p.text)) return@mapIndexedNotNull null
            val info = parse(p.text) ?: return@mapIndexedNotNull null
            val output = parts.getOrNull(i + 1)?.text?.takeIf { ModeNote.isResult(it) }
            info to output
        }
    }

    /** A run's output part for the user: without the result and untrusted markers. */
    fun displayOutput(result: String): String =
        Envelopes.strip(result.trim().removePrefix("<locus_result>").removeSuffix("</locus_result>")).trim()

    /** BEFORE_FINISH failures in [messages] (a task): the rounds already used. */
    fun finishRounds(messages: List<Message>): Int =
        messages.count { m -> runs(m).any { (info, _) -> info.trigger == RuleTrigger.BEFORE_FINISH && !info.passed } }

    /** Names of BEFORE_FINISH rules that failed in [messages]. */
    fun failedBeforeFinish(messages: List<Message>): Set<String> =
        messages.flatMap { runs(it) }.filter { (info, _) -> info.trigger == RuleTrigger.BEFORE_FINISH && !info.passed }.map { it.first.name }.toSet()

    private const val MAX_FILES = 10
}
