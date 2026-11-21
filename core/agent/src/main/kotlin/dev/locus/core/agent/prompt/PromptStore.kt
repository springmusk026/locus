package dev.locus.core.agent.prompt

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.model.SavedPrompt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Saved prompts as one file in agent-home, `prompts.json`, in the user's order
 * (docs/design/saved-prompts.md). Agent-home is in the app's private storage and in every backup, so
 * the prompts are too; no Room table and no settings key. No agent tool reads or writes this file.
 */
class PromptStore(private val home: AgentHome) {

    @Serializable
    internal data class PromptFile(val schema: Int = SCHEMA, val prompts: List<SavedPrompt> = emptyList())

    private val file get() = File(home.dir, FILE)

    /** Every prompt, in the user's order; empty when there's no file or it can't be read. */
    @Synchronized
    fun all(): List<SavedPrompt> = read() ?: emptyList()

    /**
     * Saves [prompt] (checked with [validate]): over the one with its id, or at the end when it's new
     * (a blank id gets a fresh one). Returns what was written.
     */
    @Synchronized
    fun save(prompt: SavedPrompt, now: Long = System.currentTimeMillis()): SavedPrompt {
        validate(prompt)
        val list = current().toMutableList()
        val at = list.indexOfFirst { it.id == prompt.id && prompt.id.isNotBlank() }
        require(at >= 0 || list.size < SavedPrompt.MAX_PROMPTS) { "You can keep at most ${SavedPrompt.MAX_PROMPTS} prompts." }
        val saved = prompt.copy(
            id = prompt.id.ifBlank { UUID.randomUUID().toString() },
            name = prompt.name.trim(),
            createdAt = (if (at >= 0) list[at].createdAt else prompt.createdAt).takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
        if (at >= 0) list[at] = saved else list += saved
        write(list)
        return saved
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val list = current()
        if (list.none { it.id == id }) return false
        write(list.filterNot { it.id == id })
        return true
    }

    /** Moves the prompt [id] [by] places (negative: up), stopping at either end. */
    @Synchronized
    fun move(id: String, by: Int): List<SavedPrompt> {
        val list = current().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return list
        val to = (from + by).coerceIn(0, list.lastIndex)
        if (to != from) {
            list.add(to, list.removeAt(from))
            write(list)
        }
        return list
    }

    /** Puts the prompts in [ids]' order; ones not named keep their place after them. */
    @Synchronized
    fun reorder(ids: List<String>): List<SavedPrompt> {
        val list = current()
        val byId = list.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] }.distinct()
        val result = ordered + list.filterNot { it in ordered }
        if (result != list) write(result)
        return result
    }

    private fun read(): List<SavedPrompt>? {
        val f = file
        if (!f.isFile) return emptyList()
        return runCatching { json.decodeFromString(PromptFile.serializer(), f.readText()).prompts }.getOrNull()
            ?.filter { it.id.isNotBlank() && it.name.isNotBlank() }?.distinctBy { it.id }
    }

    /**
     * The list a write starts from. A file that can't be read is set aside as `prompts.json.unreadable`
     * rather than overwritten, so nothing in it is lost.
     */
    private fun current(): List<SavedPrompt> = read() ?: run {
        val f = file
        f.renameTo(File(f.parentFile, "$FILE.unreadable"))
        emptyList()
    }

    private fun write(list: List<SavedPrompt>) {
        val f = file
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(json.encodeToString(PromptFile.serializer(), PromptFile(SCHEMA, list)))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "Couldn't write $FILE." }
        }
    }

    companion object {
        const val FILE = "prompts.json"
        const val SCHEMA = 1
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; coerceInputValues = true }

        /** Throws [IllegalArgumentException] when [p] can't be saved. The editor checks the same first, in the user's language. */
        fun validate(p: SavedPrompt) {
            require(p.name.isNotBlank()) { "Give the prompt a name." }
            require(p.name.trim().length <= SavedPrompt.MAX_NAME) { "A prompt's name is at most ${SavedPrompt.MAX_NAME} characters." }
            require(p.text.isNotBlank()) { "The prompt has no text." }
            require(p.text.length <= SavedPrompt.MAX_TEXT) { "A prompt is at most ${SavedPrompt.MAX_TEXT} characters." }
            val issues = PromptTemplate.parse(p.text).issues
            require(issues.isEmpty()) { "The prompt has ${issues.size} variable(s) that can't be filled in." }
        }
    }
}
