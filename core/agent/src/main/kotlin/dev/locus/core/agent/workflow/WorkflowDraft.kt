package dev.locus.core.agent.workflow

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.ReceiptCheck
import dev.locus.core.model.Role
import dev.locus.core.model.Verification
import dev.locus.core.model.Workflow
import dev.locus.core.model.WorkflowCheck
import dev.locus.core.model.WorkflowCheck.CheckKind
import dev.locus.core.model.WorkflowParam
import dev.locus.core.model.WorkflowParamType
import dev.locus.core.model.WorkflowSource
import dev.locus.core.model.toolLabel
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Drafts a workflow from a finished task, for the user to review before it's saved.
 *
 * Split by trust: what the task's record says for certain (the tools it used, the calls the user had
 * to approve, the checks that passed) is taken from the record; what needs judgement (a name, which
 * parts of the request are inputs, the steps in words, the expected output) comes from one cheap
 * helper-model request. Without a helper model, or when its reply can't be used, [fallback] fills
 * those from the record too.
 */
object WorkflowDraft {

    /** One call of the task and how it was let through. */
    data class Call(val name: String, val argumentsJson: String, val decision: ApprovalDecision?, val isError: Boolean)

    /** What the draft is made from: one task of a chat. */
    data class Trace(
        val request: String,
        val calls: List<Call>,
        val checks: List<ReceiptCheck>,
        val reply: String?,
        val changed: List<String> = emptyList(),
        val conversationId: String? = null,
        val taskId: String? = null,
    ) {
        companion object {
            /** The trace of the task whose messages are [messages] (the task's span on the shown branch). */
            fun of(messages: List<Message>, checks: List<ReceiptCheck>, changed: List<String>, conversationId: String?, taskId: String?): Trace {
                val results = messages.filter { it.role == Role.TOOL }.flatMap { it.toolResults }.associateBy { it.callId }
                val request = messages.filter { it.role == Role.USER && !ModeNote.isAgentNote(it) }
                    .map { m -> WorkflowRender.planIn(m)?.goal ?: m.userText }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                val calls = messages.filter { it.role == Role.ASSISTANT }.flatMap { it.toolCalls }.map { c ->
                    val r = results[c.id]
                    Call(c.name, c.argumentsJson, r?.decision, r?.isError ?: false)
                }
                val reply = messages.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.text
                return Trace(request, calls, checks, reply, changed, conversationId, taskId)
            }
        }
    }

    private val RAN = setOf(ApprovalDecision.AUTO_ALLOWED, ApprovalDecision.USER_APPROVED, ApprovalDecision.REVIEWER_ALLOWED)

    /** The capability keys of calls that ran, in first-use order. */
    fun tools(trace: Trace): List<String> = trace.calls.filter { it.decision in RAN }.map { key(it) }.distinct()

    /** Calls the user approved: they stay approval points. */
    fun approvals(trace: Trace): List<String> = trace.calls.filter { it.decision == ApprovalDecision.USER_APPROVED }.map { key(it) }.distinct()

    /** A check for each kind of verification whose last run passed (a failing one isn't a success criterion yet). */
    fun checks(trace: Trace): List<WorkflowCheck> = Verification.Kind.entries.mapNotNull { kind ->
        val last = trace.checks.lastOrNull { it.kind == kind } ?: return@mapNotNull null
        if (last.passed != true) return@mapNotNull null
        when (kind) {
            Verification.Kind.TEST -> WorkflowCheck(CheckKind.TESTS_PASS, "Tests pass")
            Verification.Kind.BUILD -> WorkflowCheck(CheckKind.BUILD_PASS, "Build passes")
            Verification.Kind.CHECK -> WorkflowCheck(CheckKind.CHECK_PASS, "Checks pass")
        }
    }

    private fun key(c: Call): String {
        val args = runCatching { Json.parseToJsonElement(c.argumentsJson) as JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return WorkflowScope.key(c.name, args)
    }

    // ---- The helper request -------------------------------------------------------------------

    /** The prompt for the helper model: the request, the steps as one-line labels, the result. */
    fun prompt(trace: Trace): String = buildString {
        appendLine("A user's task with an AI agent went well, and the user wants to save it as a reusable workflow they can run again with different inputs.")
        appendLine("Turn it into a JSON object with exactly these keys:")
        appendLine("""- "name": 2 to 5 words naming what it does""")
        appendLine("""- "description": one sentence""")
        appendLine("""- "goal": the request rewritten as a reusable instruction, with each input as a {slot}, e.g. "Run the tests in {folder} and fix any failures" """)
        appendLine("""- "parameters": the inputs, each {"name": slot in lowercase_with_underscores, "label": short label, "type": one of text, url, path, number, boolean, choice, "default": the value from this task, "description": one line, "options": [only for choice]}. Only things that would change between runs (addresses, folders, names, numbers); at most 6""")
        appendLine("""- "steps": 2 to 8 short imperative steps that generalise what the agent did, using the same {slots}; describe intent ("Fix failing tests"), not exact commands unless the command is the point""")
        appendLine("""- "expected_output": what a good final answer gives the user, one line""")
        appendLine("""- "checks": 0 to 3 extra success checks that a person could confirm from the final answer, each a short label (tests and builds are added automatically)""")
        appendLine("Reply with the JSON only.")
        appendLine()
        appendLine("<request>")
        appendLine(trace.request.take(MAX_REQUEST))
        appendLine("</request>")
        appendLine("<steps>")
        trace.calls.take(MAX_CALLS).forEach { c ->
            val outcome = when {
                c.decision == ApprovalDecision.USER_APPROVED -> " (user approved)"
                c.decision == null || c.decision !in RAN -> " (not run)"
                c.isError -> " (failed)"
                else -> ""
            }
            appendLine("- " + toolLabel(c.name, c.argumentsJson).take(160) + outcome)
        }
        if (trace.calls.size > MAX_CALLS) appendLine("- … ${trace.calls.size - MAX_CALLS} more")
        appendLine("</steps>")
        trace.reply?.let { appendLine("<final_answer>").appendLine(it.take(MAX_REPLY)).appendLine("</final_answer>") }
    }

    @Serializable
    private data class Reply(
        val name: String = "",
        val description: String = "",
        val goal: String = "",
        val parameters: List<ReplyParam> = emptyList(),
        val steps: List<String> = emptyList(),
        @SerialName("expected_output") val expectedOutput: String = "",
        val checks: List<String> = emptyList(),
    )

    @Serializable
    private data class ReplyParam(
        val name: String = "",
        val label: String = "",
        val type: String = "text",
        val default: String? = null,
        val description: String = "",
        val options: List<String> = emptyList(),
    )

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * The draft from the helper's [raw] reply, with the record's tools, approvals and checks; the
     * [fallback] when the reply isn't usable JSON. Parameters whose slot appears nowhere are dropped,
     * and a goal that lost the request is replaced by it.
     */
    fun draft(trace: Trace, raw: String?, now: Long = 0): Workflow {
        val base = fallback(trace, now)
        val reply = raw?.let(::jsonObject)?.let { runCatching { lenient.decodeFromString(Reply.serializer(), it) }.getOrNull() } ?: return base
        val goal = reply.goal.trim().ifBlank { return base }
        val steps = reply.steps.map { it.trim() }.filter { it.isNotEmpty() }.take(Workflow.MAX_STEPS).ifEmpty { base.steps }
        val text = (listOf(goal) + steps).joinToString("\n")
        val params = reply.parameters.mapNotNull { p ->
            val name = p.name.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
            if (!WorkflowParam.NAME.matches(name) || "{$name}" !in text) return@mapNotNull null
            val type = WorkflowParamType.entries.firstOrNull { it.name.equals(p.type.trim(), ignoreCase = true) } ?: WorkflowParamType.TEXT
            WorkflowParam(
                name = name,
                label = p.label.trim().take(40),
                type = if (type == WorkflowParamType.CHOICE && p.options.isEmpty()) WorkflowParamType.TEXT else type,
                default = p.default?.trim()?.takeIf { it.isNotEmpty() },
                description = p.description.trim().take(200),
                options = if (type == WorkflowParamType.CHOICE) p.options.map { it.trim() }.filter { it.isNotEmpty() }.distinct() else emptyList(),
            )
        }.distinctBy { it.name }.take(Workflow.MAX_PARAMS)
        // A slot left without a parameter would be sent as typed: put the default value back.
        val known = params.map { it.name }.toSet()
        val orphan = Regex("""\{([a-z][a-z0-9_]{0,31})\}""")
        fun clean(s: String) = orphan.replace(s) { m -> if (m.groupValues[1] in known) m.value else m.groupValues[1].replace('_', ' ') }
        val name = reply.name.trim().take(60).ifBlank { base.name }
        return base.copy(
            id = idFor(name),
            name = name,
            description = reply.description.trim().take(300),
            goal = clean(goal),
            parameters = params,
            steps = steps.map(::clean),
            expectedOutput = reply.expectedOutput.trim().take(300),
            checks = base.checks + reply.checks.map { it.trim() }.filter { it.isNotEmpty() }.take(3).map { WorkflowCheck(CheckKind.AGENT, it.take(120)) },
        )
    }

    private val URL = Regex("""https?://[^\s<>"')\]]+""")

    /**
     * A draft from the record alone: the request as the goal with each web address as a `{url}`
     * input, one step per kind of thing the agent did, the record's tools, approvals and checks.
     */
    fun fallback(trace: Trace, now: Long = 0): Workflow {
        val urls = URL.findAll(trace.request).map { it.value.trimEnd('.', ',', ';') }.distinct().take(3).toList()
        val params = urls.mapIndexed { i, u ->
            WorkflowParam(if (urls.size == 1) "url" else "url_${i + 1}", if (urls.size == 1) "Address" else "Address ${i + 1}", WorkflowParamType.URL, default = u)
        }
        var goal = trace.request.trim()
        params.forEach { p -> goal = goal.replace(p.default!!, "{${p.name}}") }
        val steps = trace.calls.filter { it.decision in RAN && !it.isError }
            .map { toolLabel(it.name, it.argumentsJson).take(120) }
            .fold(mutableListOf<String>()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }
            .take(Workflow.MAX_STEPS)
        val name = trace.request.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.split(Regex("\\s+"))?.take(5)?.joinToString(" ")?.take(60)
            ?.ifBlank { null } ?: "Saved task"
        return Workflow(
            id = idFor(name),
            name = name,
            goal = goal,
            parameters = params,
            steps = steps,
            tools = tools(trace),
            approvals = approvals(trace),
            checks = checks(trace),
            source = WorkflowSource(trace.conversationId, trace.taskId),
            createdAt = now,
            updatedAt = now,
        )
    }

    /** A file-safe id for [name]. */
    fun idFor(name: String): String = AgentHome.slug(name).take(64).trim('-').ifEmpty { "workflow" }

    /** The first {...} object in a reply that may wrap it in prose or a code fence. */
    private fun jsonObject(raw: String): String? {
        val start = raw.indexOf('{').takeIf { it >= 0 } ?: return null
        val end = raw.lastIndexOf('}').takeIf { it > start } ?: return null
        return raw.substring(start, end + 1)
    }

    private const val MAX_REQUEST = 4_000
    private const val MAX_CALLS = 40
    private const val MAX_REPLY = 1_500
}
