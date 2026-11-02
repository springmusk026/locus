package dev.locus.core.model

import kotlinx.serialization.Serializable

/** What a remembered approval is scoped to (docs/design/remembered-approvals.md). Stored by name. */
@Serializable
enum class ApprovalScopeKind {
    /** A web origin (`https://host[:port]`), with the HTTP method for fetch_url (`POST https://host`). */
    SITE,

    /** A folder inside the workspace or the chat's project, relative to it (`src/main`). */
    FOLDER,

    /** One email address or phone number. */
    RECIPIENT,

    /** A command prefix of whole words (`./gradlew test`). Never one Locus considers dangerous. */
    COMMAND,
}

/**
 * The user's "don't ask again" for one tool and one scope: "Allow for this chat" ([conversationId]
 * set) or "Always allow" (null). It only turns an Ask into an Allow, and never applies over a Deny,
 * a project's or chat's restriction, a forced confirmation (a tool's `mustConfirm`, such as a payment),
 * taint or the reviewer. Kept in the sealed settings ([PolicySeal]), so nothing
 * outside Locus can add one.
 */
@Serializable
data class RememberedApproval(
    val id: String,
    /** The tool's name, e.g. `run_command`. A rule covers that one tool. */
    val tool: String,
    val kind: ApprovalScopeKind,
    /** The scope, normalized: an origin, a folder, an address or a command prefix. */
    val value: String,
    /** The project the rule was made in; it applies only to that project's chats. Null: chats outside projects. */
    val projectId: String? = null,
    /** Set for "Allow for this chat": the rule applies to that chat only, and goes with it. */
    val conversationId: String? = null,
    val createdAt: Long = 0,
    /** When the rule stops applying; null for never (chat rules end with their chat). */
    val expiresAt: Long? = null,
) {
    fun expired(now: Long): Boolean = expiresAt != null && now >= expiresAt

    companion object {
        /** How long an "Always allow" lasts by default: 30 days (see the design doc's research). */
        const val DEFAULT_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** At most this many rules are kept; the oldest go first. */
        const val MAX_RULES = 200
    }
}
