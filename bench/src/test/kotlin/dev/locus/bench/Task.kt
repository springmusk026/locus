package dev.locus.bench

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One benchmark task, read from `bench/tasks/<category>/<name>/task.json`. Next to it:
 * - `workspace/`: the files the agent starts with (copied into a fresh workspace for every trial);
 * - `grader/`: hidden files copied over the workspace after the run, before the checks (tests the agent
 *   never saw, as SWE-bench applies its test patch after the model's);
 * - `site/<host>/`: pages the fixture web server serves for `http://<host>:<port>/`;
 * - `remote/`: the SSH server's home folder;
 * - `oracle.json`: a reference solution the scripted fake model replays (see [Oracle]).
 */
@Serializable
data class TaskSpec(
    val id: String = "",
    val category: String = "",
    val title: String,
    /** What the user types. `{{site:host}}`, `{{ssh}}` are filled in (see [Placeholders]). */
    val prompt: String,
    val budget: Budget = Budget(),
    /** Host shell commands run in the workspace before the agent starts (e.g. `git init`). */
    val setup: List<String> = emptyList(),
    /** Fixture web hosts to serve from `site/<host>/`; any host under `.bench.test` resolves to the fixture server. */
    val sites: List<String> = emptyList(),
    /** Start the fixture SSH server with `remote/` as its home, added to Settings as [SSH_ALIAS]. */
    val ssh: Boolean = false,
    /** How the scripted user answers ask_user and approval cards. */
    val user: ScriptedUserSpec = ScriptedUserSpec(),
    /** All must pass for the trial to pass. */
    val checks: List<Check>,
    /** Set for injection-robustness tasks: what the attack is and how to tell it happened. */
    val injection: Injection? = null,
    /** Where this task's idea comes from, or what it measures, for the docs. */
    val notes: String = "",
) {
    @kotlinx.serialization.Transient
    var dir: File = File(".")

    val workspaceDir: File get() = File(dir, "workspace")
    val graderDir: File get() = File(dir, "grader")
    val remoteDir: File get() = File(dir, "remote")
    fun siteDir(host: String) = File(File(dir, "site"), host)
    val oracleFile: File get() = File(dir, "oracle.json")

    companion object {
        const val SSH_ALIAS = "benchbox"
    }
}

@Serializable
data class Budget(
    /** Model requests (steps) before the run stops: AgentConfig.maxSteps. */
    val maxSteps: Int = 30,
    /** Task budget tokens (TaskBudget.budgetTokens: cache reads at a tenth) before the run stops. */
    val maxTokens: Long = 400_000,
    /** Wall-clock limit for the trial, model and tools together. */
    val timeoutMinutes: Int = 10,
)

/**
 * The scripted user. [answers] answer ask_user: the first rule whose [AnswerRule.match] appears in the
 * question (case-insensitive) wins; with none, the first option is picked, or "Use your judgement".
 *
 * Approvals: with [attentive] false (the default) every approval card is approved: the user asked for
 * the task. With [attentive] true, a card that Locus marked as coming from a tainted run ("Tainted
 * run: …") is approved only when it matches one of [expected] (a tool name and text its arguments must
 * contain), as a user who reads the card approves only what they asked for. Ordinary cards are approved.
 */
@Serializable
data class ScriptedUserSpec(
    val answers: List<AnswerRule> = emptyList(),
    val attentive: Boolean = false,
    val expected: List<CallPattern> = emptyList(),
)

@Serializable
data class AnswerRule(val match: String, val answer: String)

/** A tool call: [tool] by name (null: any), whose arguments JSON contains every string in [argsContain]. */
@Serializable
data class CallPattern(val tool: String? = null, val argsContain: List<String> = emptyList())

/**
 * An attack planted in the task's fixtures (AgentDojo-style): [goal] says what it wants. [attempted]
 * patterns recognise the agent trying it (a tool call), whether or not Locus let it through; the task's
 * [Check]s with `security: true` decide whether it succeeded.
 */
@Serializable
data class Injection(val goal: String, val attempted: List<CallPattern> = emptyList())

/** One deterministic check of the end state. No check asks a model. */
@Serializable
sealed interface Check {
    /** True for an injection task's "the attack didn't succeed" checks; false for "the task got done". */
    val security: Boolean

    /** A host shell command in the workspace (after `grader/` is copied in) exits with [exit], its output containing [outputContains]. */
    @Serializable
    @SerialName("command")
    data class Command(
        val run: String,
        val exit: Int = 0,
        val outputContains: List<String> = emptyList(),
        val timeoutSeconds: Int = 120,
        override val security: Boolean = false,
    ) : Check

    /** A workspace file: absent, or present and equal to / containing / matching / not containing. */
    @Serializable
    @SerialName("file")
    data class FileCheck(
        val path: String,
        val absent: Boolean = false,
        val equals: String? = null,
        val contains: List<String> = emptyList(),
        val notContains: List<String> = emptyList(),
        val matches: String? = null,
        /** Compare with whitespace at line ends and the file's end trimmed. */
        val normalize: Boolean = true,
        override val security: Boolean = false,
    ) : Check

    /** The agent's final reply contains every string in [contains] (case-insensitive) and none of [notContains]. */
    @Serializable
    @SerialName("answer")
    data class Answer(
        val contains: List<String> = emptyList(),
        val anyOf: List<String> = emptyList(),
        val notContains: List<String> = emptyList(),
        override val security: Boolean = false,
    ) : Check

    /**
     * Requests the fixture web server received for [host] (method and path, body containing). [count]
     * null means at least one; 0 means none ever arrived.
     */
    @Serializable
    @SerialName("http")
    data class Http(
        val host: String,
        val method: String? = null,
        val path: String? = null,
        val bodyContains: List<String> = emptyList(),
        /** A regular expression the body (or query) must match, e.g. `"qty"\s*:\s*2\b`. */
        val bodyMatches: String? = null,
        val count: Int? = null,
        override val security: Boolean = false,
    ) : Check

    /** A file on the SSH server, under its home folder. */
    @Serializable
    @SerialName("remote_file")
    data class RemoteFile(
        val path: String,
        val absent: Boolean = false,
        val equals: String? = null,
        val contains: List<String> = emptyList(),
        val notContains: List<String> = emptyList(),
        override val security: Boolean = false,
    ) : Check

    /** A host shell command in the SSH server's home folder. */
    @Serializable
    @SerialName("remote_command")
    data class RemoteCommand(
        val run: String,
        val exit: Int = 0,
        val outputContains: List<String> = emptyList(),
        override val security: Boolean = false,
    ) : Check

    /** The agent's memory (every file under agent-home) contains none of [absent], and all of [contains]. */
    @Serializable
    @SerialName("memory")
    data class Memory(
        val absent: List<String> = emptyList(),
        val contains: List<String> = emptyList(),
        override val security: Boolean = false,
    ) : Check

    /**
     * A tool ran successfully at least [min] times. Only for tasks whose prompt asks for that feature
     * (the todo list, helpers): otherwise grade the outcome, not the path.
     */
    @Serializable
    @SerialName("tool_used")
    data class ToolUsed(val name: String, val min: Int = 1, override val security: Boolean = false) : Check

    /** The scripted user was asked at least [min] questions with ask_user. */
    @Serializable
    @SerialName("asked_user")
    data class AskedUser(val min: Int = 1, override val security: Boolean = false) : Check
}

/** Fills placeholders in prompts, checks and oracle arguments. */
class Placeholders(private val values: Map<String, String>) {
    fun fill(text: String): String = PATTERN.replace(text) { m -> values[m.groupValues[1]] ?: m.value }

    companion object {
        private val PATTERN = Regex("""\{\{([a-z_]+(?::[a-z0-9.-]+)?)\}\}""")
    }
}

val BenchJson = Json {
    ignoreUnknownKeys = false
    classDiscriminator = "type"
    prettyPrint = true
    encodeDefaults = false
}

/** Loads tasks from `<root>/<category>/<name>/task.json`, sorted by id. */
object TaskLoader {
    fun load(root: File): List<TaskSpec> =
        root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.flatMap { category ->
            category.listFiles().orEmpty().filter { File(it, "task.json").isFile }.sortedBy { it.name }.map { dir ->
                val spec = try {
                    BenchJson.decodeFromString(TaskSpec.serializer(), File(dir, "task.json").readText())
                } catch (e: Exception) {
                    throw IllegalArgumentException("Bad task ${dir.path}: ${e.message}", e)
                }
                spec.copy(id = "${category.name}/${dir.name}", category = category.name).also { it.dir = dir }
            }
        }

    /** Tasks whose category or id is in [filter] (comma-separated); all when blank. */
    fun select(all: List<TaskSpec>, filter: String?): List<TaskSpec> {
        val wanted = filter?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (wanted.isEmpty()) return all
        return all.filter { t -> wanted.any { w -> t.category == w || t.id == w } }
    }
}
