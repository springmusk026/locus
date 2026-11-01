package dev.locus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A reusable workflow: a task that went well, kept as a plan the agent follows again with new inputs
 * (see docs/design/workflows.md). It's agent-guided, not a recording: [steps] are what to do, not
 * calls to replay, and the agent adapts them to what it finds.
 *
 * Stored as JSON in agent-home (`workflows/<id>.json`, or a project's `projects/<id>/workflows/`).
 * [schema] is the file format's version: fields may be added with defaults, never renamed.
 */
@Serializable
data class Workflow(
    val schema: Int = SCHEMA,
    /** File name and handle: 1–64 lowercase letters, digits and single hyphens, like a skill's name. */
    val id: String,
    val name: String,
    val description: String = "",
    /** What to do, as the user would ask it, with `{param}` slots. Becomes the visible user message. */
    val goal: String,
    val parameters: List<WorkflowParam> = emptyList(),
    /** The plan, in order; may use `{param}` slots. */
    val steps: List<String> = emptyList(),
    /**
     * The tools the workflow may use without extra approval ([WorkflowCapability] keys: a tool name, or
     * `connector:<id>` for a connector or MCP server). Anything else that changes something asks first.
     * Empty: no limit beyond the user's own settings.
     */
    val tools: List<String> = emptyList(),
    /** Capability keys that always ask, even when the user's settings allow them: they needed approval when this was recorded. */
    val approvals: List<String> = emptyList(),
    val checks: List<WorkflowCheck> = emptyList(),
    /** What a good run ends with ("A link to the deployed site"). */
    val expectedOutput: String = "",
    /** The task it was saved from, for reference; never needed to run it. */
    val source: WorkflowSource? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        const val SCHEMA = 1
        val ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        const val MAX_PARAMS = 12
        const val MAX_STEPS = 20
    }
}

/** Where a workflow came from. */
@Serializable
data class WorkflowSource(val conversationId: String? = null, val taskId: String? = null)

/** One input. Types follow GitHub Actions' `workflow_dispatch` inputs, plus URL and path, which agents use most. */
@Serializable
data class WorkflowParam(
    /** The slot name: `{name}` in the goal and steps. Lowercase letters, digits and underscores. */
    val name: String,
    val label: String = "",
    val type: WorkflowParamType = WorkflowParamType.TEXT,
    val default: String? = null,
    val required: Boolean = true,
    val description: String = "",
    /** For [WorkflowParamType.CHOICE]. */
    val options: List<String> = emptyList(),
) {
    val title: String get() = label.ifBlank { name.replace('_', ' ').replaceFirstChar { it.uppercase() } }

    companion object {
        val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}

@Serializable
enum class WorkflowParamType {
    @SerialName("text") TEXT,
    @SerialName("url") URL,
    @SerialName("path") PATH,
    @SerialName("number") NUMBER,
    @SerialName("boolean") BOOLEAN,
    @SerialName("choice") CHOICE,
}

/**
 * How to tell a run worked. [kind] decides who checks it: Locus itself for the build, test and lint
 * runs it recognises ([Verification]), files changed and the reply's text; the agent for [CheckKind.AGENT].
 */
@Serializable
data class WorkflowCheck(val kind: CheckKind, val label: String, val value: String = "") {
    @Serializable
    enum class CheckKind {
        /** The task's last test run passed. */
        @SerialName("tests_pass") TESTS_PASS,

        /** The task's last build passed. */
        @SerialName("build_pass") BUILD_PASS,

        /** The task's last lint or type check passed. */
        @SerialName("check_pass") CHECK_PASS,

        /** The task changed a file whose path ends with [value]. */
        @SerialName("file_changed") FILE_CHANGED,

        /** The final reply contains [value] (ignoring case). */
        @SerialName("reply_contains") REPLY_CONTAINS,

        /** Judged by the agent, which reports it at the end ("Check: … passed"). */
        @SerialName("agent") AGENT,
    }
}

/** A workflow and where it's kept: [projectId] null for one every chat can use. */
data class SavedWorkflow(val workflow: Workflow, val projectId: String? = null) {
    val key: String get() = (projectId?.let { "$it/" } ?: "") + workflow.id
}

/** One success check after a run: [passed] null when nothing in the task could tell. [note] says how it was judged. */
data class WorkflowCheckResult(val check: WorkflowCheck, val passed: Boolean?, val note: String)

/** A workflow run's line on its task receipt: which workflow, and how each check went. */
data class ReceiptWorkflow(val id: String, val name: String, val checks: List<WorkflowCheckResult>) {
    val passed: Int get() = checks.count { it.passed == true }
    val failed: Int get() = checks.count { it.passed == false }
}
