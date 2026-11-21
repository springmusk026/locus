package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * A saved prompt (docs/design/saved-prompts.md): text the user sends often, picked from the composer
 * with `/` and put in the box for review. Never sent on its own. [text] may hold variables
 * (`{{clipboard}}`, `{{date}}`, `{{time}}`, `{{selection}}`, `{{field:Label|default}}`), filled in
 * when it's picked (`PromptTemplate` in core/agent).
 *
 * Stored in agent-home's `prompts.json`, in the user's order. Fields may be added with defaults,
 * never renamed.
 */
@Serializable
data class SavedPrompt(
    /** A random handle; never shown. */
    val id: String,
    val name: String,
    val text: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        const val MAX_NAME = 60
        const val MAX_TEXT = 20_000

        /** Most prompts kept: a list to pick from, not an archive. */
        const val MAX_PROMPTS = 200
    }
}
