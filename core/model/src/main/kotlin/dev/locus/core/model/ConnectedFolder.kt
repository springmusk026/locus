package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * A folder outside the workspace that the user granted Locus through Android's folder picker
 * (`ACTION_OPEN_DOCUMENT_TREE` with a persisted grant): an Obsidian or Logseq vault, a Syncthing
 * folder, Documents/Notes. The agent's file tools reach it as `folders/<name>/…`
 * (docs/design/connected-folders.md). Kept in the sealed settings, never in Room.
 */
@Serializable
data class ConnectedFolder(
    /** Stable id; trash entries and undo records refer to it. */
    val id: String,
    /** The path segment after `folders/`, chosen when connecting ([ConnectedFolders.nameProblem]). */
    val name: String,
    /** The tree URI the picker returned, with a persisted read (and usually write) grant. */
    val treeUri: String,
    /** Where it is, as the picker's provider names it ("Internal storage › Documents/Obsidian"); shown only. */
    val source: String = "",
    /** The agent may only read it: writes are refused before anyone is asked. */
    val readOnly: Boolean = false,
    /**
     * Hidden folders and files (`.obsidian`, `.git`, `.trash`, any name starting with a dot) are
     * read-only for the agent unless this is on.
     */
    val hiddenWritable: Boolean = false,
    /**
     * Projects whose chats see it. Chats outside projects see every connected folder; a project chat
     * sees only the folders linked to its project (docs/design/connected-folders.md, "Projects").
     */
    val projectIds: List<String> = emptyList(),
    val addedAt: Long = 0,
)

/** Naming rules for connected folders, shared by the picker sheet and the file layer. */
object ConnectedFolders {
    /** The virtual folder they appear under, at the top of the workspace (or of a project's folder). */
    const val PREFIX = "folders"

    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")

    /** Why [name] can't name a connected folder, or null. [taken] are the names already in use. */
    fun nameProblem(name: String, taken: Collection<String>): String? = when {
        name.isBlank() -> "Give it a name."
        !NAME.matches(name) -> "Use letters, digits, dots, dashes or underscores (up to 40), starting with a letter or digit."
        taken.any { it.equals(name, ignoreCase = true) } -> "Another connected folder is already called that."
        else -> null
    }

    /** A valid name made from the picked folder's own name ("My Vault" → "my-vault"), unique among [taken]. */
    fun suggest(display: String, taken: Collection<String>): String {
        val base = display.trim().lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(32)
            .ifEmpty { "folder" }
        var candidate = base
        var n = 2
        while (taken.any { it.equals(candidate, ignoreCase = true) }) candidate = "$base-${n++}"
        return candidate
    }

    /** The agent-visible path of [folder]'s top. */
    fun pathOf(folder: ConnectedFolder): String = "$PREFIX/${folder.name}"

    /** Whether a chat in [projectId] (null outside projects) sees [folder]. */
    fun visibleIn(folder: ConnectedFolder, projectId: String?): Boolean = projectId == null || projectId in folder.projectIds
}
