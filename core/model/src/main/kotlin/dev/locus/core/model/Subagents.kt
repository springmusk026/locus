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

/**
 * Why a helper stopped before finishing. Not stored: it lives in the helper's live state and in the
 * `stopped` attribute of its report, so values may change.
 */
enum class HelperStop(val wire: String, val label: String) {
    TIMEOUT("timeout", "Timed out"),
    CANCELLED("cancelled", "Stopped"),
    TOKEN_BUDGET("token_budget", "Token limit"),
    STEP_LIMIT("step_limit", "Step limit"),
    TASK_BUDGET("task_budget", "Task budget"),
    ;

    companion object {
        private val attribute = Regex("""^<subagent_report [^>]*\bstopped="([a-z_]+)"""")

        /** The reason in a wrapped report's `stopped` attribute, or null. */
        fun fromReport(report: String?): HelperStop? =
            report?.let { attribute.find(it)?.groupValues?.get(1) }?.let { wire -> entries.firstOrNull { it.wire == wire } }
    }
}

/** The user's choice of how long a helper may work (Settings → Agent → Helpers). */
object HelperTimeLimit {
    const val DEFAULT_MINUTES = 30
    val CHOICES = listOf(10, 30, 60, 120)

    /** [minutes] as a supported choice: the nearest one, so a hand-edited or old value still works. */
    fun normalized(minutes: Int): Int = CHOICES.minBy { kotlin.math.abs(it - minutes) }

    fun label(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"
}

/** Names of the sub-agent tools; the chat renders `agent_spawn` and `agent_send` as nested helper rows. */
object SubagentTools {
    const val SPAWN = "agent_spawn"
    const val WAIT = "agent_wait"
    const val STATUS = "agent_status"
    const val CANCEL = "agent_cancel"

    /** Continues a helper that finished (or stopped early) with one more message from the main agent. */
    const val SEND = "agent_send"
    const val CREATE = "agent_create"

    /** Every `agent_*` tool. Helpers never get them (one level of nesting only). */
    fun isAgentTool(name: String) = name.startsWith("agent_")
}
