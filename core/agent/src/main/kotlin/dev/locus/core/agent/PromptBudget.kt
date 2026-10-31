package dev.locus.core.agent

/**
 * A rough, deliberately high token count for text that has no provider usage yet (a system prompt
 * being built). It counts characters, never bytes: ASCII runs at about four characters a token, and
 * every other character (CJK, Cyrillic, emoji…) is counted as a whole token, so non-Latin text is
 * overestimated rather than undercounted. Only used to cap what goes into a new chat's prompt;
 * compaction and the context ring use the provider's reported usage.
 */
object TokenEstimate {
    fun of(text: CharSequence): Int {
        var ascii = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.code < 0x80) ascii++ else if (!c.isLowSurrogate()) other++
            i++
        }
        return (ascii + 3) / 4 + other
    }
}

/**
 * Hard token budgets for the parts of a new chat's system prompt that grow with use (persona files,
 * memory index, skills, connectors, helper types, recent chats, the user's instructions), and a cap on
 * the whole prompt relative to the model's context window. Anything cut gets a note pointing at the
 * tools that reach the rest. The prompt is still built once per chat and frozen.
 */
data class PromptBudget(
    /** Each persona file (IDENTITY.md, SOUL.md, USER.md, AGENTS.md). */
    val persona: Int,
    val memory: Int,
    val skills: Int,
    val connectors: Int,
    val agents: Int,
    val recentChats: Int,
    val instructions: Int,
    /** The whole system prompt, fixed text included. */
    val total: Int,
) {
    /** Every section budget times [factor]; the total stays. */
    fun scaled(factor: Double) = copy(
        persona = (persona * factor).toInt(),
        memory = (memory * factor).toInt(),
        skills = (skills * factor).toInt(),
        connectors = (connectors * factor).toInt(),
        agents = (agents * factor).toInt(),
        recentChats = (recentChats * factor).toInt(),
        instructions = (instructions * factor).toInt(),
    )

    companion object {
        /** Windows up to this size get [COMPACT] section budgets (on-device Gemma runs with 16K). */
        const val SMALL_WINDOW = 32_768

        /** The prompt may use at most this share of the context window. */
        const val WINDOW_SHARE = 0.2

        val STANDARD = PromptBudget(
            persona = 1_000,
            memory = 2_000,
            skills = 1_500,
            connectors = 1_200,
            agents = 600,
            recentChats = 500,
            instructions = 2_000,
            total = 12_000,
        )

        val COMPACT = PromptBudget(
            persona = 250,
            memory = 400,
            skills = 300,
            connectors = 250,
            agents = 150,
            recentChats = 0,
            instructions = 500,
            total = 4_000,
        )

        /** The budget for a model with [contextWindow] tokens; [STANDARD] when the window isn't known. */
        fun forWindow(contextWindow: Int?): PromptBudget {
            if (contextWindow == null || contextWindow <= 0) return STANDARD
            val base = if (contextWindow <= SMALL_WINDOW) COMPACT else STANDARD
            return base.copy(total = minOf(STANDARD.total, (contextWindow * WINDOW_SHARE).toInt()))
        }

        /**
         * [text] cut to about [tokens], at a line boundary where possible. When something is cut, the
         * [note] (given how many lines were left out) is added on its own line.
         */
        fun fit(text: String, tokens: Int, note: (omittedLines: Int) -> String): String {
            if (TokenEstimate.of(text) <= tokens) return text
            val lines = text.lines()
            val kept = StringBuilder()
            var used = 0
            var count = 0
            for (line in lines) {
                val cost = TokenEstimate.of(line) + 1
                if (used + cost > tokens) break
                if (count > 0) kept.append('\n')
                kept.append(line)
                used += cost
                count++
            }
            // A single overlong first line: keep its start rather than nothing.
            if (count == 0 && tokens > 0) {
                val line = lines.first()
                // In quarter tokens, matching [TokenEstimate]: ASCII costs 1, anything else 4.
                var left = tokens * 4L
                var end = 0
                while (end < line.length) {
                    val cost = if (line[end].code < 0x80) 1 else if (line[end].isLowSurrogate()) 0 else 4
                    if (cost > left) break
                    left -= cost
                    end++
                }
                // Never split a surrogate pair.
                if (end > 0 && line[end - 1].isHighSurrogate()) end--
                if (end > 0) kept.append(line, 0, end).append(" …")
            }
            val omitted = lines.size - count
            return (if (kept.isEmpty()) "" else "$kept\n") + note(omitted)
        }
    }
}
