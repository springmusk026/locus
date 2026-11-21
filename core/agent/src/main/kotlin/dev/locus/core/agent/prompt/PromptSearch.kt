package dev.locus.core.agent.prompt

import dev.locus.core.model.SavedPrompt
import java.text.Normalizer
import java.util.Locale

/** Finding a saved prompt by what's typed after `/` or in the picker's search box. */
object PromptSearch {
    /**
     * The prompts matching [query], best first: names that start with it, then names with a word that
     * does, then names or texts that contain it. Case and accents are ignored. A blank query keeps them all
     * in the user's order.
     */
    fun filter(prompts: List<SavedPrompt>, query: String): List<SavedPrompt> {
        val q = fold(query.trim())
        if (q.isEmpty()) return prompts
        return prompts.mapNotNull { p ->
            val name = fold(p.name)
            val rank = when {
                name.startsWith(q) -> 0
                name.split(WORD_BREAK).any { it.startsWith(q) } -> 1
                name.contains(q) -> 2
                fold(p.text).contains(q) -> 3
                else -> return@mapNotNull null
            }
            rank to p
        }.sortedBy { it.first }.map { it.second }
    }

    /** The first line of [text] with words in it, for a one-line preview. */
    fun preview(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")

    private fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
}

/**
 * The composer's `/` shortcut (docs/design/saved-prompts.md): text that is `/` and then a word with no
 * spaces, typed into an empty box, is a search for a prompt or a command. Anything with a space or a
 * line break after the `/` is a message (or a command such as `/btw question`), so a path like
 * `/usr/bin is…` or `/btw what's this?` is left alone.
 */
object SlashQuery {
    private val QUERY = Regex("^/(\\S{0,40})$")

    /** What's typed after `/`, or null when [text] isn't a `/` search. */
    fun of(text: String): String? = QUERY.matchEntire(text)?.groupValues?.get(1)
}
