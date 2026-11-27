package dev.locus.core.data.agent

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.BudgetReached
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.R
import dev.locus.core.linux.EngineText

/** What the status line says first, before any helpers; worded in the app's language by [AgentStatus.text]. */
internal sealed interface StatusSays {
    /** "Waiting for your approval". */
    data object Approval : StatusSays

    /** "Waiting for your answer". */
    data object Answer : StatusSays

    /** "Paused at the task budget". */
    data object BudgetPaused : StatusSays

    /** "Running a command": the active [tool]'s activity phrase. */
    data class Tool(val tool: String) : StatusSays

    /** "Working". */
    data object Working : StatusSays
}

/**
 * What the "Locus is working" notification shows, and whether it may ask to be a Live Update
 * (Android 16+: a promoted ongoing notification with a chip in the status bar). The words are [EngineText]
 * in the app's language (docs/design/localization.md, "Notifications"); the tool and budget phrases come
 * from the caller ([NotificationPhrases]).
 *
 * Only runs the user started count: a scheduled task's run is never promoted, and its state doesn't
 * change the text or the chip.
 */
internal data class AgentStatus(
    /** What the one line says first ([text] adds the helpers). */
    val says: StatusSays,
    /** Background helpers working for the user's chats. */
    val helpers: Int = 0,
    /** The step shown in the chip ("Step 4"); null when the chip says something else. */
    val chipStep: Int? = null,
    /** True while a run the user started (or one of its background helpers) is working. */
    val promotable: Boolean,
    /** The approval to offer Allow / Deny for, oldest first. */
    val approval: ApprovalRequest? = null,
    /** The question to offer its answers for, when no approval is waiting. */
    val question: UserQuestion? = null,
    /** The chat paused at its task budget, to offer Continue / Stop for, when no approval or question is waiting. */
    val pausedChat: String? = null,
    /** Why it paused: "Paused: reached the 200k-token budget for this task." */
    val pause: BudgetPause? = null,
    /** The chat the notification opens: the one waiting for the user, else the one working. */
    val chatId: String? = null,
    /**
     * The chat waiting for the user is a locked chat (docs/design/app-lock.md): its approval, question
     * or pause isn't described ([detail] is null) and the notification offers no actions, only a tap.
     */
    val lockedChat: Boolean = false,
) {
    /** An approval, a question or a budget pause is waiting for the user. */
    val needsUser: Boolean get() = approval != null || question != null || pausedChat != null

    /** The notification's title: "Locus needs you", "Locus is working". */
    val title: EngineText get() = EngineText.Res(if (needsUser) R.string.data_notif_title_needs_you else R.string.data_notif_title_working)

    /** One line: "Browsing", "Waiting for your approval", "Working · 2 helpers"; [activity] words a tool's activity. */
    fun text(activity: (String) -> String): EngineText {
        val base = when (val s = says) {
            StatusSays.Approval -> EngineText.Res(R.string.data_notif_status_approval)
            StatusSays.Answer -> EngineText.Res(R.string.data_notif_status_answer)
            StatusSays.BudgetPaused -> EngineText.Res(R.string.data_notif_status_paused)
            is StatusSays.Tool -> EngineText.Plain(activity(s.tool))
            StatusSays.Working -> EngineText.Res(R.string.data_notif_status_working)
        }
        return when {
            helpers > 0 -> EngineText.Plural(R.plurals.data_notif_status_helpers, helpers, base, helpers)
            says == StatusSays.Working -> EngineText.Res(R.string.data_notif_status_working_alone)
            else -> base
        }
    }

    /**
     * The status-bar chip: "Asks" while an approval or question waits, "Paused" at a task budget,
     * "Step N" during a longer run, otherwise "Working". [chip] words it and falls back to "Working" when
     * the step wouldn't fit.
     */
    val chipText: EngineText get() = when {
        approval != null || question != null -> EngineText.Res(R.string.data_notif_chip_asks)
        pausedChat != null -> EngineText.Res(R.string.data_notif_chip_paused)
        chipStep != null -> EngineText.Res(R.string.data_notif_chip_step, chipStep)
        else -> CHIP_WORKING
    }

    /** [chipText] resolved by [resolve], or "Working" when it is longer than [CHIP_MAX] in this language. */
    fun chip(resolve: (EngineText) -> String): String =
        resolve(chipText).takeIf { it.length <= CHIP_MAX } ?: resolve(CHIP_WORKING)

    /**
     * A second line saying what the user is asked: the approval's title ([label] words the call) and summary,
     * the question, or why the task paused ([budget] words it).
     */
    fun detail(label: (ApprovalRequest) -> String, budget: (BudgetReached) -> String): EngineText? = when {
        lockedChat -> null
        approval != null -> {
            val title = approvalTitle(approval, label)
            // The summary is the tool's own description, also the audit log's, so it stays English.
            val summary = approval.summary.lineSequence().firstOrNull().orEmpty().trim()
            if (summary.isEmpty()) title else EngineText.Res(R.string.data_notif_two_lines, title, summary)
        }
        question != null -> questionBody(question)
        else -> pause?.let { p -> EngineText.Plain(p.reached?.let(budget) ?: p.message) }
    }

    companion object {
        /** The chip shows its whole text only up to this length (the chip is at most 96dp wide, with an icon). */
        const val CHIP_MAX = 7

        private val CHIP_WORKING = EngineText.Res(R.string.data_notif_chip_working)
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
internal fun questionBody(question: UserQuestion): EngineText {
    val body = buildString {
        append(question.question)
        if (question.options.isNotEmpty() && !allOptionsAsButtons(question)) {
            question.options.forEachIndexed { i, option -> append("\n${i + 1}. $option") }
        }
    }
    return if (question.multi && question.options.isNotEmpty() && !allOptionsAsButtons(question)) {
        EngineText.Res(R.string.data_notif_two_lines, body, EngineText.Res(R.string.data_notif_question_choices))
    } else {
        EngineText.Plain(body)
    }
}

/** The question notification's title: "Researcher #2 asks", or "Locus asks" for the chat's own agent. */
internal fun questionTitle(question: UserQuestion): EngineText =
    question.origin?.let { EngineText.Res(R.string.data_notif_question_title, it.label) } ?: EngineText.Res(R.string.data_notif_question_title_locus)

/**
 * What the request would do, in the words the chat uses ([label], [dev.locus.core.model.toolLabel] in
 * English): "Run `ls`?" for the chat's own agent, "Researcher #2: Run `ls`?" for a helper's.
 */
internal fun approvalTitle(request: ApprovalRequest, label: (ApprovalRequest) -> String): EngineText {
    val asked = EngineText.Res(R.string.data_notif_approval_label, label(request))
    return request.origin?.let { EngineText.Res(R.string.data_notif_approval_origin, it.label, asked) } ?: asked
}

/**
 * The status for [states] (every chat's [RunState]) and the open `ask_user` [questions] (oldest first).
 * [lockedChats] are the locked chats' ids, whose waiting requests aren't described. Pure, so the chip
 * and text rules are unit-tested.
 */
internal fun agentStatus(states: Map<String, RunState>, questions: List<UserQuestion>, lockedChats: Set<String> = emptySet()): AgentStatus {
    val userChats = states.filterValues { !it.scheduled }
    val running = userChats.values.firstOrNull { it.running }
    val helpers = userChats.values.sumOf { it.helpers }
    val approval = userChats.values.flatMap { it.approvals }.firstOrNull()
    val question = if (approval == null) questions.firstOrNull { it.chatId in userChats } else null
    val paused = if (approval == null && question == null) userChats.entries.firstOrNull { it.value.budgetPause != null }?.key else null

    val says = when {
        approval != null -> StatusSays.Approval
        question != null -> StatusSays.Answer
        paused != null -> StatusSays.BudgetPaused
        running?.activeTool != null -> StatusSays.Tool(running.activeTool)
        else -> StatusSays.Working
    }
    // "Step 100" would be cut off in the chip.
    val step = (running?.step ?: 0).takeIf { it in 2..MAX_CHIP_STEP }
    return AgentStatus(
        says = says,
        helpers = helpers,
        chipStep = step.takeIf { approval == null && question == null && paused == null },
        promotable = userChats.values.any { it.running || it.helpers > 0 },
        approval = approval,
        question = question,
        pausedChat = paused,
        pause = paused?.let { userChats[it]?.budgetPause },
        chatId = approval?.conversationId ?: question?.chatId ?: paused ?: userChats.entries.firstOrNull { it.value.running }?.key,
        lockedChat = (approval?.conversationId ?: question?.chatId ?: paused)?.let { it in lockedChats } == true,
    )
}

/** The highest step the chip names: "Step 100" wouldn't fit. */
private const val MAX_CHIP_STEP = 99
