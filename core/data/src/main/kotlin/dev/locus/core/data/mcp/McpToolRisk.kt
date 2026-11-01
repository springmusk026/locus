package dev.locus.core.data.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations

/**
 * What an MCP tool call may do, for approvals and read-only modes.
 *
 * Built from the tool's [ToolAnnotations] (MCP spec 2025-03-26 and later), read with the spec's
 * defaults: a missing `readOnlyHint` means false and a missing `destructiveHint` means true, so a
 * tool that says nothing is treated as one that may delete things. The spec also says annotations
 * are hints that clients must treat as untrusted unless the server is trusted, so:
 *
 * - A tool whose name says it deletes ("delete_issue", "dropTable") is destructive whatever its hints
 *   say, on every server.
 * - On a server Locus doesn't recognise as the vendor's own ([trusted] false), a read-only claim on a
 *   tool whose name says it writes ("create_issue", "send_email") is ignored.
 */
enum class McpToolRisk {
    /** Only reads; runs without asking and counts as an observation (plan mode, read-only tasks). */
    READ,

    /** Adds or updates without deleting (`destructiveHint: false`); asks first. */
    ADDITIVE,

    /** May delete or overwrite (marked so, or by name); asks first. */
    DESTRUCTIVE,

    /** The server gave no hints; by the spec's defaults it may delete things. Asks first. */
    UNKNOWN,

    /** The tool isn't in the server's list (renamed, or the model made it up). Asks first. */
    MISSING;

    val isObservation: Boolean get() = this == READ

    /** Why the user is asked before the call, or null when it runs under the normal policy. */
    fun confirmReason(connector: String): String? = when (this) {
        READ -> null
        ADDITIVE -> "This tool adds or changes something in $connector."
        DESTRUCTIVE -> "This tool may delete or overwrite something in $connector."
        UNKNOWN -> "$connector doesn't say what this tool does, so it may change or delete something."
        MISSING -> "$connector doesn't list this tool, so Locus can't tell what it does."
    }

    /** A short tag for the tool list the model reads. */
    val label: String? get() = when (this) {
        READ -> "read-only"
        ADDITIVE -> "writes, asks first"
        DESTRUCTIVE -> "destructive, asks first"
        UNKNOWN -> "asks first"
        MISSING -> null
    }

    companion object {
        /** Words in a tool name that mean it removes something. */
        private val DESTRUCTIVE_WORDS = setOf(
            "delete", "remove", "drop", "destroy", "purge", "truncate", "wipe", "erase", "revoke",
            "terminate", "kill", "reset", "rm", "unlink", "uninstall", "archive", "cancel", "disable",
        )

        /** Words in a tool name that mean it changes or sends something. */
        private val WRITE_WORDS = setOf(
            "create", "update", "write", "edit", "set", "add", "insert", "upsert", "put", "patch", "post",
            "send", "publish", "deploy", "push", "merge", "commit", "upload", "move", "rename", "apply",
            "execute", "exec", "run", "invoke", "trigger", "comment", "reply", "invite", "assign", "close",
            "fork", "approve", "transfer", "charge", "refund", "pay", "enable", "restart", "rollback",
            "migrate", "generate", "save", "mutate", "modify", "replace", "submit", "start", "stop",
        )

        /**
         * The risk of calling [name] given its [annotations] (null when the tool has none), or
         * [MISSING] when [found] is false. [trusted] is true for a vendor's own server (see
         * [McpTemplates.official]).
         */
        fun of(name: String, annotations: ToolAnnotations?, trusted: Boolean, found: Boolean = true): McpToolRisk {
            if (!found) return MISSING
            val words = words(name)
            if (words.any { it in DESTRUCTIVE_WORDS }) return DESTRUCTIVE
            val readOnly = annotations?.readOnlyHint
            // Spec default: destructiveHint is true when absent (meaningful only when not read-only).
            val destructive = annotations?.destructiveHint
            if (readOnly == true) {
                // A read-only tool can't also be destructive; contradicting hints get the safer reading.
                if (destructive == true) return DESTRUCTIVE
                if (trusted || words.none { it in WRITE_WORDS }) return READ
                return UNKNOWN
            }
            return when {
                destructive == false -> ADDITIVE
                destructive == true -> DESTRUCTIVE
                else -> UNKNOWN
            }
        }

        /** Splits snake_case, kebab-case, dotted and camelCase names into lowercase words. */
        internal fun words(name: String): List<String> =
            name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .split(Regex("[^A-Za-z0-9]+"))
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
    }
}
