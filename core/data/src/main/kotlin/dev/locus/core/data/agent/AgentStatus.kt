package dev.locus.core.data.agent

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.model.toolActivity
import dev.locus.core.model.toolLabel

/**
 * What the "Locus is working" notification shows, and whether it may ask to be a Live Update
 * (Android 16+: a promoted ongoing notification with a chip in the status bar).
 *
 * Only runs the user started count: a scheduled task's run is never promoted, and its state doesn't
 * change the text or the chip.
 */
internal data class AgentStatus(
    /** One line: "Browsing", "Waiting for your approval", "Working · 2 helpers". */
    val text: String,
    /**
     * The status-bar chip: "Asks" while an approval or question waits, "Paused" at a task budget,
     * "Step N" during a longer run, otherwise "Working".
     */
    val chip: String,
    /** True while a run the user started (or one of its background helpers) is working. */
    val promotable: Boolean,
    /** The approval to offer Allow / Deny for, oldest first. */
    val approval: ApprovalRequest? = null,
    /** The question to offer its answers for, when no approval is waiting. */
    val question: UserQuestion? = null,
    /** The chat paused at its task budget, to offer Continue / Stop for, when no approval or question is waiting. */
    val pausedChat: String? = null,
    /** What the pause says: "Paused: reached the 200k-token budget for this task." */
    val pauseMessage: String? = null,
    /** The chat the notification opens: the one waiting for the user, else the one working. */
    val chatId: String? = null,
) {
    /** An approval, a question or a budget pause is waiting for the user. */
    val needsUser: Boolean get() = approval != null || question != null || pausedChat != null

    /** The notification's title. */
    val title: String get() = if (needsUser) "Locus needs you" else "Locus is working"

    /** A second line saying what the user is asked: the approval's title and summary, or the question. */
    val detail: String?
        get() = approval?.let { request ->
            val summary = request.summary.lineSequence().firstOrNull().orEmpty().trim()
            if (summary.isEmpty()) approvalTitle(request) else "${approvalTitle(request)}\n$summary"
        }
            ?: question?.let(::questionBody)
            ?: pauseMessage

    companion object {
        /** The chip shows its whole text only up to this length (the chip is at most 96dp wide, with an icon). */
        const val CHIP_MAX = 7
    }
}

/**
 * Android shows at most three actions: a single choice of up to three options with no free text gets
 * all of them as buttons; otherwise up to two options (single choice only) and Reply.
 */
internal fun allOptionsAsButtons(question: UserQuestion): Boolean =
    !question.multi && !question.allowOther && question.options.size in 1..3

/** How many of [question]'s options get a button. */
internal fun questionButtons(question: UserQuestion): Int = when {
    allOptionsAsButtons(question) -> question.options.size
    question.multi -> 0
    else -> minOf(2, question.options.size)
}

/** The question, with every option listed when not all of them are buttons. */
internal fun questionBody(question: UserQuestion): String = buildString {
    append(question.question)
    if (question.options.isNotEmpty() && !allOptionsAsButtons(question)) {
        question.options.forEachIndexed { i, option -> append("\n${i + 1}. $option") }
        if (question.multi) append("\nReply with your choices.")
    }
}

/**
 * What the request would do, in the words the chat uses ([toolLabel]): "Run `ls`?" for the chat's own
 * agent, "Researcher #2: Run `ls`?" for a helper's.
 */
internal fun approvalTitle(request: ApprovalRequest): String {
    val label = toolLabel(request.toolName, request.argumentsJson, done = false) + "?"
    return request.origin?.let { "${it.label}: $label" } ?: label
}

/**
 * The status for [states] (every chat's [RunState]) and the open `ask_user` [questions] (oldest first).
 * Pure, so the chip and text rules are unit-tested.
 */
internal fun agentStatus(states: Map<String, RunState>, questions: List<UserQuestion>): AgentStatus {
    val userChats = states.filterValues { !it.scheduled }
    val running = userChats.values.firstOrNull { it.running }
    val helpers = userChats.values.sumOf { it.helpers }
    val approval = userChats.values.flatMap { it.approvals }.firstOrNull()
    val question = if (approval == null) questions.firstOrNull { it.chatId in userChats } else null
    val paused = if (approval == null && question == null) userChats.entries.firstOrNull { it.value.budgetPause != null }?.key else null

    val base = when {
        approval != null -> "Waiting for your approval"
        question != null -> "Waiting for your answer"
        paused != null -> "Paused at the task budget"
        running?.activeTool != null -> toolActivity(running.activeTool)
        else -> "Working"
    }
    val text = when (helpers) {
        0 -> if (base == "Working") "Working…" else base
        1 -> "$base · 1 helper"
        else -> "$base · $helpers helpers"
    }
    val step = running?.step ?: 0
    val chip = when {
        approval != null || question != null -> "Asks"
        paused != null -> "Paused"
        step >= 2 -> "Step $step".takeIf { it.length <= AgentStatus.CHIP_MAX } ?: "Working"
        else -> "Working"
    }
    return AgentStatus(
        text = text,
        chip = chip,
        promotable = userChats.values.any { it.running || it.helpers > 0 },
        approval = approval,
        question = question,
        pausedChat = paused,
        pauseMessage = paused?.let { userChats[it]?.budgetPause?.message },
        chatId = approval?.conversationId ?: question?.chatId ?: paused ?: userChats.entries.firstOrNull { it.value.running }?.key,
    )
}
