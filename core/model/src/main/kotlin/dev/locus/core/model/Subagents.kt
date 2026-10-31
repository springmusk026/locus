package dev.locus.core.model

/** Where a sub-agent (helper) got to. Stored by [wire] name, so values may be added but never renamed. */
enum class SubagentStatus(val wire: String, val label: String) {
    RUNNING("running", "Working"),
    COMPLETED("completed", "Done"),

    /** Stopped early (step limit, token budget, time limit) with what it had so far. */
    PARTIAL("partial", "Stopped early"),
    FAILED("failed", "Failed"),
    CANCELLED("cancelled", "Stopped"),

    /** Locus was closed while it was working. */
    INTERRUPTED("interrupted", "Interrupted"),
    ;

    val finished: Boolean get() = this != RUNNING

    companion object {
        fun parse(value: String?): SubagentStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** Names of the sub-agent tools; the chat renders `agent_spawn` as a nested helper row. */
object SubagentTools {
    const val SPAWN = "agent_spawn"
    const val WAIT = "agent_wait"
    const val STATUS = "agent_status"
    const val CANCEL = "agent_cancel"
    const val CREATE = "agent_create"

    /** Every `agent_*` tool. Helpers never get them (one level of nesting only). */
    fun isAgentTool(name: String) = name.startsWith("agent_")
}
