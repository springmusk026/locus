package dev.locus.core.data.needsyou

import dev.locus.core.agent.ApprovalFlag
import dev.locus.core.agent.ApprovalPreview
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.ProposalDraft
import dev.locus.core.database.ProposalEntity
import dev.locus.core.model.ToolRisk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Where a proposal stands (docs/design/needs-you.md). Stored by name. */
enum class ProposalStatus {
    /** Waiting for the user; past its expiry it shows as expired and can't run. */
    PENDING,

    /** Approved and running now (claimed in one statement, so it runs once). */
    RUNNING,

    /** Approved and ran. */
    DONE,

    /** Approved, but it failed, or Locus's checks stopped it when it was about to run. */
    FAILED,

    /** The user declined it. */
    DECLINED,

    /** Locus was closed while it ran: it may or may not have happened, and it never runs again. */
    INTERRUPTED,

    /** It came back with a restored backup: shown, never run (it may have run after the backup was made). */
    STALE,
    ;

    companion object {
        fun parse(name: String): ProposalStatus = entries.firstOrNull { it.name == name } ?: STALE
    }
}

/** A call a scheduled or triggered run proposed (see [dev.locus.core.agent.Proposals]). */
data class Proposal(
    val id: String,
    val conversationId: String,
    val taskId: String?,
    /** The task's title when it was proposed. */
    val source: String,
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val summary: String,
    val reason: String?,
    val flags: Set<ApprovalFlag>,
    val taintSources: Set<String>,
    val preview: ApprovalPreview?,
    val createdAt: Long,
    val expiresAt: Long,
    val status: ProposalStatus,
    val decidedAt: Long? = null,
    val outcome: String? = null,
) {
    /** Still waiting, but too old to run. */
    fun expired(now: Long): Boolean = status == ProposalStatus.PENDING && now >= expiresAt

    /** Whether Approve can run it now. */
    fun approvable(now: Long): Boolean = status == ProposalStatus.PENDING && now < expiresAt

    /**
     * The approval card's request, the same as a live approval of this call would show: the tool's line and why it
     * asks. Nothing on it can be remembered ([ApprovalRequest.remember] is null): a proposal is approved once.
     */
    fun request(risk: ToolRisk = ToolRisk.NETWORK): ApprovalRequest = ApprovalRequest(
        conversationId = conversationId,
        callId = id,
        toolName = toolName,
        risk = risk,
        summary = summary + (reason?.let { "\n$it" } ?: ""),
        argumentsJson = argumentsJson,
        flags = flags,
        preview = preview,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(e: ProposalEntity): Proposal = Proposal(
            id = e.id,
            conversationId = e.conversationId,
            taskId = e.taskId,
            source = e.source,
            callId = e.callId,
            toolName = e.toolName,
            argumentsJson = e.argumentsJson,
            summary = e.summary,
            reason = e.reason,
            flags = e.flags.lines().mapNotNull { name -> ApprovalFlag.entries.firstOrNull { it.name == name } }.toSet(),
            taintSources = e.taintSources.lines().filter { it.isNotBlank() }.toSet(),
            preview = previewOf(e.previewJson),
            createdAt = e.createdAt,
            expiresAt = e.expiresAt,
            status = ProposalStatus.parse(e.status),
            decidedAt = e.decidedAt,
            outcome = e.outcome,
        )

        fun entity(id: String, draft: ProposalDraft, origin: ProposalOrigin, now: Long, expiresAt: Long) = ProposalEntity(
            id = id,
            conversationId = draft.conversationId,
            taskId = origin.taskId,
            source = origin.source.take(200),
            callId = draft.callId,
            toolName = draft.toolName,
            argumentsJson = draft.argumentsJson,
            summary = draft.summary.take(1_000),
            reason = draft.reason?.take(1_000),
            flags = draft.flags.sorted().joinToString("\n") { it.name },
            taintSources = draft.taintSources.sorted().joinToString("\n"),
            previewJson = draft.preview?.let(::previewJson),
            createdAt = now,
            expiresAt = expiresAt,
            status = ProposalStatus.PENDING.name,
        )

        /** The card's field rows; a memory change isn't kept (memory tools aren't proposable). */
        internal fun previewJson(preview: ApprovalPreview): String? {
            if (preview.rows.isEmpty()) return null
            return buildJsonArray {
                preview.rows.forEach { row ->
                    add(buildJsonObject { put("label", row.label); put("value", row.value.take(2_000)); put("outward", row.outward) })
                }
            }.toString()
        }

        internal fun previewOf(text: String?): ApprovalPreview? {
            if (text.isNullOrBlank()) return null
            val rows = runCatching { json.parseToJsonElement(text) as JsonArray }.getOrNull() ?: return null
            return ApprovalPreview(
                rows.mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    val label = (o["label"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val value = (o["value"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    ApprovalPreview.Row(label, value, o["outward"]?.jsonPrimitive?.booleanOrNull == true)
                },
            ).takeIf { it.rows.isNotEmpty() }
        }
    }
}

/** Which task's run proposed it: its id and its title, for the card ("Morning inbox proposes …"). */
data class ProposalOrigin(val taskId: String?, val source: String)
