package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * A group of chats with their own workspace folder, instructions, memory scope, default models and
 * permissions (docs/design/projects.md). Instructions and memory live in agent-home
 * (`projects/<id>/`); permissions live in the sealed settings ([AppSettings.projectPolicies]).
 */
data class Project(
    val id: String,
    /** Shown in the app only; never sent to the model. */
    val name: String,
    /** The project's folder, relative to the workspace (e.g. `projects/garden-app`). Fixed at creation. */
    val folder: String,
    /** Default model for new chats in the project; null means the global default. */
    val providerId: String? = null,
    val model: String? = null,
    /** The helper model for side jobs in the project's chats; null means the global helper setting. */
    val helperProviderId: String? = null,
    val helperModel: String? = null,
    /** Applies to new chats; each chat keeps the scope it started with. */
    val memoryScope: MemoryScope = MemoryScope.PROJECT_AND_GLOBAL,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** Which memory a project chat sees. */
enum class MemoryScope(val label: String) {
    /** The project's memory first, then the user's general memory. Saves go to the project. */
    PROJECT_AND_GLOBAL("Project and general"),

    /** Only the project's memory, in the prompt and in the memory tools. */
    PROJECT_ONLY("Project only"),
    ;

    companion object {
        fun parse(name: String?): MemoryScope? = entries.firstOrNull { it.name == name }
    }
}

/**
 * What a chat is scoped to, fixed when it was created: its project, the project's folder and the
 * memory scope it started with. Null fields mean the global workspace or memory.
 */
data class ChatScopeSpec(val projectId: String, val folder: String, val memory: MemoryScope)

/**
 * A project's permissions: an overlay on the global tool policies that can only make them stricter,
 * except for tools the user explicitly [widened]. Stored in the sealed settings (see [PolicySeal]).
 */
@Serializable
data class ProjectPolicy(
    /** Per-tool overrides for this project's chats. */
    val toolPolicies: Map<String, ToolPolicy> = emptyMap(),
    /**
     * Tools whose project policy applies even when it's looser than the global one. Only set by an
     * explicit, confirmed choice in the project's permission screen.
     */
    val widened: Set<String> = emptySet(),
    /** Connectors and MCP servers (ids) this project's chats may use; null means every connector. */
    val allowedConnectors: Set<String>? = null,
) {
    val isEmpty: Boolean get() = toolPolicies.isEmpty() && widened.isEmpty() && allowedConnectors == null

    /** Only what restricts: no Allow overrides and no widening. Used when the seal fails. */
    fun restricted(): ProjectPolicy = copy(toolPolicies = toolPolicies.filterValues { it != ToolPolicy.ALLOW }, widened = emptySet())

    /**
     * The policy for [tool] in a project chat, given the global one. With no project override the
     * global policy applies; an override only tightens it (Deny > Ask > Allow), unless the tool is in
     * [widened], where the project's choice applies as it is.
     */
    fun effective(tool: String, global: ToolPolicy): ToolPolicy {
        val own = toolPolicies[tool] ?: return global
        if (tool in widened) return own
        return stricter(own, global)
    }

    /** Whether a chat in this project may use [connectorId]. */
    fun allowsConnector(connectorId: String): Boolean = allowedConnectors == null || connectorId in allowedConnectors

    companion object {
        private fun rank(p: ToolPolicy) = when (p) {
            ToolPolicy.ALLOW -> 0
            ToolPolicy.ASK -> 1
            ToolPolicy.DENY -> 2
        }

        fun stricter(a: ToolPolicy, b: ToolPolicy): ToolPolicy = if (rank(a) >= rank(b)) a else b

        /** A folder name from a project name: lowercase words joined by dashes, under `projects/`. */
        fun defaultFolder(name: String): String =
            "projects/" + name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "project" }

        /**
         * A safe workspace-relative folder, or null: no absolute paths, no `..` or `.` segments, no
         * empty segments, no hidden folders (`.locus` holds Locus's own files), at most 120 characters.
         */
        fun cleanFolder(raw: String): String? {
            val parts = raw.trim().trim('/').split('/').map { it.trim() }
            if (parts.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") || '\\' in it || '\u0000' in it }) return null
            return parts.joinToString("/").takeIf { it.length <= 120 }
        }
    }
}
