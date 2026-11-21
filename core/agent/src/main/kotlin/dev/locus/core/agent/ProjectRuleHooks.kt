package dev.locus.core.agent

import dev.locus.core.llm.LlmJson
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.ProjectRule
import dev.locus.core.model.ProjectRules
import dev.locus.core.model.Role
import dev.locus.core.model.RuleNote
import dev.locus.core.model.RuleTrigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A project's rules for one run (docs/design/rules.md). The user sets them in the project's settings;
 * the agent has no tool to see or change them. The run reads [rules] fresh at each hook, so a change
 * the user makes mid-run applies from the next one.
 */
interface ProjectRuleHooks {
    /** The project's rules in list order; inactive ones (off or suspended) are filtered out by the caller. */
    suspend fun rules(): List<ProjectRule>

    /** Whether rules that run commands can run now: Linux is installed and ready. Block rules don't need it. */
    suspend fun canRun(): Boolean

    /** Runs [rule]'s command in the project's Linux workspace, with [files] in [ProjectRules.FILES_VAR]. */
    suspend fun run(rule: ProjectRule, files: List<String>): RuleOutcome
}

/** How one rule's command ended: [exitCode] null when it timed out; [output] both streams, already scrubbed. */
data class RuleOutcome(val exitCode: Int?, val output: String, val durationMs: Long) {
    val passed: Boolean get() = exitCode == 0
}

/** One rule that ran, and the note parts it leaves for the agent. */
class RuleRun(val rule: ProjectRule, val files: List<String>, val outcome: RuleOutcome, val header: String) {
    /** The output part: bounded, marked as untrusted, saying whether it's a local command's ([RuleNote.LOCAL_OUTPUT]). */
    val result: String get() = ModeNote.result(UntrustedContent.wrap(RuleHooks.bound(outcome.output).ifBlank { "(no output)" }, UntrustedContent.note(source(rule))))

    val parts: List<Part> get() = listOf(Part.Text(ModeNote.wrap(header)), Part.Text(result))

    /** For taint: the label, and whether its output counts as the workspace's own ([Taint.isLocalOutput]'s rule). */
    val taintLabel: String get() = RuleHooks.taintLabel(rule.name)
    val local: Boolean get() = RuleHooks.isLocal(rule.command)

    private fun source(rule: ProjectRule) = if (RuleHooks.isLocal(rule.command)) RuleNote.LOCAL_OUTPUT else RuleNote.FETCH_OUTPUT
}

/** What the agent loop does with [ProjectRuleHooks]: which rules fire, their notes and their Activity entries. */
object RuleHooks {
    /** The most of a rule's output a note keeps: its start and, mostly, its end (where errors are). */
    const val MAX_OUTPUT = 4_000
    private const val HEAD = 1_000

    fun bound(output: String): String {
        if (output.length <= MAX_OUTPUT) return output
        val tail = MAX_OUTPUT - HEAD
        return output.take(HEAD) + "\n[… ${output.length - MAX_OUTPUT} characters cut …]\n" + output.takeLast(tail)
    }

    fun isLocal(command: String): Boolean = !CommandSafety.mayFetch(command)

    fun taintLabel(name: String) = "a project rule ($name)"

    /**
     * Why a BEFORE_COMMAND rule blocks this call, or null. Only `run_command` and `linux_service_start`
     * are checked, against their `command` argument.
     */
    fun blockReason(rules: List<ProjectRule>, tool: String, args: JsonObject): String? {
        if (tool !in ProjectRules.COMMAND_TOOLS) return null
        val command = (args["command"] as? JsonPrimitive)?.contentOrNull ?: return null
        val rule = rules.firstOrNull { it.active && it.trigger == RuleTrigger.BEFORE_COMMAND && it.matchesCommand(command) } ?: return null
        val name = rule.name.replace("”", "'").replace("“", "'").replace('\n', ' ')
        val message = rule.message.trim().replace('\n', ' ').take(ProjectRule.MAX_TEXT)
        return "Not allowed: the project rule “$name” blocks this command: $message. This rule is the user's; don't retry the command or work " +
            "around it. Continue without it, or ask the user."
    }

    /**
     * The paths of [calls] (this step's) that edited files successfully: write_file, edit_file and multi_edit
     * that ran ([ran]) and didn't fail. A call_tool counts as the tool it ran.
     */
    fun editedPaths(calls: List<Part.ToolCall>, results: List<Part.ToolResult>, ran: (Part.ToolResult) -> Boolean): List<String> {
        val byId = results.associateBy { it.callId }
        return calls.mapNotNull { c ->
            val r = byId[c.id] ?: return@mapNotNull null
            if (r.isError || !ran(r)) return@mapNotNull null
            val (name, args) = DiscoveryTools.unwrap(c.name, c.argumentsJson)
            if (name !in ProjectRules.EDIT_TOOLS) return@mapNotNull null
            val path = runCatching { (LlmJson.parseToJsonElement(args) as? JsonObject)?.get("path") as? JsonPrimitive }.getOrNull()?.contentOrNull
            path?.let(ProjectRules::normalizePath)
        }.distinct()
    }

    /** The active AFTER_EDIT rules that cover any of [paths], each with the paths it covers. */
    fun afterEdit(rules: List<ProjectRule>, paths: List<String>): List<Pair<ProjectRule, List<String>>> {
        if (paths.isEmpty()) return emptyList()
        return rules.filter { it.active && it.trigger == RuleTrigger.AFTER_EDIT }
            .mapNotNull { r -> paths.filter(r::matchesFile).takeIf { it.isNotEmpty() }?.let { r to it } }
    }

    /** The active BEFORE_FINISH rules. */
    fun beforeFinish(rules: List<ProjectRule>): List<ProjectRule> = rules.filter { it.active && it.trigger == RuleTrigger.BEFORE_FINISH }

    /** Tools whose successful call in a task makes BEFORE_FINISH rules fire: it changed or may have changed files. */
    private val WORK = CompletionCheck.EDIT_TOOLS + setOf("run_command", "linux_service_start", "git_commit") +
        setOf(dev.locus.core.model.SubagentTools.SPAWN, dev.locus.core.model.SubagentTools.SEND)

    /** Whether [task] (the messages of the current task) did something BEFORE_FINISH rules should check. */
    fun taskDidWork(task: List<Message>): Boolean {
        val results = task.asSequence().filter { it.role == Role.TOOL }.flatMap { it.toolResults }.associateBy { it.callId }
        return task.asSequence().filter { it.role == Role.ASSISTANT }.flatMap { it.toolCalls }.any { c ->
            val r = results[c.id] ?: return@any false
            !r.isError && DiscoveryTools.unwrap(c.name, c.argumentsJson).first in WORK
        }
    }

    /** The Activity entry's arguments for a rule run. */
    fun entryArgs(rule: ProjectRule, files: List<String>): String = buildJsonObject {
        put("rule", rule.name)
        put("trigger", rule.trigger.wire)
        put("command", rule.command)
        if (files.isNotEmpty()) putJsonArray("files") { files.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    /** The Activity entry's result line. */
    fun entryResult(outcome: RuleOutcome): String =
        (if (outcome.exitCode == null) "Timed out" else "Exit code ${outcome.exitCode}") + "\n" + outcome.output
}
