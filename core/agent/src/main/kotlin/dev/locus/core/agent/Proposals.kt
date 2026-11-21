package dev.locus.core.agent

import dev.locus.core.model.DeviceIntegrations

/**
 * What a scheduled or event-triggered run does with a call it may not make on its own (docs/design/needs-you.md):
 * instead of refusing it, Locus records it as a proposal the user approves or declines later, in Needs you.
 * The model learns this from the call's result, so its system prompt and tool list stay as they were.
 */
object Proposals {
    /**
     * The tools whose calls an unattended run may propose. Each writes or sends one thing the user can read in
     * full on the card before it happens, and none of them spends money or drives a screen:
     * - `email_send`: a mail from the agent's own mailbox (it always asks in a chat too);
     * - `calendar_create`: a new event in the one calendar the user lets Locus change;
     * - `write_file`: a file in the workspace (never a connected folder of the user's own files, see [Tool.asksFirst]).
     *
     * Not on it: payments and anything in the browser, shell commands, SSH, deploys, deletes, edits of existing
     * files (Locus forgets after a restart which files a chat has read, so an approved edit would fail), memory
     * and skills, and the phone hand-offs, which need the user's screen anyway. `notify_me` isn't here because
     * a background run may already use it: it only reaches the user.
     */
    val TOOLS: Set<String> = setOf("email_send", DeviceIntegrations.CALENDAR_CREATE, "write_file")

    /** How long a proposal can be approved; after that it shows as expired and never runs. */
    const val EXPIRY_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** Proposals one run may make; further calls are refused, so a confused run can't flood the list. */
    const val MAX_PER_RUN = 5

    /** Proposals waiting at once, across every run; beyond it new ones are refused until the user catches up. */
    const val MAX_PENDING = 50

    /** The result the model reads for a call Locus recorded as a proposal. English, like every tool result. */
    fun proposedResult(summary: String, duplicate: Boolean): String =
        (if (duplicate) "This exact call is already waiting for the user's approval; it wasn't recorded twice. " else "") +
            "Not run yet: this is an unattended run, so Locus recorded the call as a proposal for the user (“$summary”). " +
            "They will see it in Locus under Needs you and can approve or decline it within 7 days; approving runs exactly " +
            "this call, and the outcome is added to this chat. Don't call it again. In your final answer, say what you proposed."

    /** The result for a call that could have been proposed but wasn't, with why. */
    fun refusedResult(reason: String): String = "Not allowed: this is a background run and $reason Continue without it."

    /** The note the run's chat gets once the user approved a proposal and it ran. */
    fun approvedNote(toolName: String, callId: String, failed: Boolean, output: String): String =
        "[From Locus, not typed by the user] The user approved your proposed $toolName call ($callId) in Needs you, and Locus ran it " +
            "with exactly the arguments you gave. " + (if (failed) "It failed:" else "Its result:") + "\n$output"

    /** The note for a proposal that Locus's checks stopped when the user approved it (its tool switched off, the chat's limits). */
    fun blockedNote(toolName: String, callId: String, reason: String): String =
        "[From Locus, not typed by the user] The user approved your proposed $toolName call ($callId) in Needs you, but it didn't run: $reason"
}

/** Where an unattended run's proposals go: recorded, or refused with a reason the model reads. */
fun interface ProposalSink {
    suspend fun propose(draft: ProposalDraft): ProposalReceipt
}

/**
 * A call an unattended run proposed: exactly what would run, why it needs the user ([reason], [flags]), what
 * the run had read ([taintSources]) and what the card shows ([summary], [preview]).
 */
data class ProposalDraft(
    val conversationId: String,
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val summary: String,
    val reason: String?,
    val flags: Set<ApprovalFlag>,
    val taintSources: Set<String>,
    val preview: ApprovalPreview?,
)

sealed interface ProposalReceipt {
    /** Recorded (or already waiting, [duplicate]). */
    data class Recorded(val id: String, val duplicate: Boolean = false) : ProposalReceipt

    /** Not recorded: [reason] finishes "this is a background run and …" ("too many proposals are waiting."). */
    data class Refused(val reason: String) : ProposalReceipt
}
