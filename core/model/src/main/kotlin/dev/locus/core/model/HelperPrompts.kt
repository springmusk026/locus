package dev.locus.core.model

/**
 * The helper prompts a user can edit: what the helper model is asked for chat titles and follow-up
 * suggestions, and what a chat's own model is asked when it summarises a long chat.
 *
 * The safety reviewer's prompt is deliberately not here: it decides which actions run without
 * asking, so an edited version could quietly weaken approvals.
 */
enum class HelperPrompt(val label: String) {
    TITLE("Chat title"),
    SUGGESTIONS("Follow-up suggestions"),
    COMPACTION("Summary of long chats"),
}

/** A `{name}` slot in a prompt template, filled in when the prompt is sent. */
data class PromptPlaceholder(val name: String, val description: String, val required: Boolean) {
    val token: String get() = "{$name}"
}

/** What's wrong with an edited prompt. [errors] block saving; [warnings] don't. */
data class PromptCheck(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

object HelperPrompts {
    /** The chat excerpt: the last few messages as plain text. */
    val CHAT = PromptPlaceholder("chat", "The last few messages of the chat, as \"User: …\" and \"Assistant: …\" lines.", required = true)

    /** Long enough for a detailed instruction, short enough that a helper request stays small. */
    const val MAX_LENGTH = 4_000

    /**
     * Always sent before the compaction instruction, edited or not: it tells the model the
     * message isn't from the user and keeps it from calling tools or carrying on with the task.
     */
    const val COMPACTION_PREAMBLE = "[Automatic context summary, not from the user] The conversation is getting long. " +
        "Do not call any tools and do not continue the task."

    private val DEFAULT_TITLE = """
        Write a title for this chat between a user and an AI assistant: 2 to 6 words that name its subject, in the language the user writes in.
        Reply with the title only: no quotes, no trailing punctuation, no prefix like "Title:".

        <chat>
        {chat}
        </chat>
    """.trimIndent()

    private val DEFAULT_SUGGESTIONS = """
        Here is the end of a chat between a user and an AI assistant that can use tools (web, files, a Linux shell, the phone's apps).
        Suggest 3 short things the user might say next: natural follow-ups, written as the user, in the user's language and style, each under 8 words.
        Reply with one suggestion per line and nothing else: no numbering, bullets or quotes.

        <chat>
        {chat}
        </chat>
    """.trimIndent()

    private const val DEFAULT_COMPACTION = "Write a summary that will replace everything above in your context, so a fresh you can carry on without loss. Use these sections:\n" +
        "## Goal: what the user wants overall\n## Context: facts, preferences and constraints the user gave\n## Done so far: decisions and results, with exact values " +
        "(paths, commands, URLs, numbers, ids)\n## Problems: errors hit and how they were solved\n## Open: what's unfinished and the next step\n" +
        "Be specific and complete; leave out pleasantries."

    private val PLACEHOLDER = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")

    fun default(prompt: HelperPrompt): String = when (prompt) {
        HelperPrompt.TITLE -> DEFAULT_TITLE
        HelperPrompt.SUGGESTIONS -> DEFAULT_SUGGESTIONS
        HelperPrompt.COMPACTION -> DEFAULT_COMPACTION
    }

    /** The placeholders [prompt] understands. Compaction has none: it is sent after the chat itself. */
    fun placeholders(prompt: HelperPrompt): List<PromptPlaceholder> = when (prompt) {
        HelperPrompt.TITLE, HelperPrompt.SUGGESTIONS -> listOf(CHAT)
        HelperPrompt.COMPACTION -> emptyList()
    }

    /** The user's saved text for [prompt]; null means the default. */
    fun override(settings: AppSettings, prompt: HelperPrompt): String? = when (prompt) {
        HelperPrompt.TITLE -> settings.titlePrompt
        HelperPrompt.SUGGESTIONS -> settings.suggestionsPrompt
        HelperPrompt.COMPACTION -> settings.compactionPrompt
    }

    /** [settings] with [prompt]'s override set to [text] (null: back to the default). */
    fun withOverride(settings: AppSettings, prompt: HelperPrompt, text: String?): AppSettings = when (prompt) {
        HelperPrompt.TITLE -> settings.copy(titlePrompt = text)
        HelperPrompt.SUGGESTIONS -> settings.copy(suggestionsPrompt = text)
        HelperPrompt.COMPACTION -> settings.copy(compactionPrompt = text)
    }

    /**
     * The template to send: the override when it's set and still valid, the default otherwise. An
     * override that lost a required placeholder (say, edited outside the app) is never used, so a
     * request always carries the chat excerpt.
     */
    fun template(settings: AppSettings, prompt: HelperPrompt): String =
        override(settings, prompt)?.takeIf { it.isNotBlank() && check(prompt, it).ok } ?: default(prompt)

    fun isCustom(settings: AppSettings, prompt: HelperPrompt): Boolean = template(settings, prompt) != default(prompt)

    /**
     * What to store for [text]: null when it's blank or the same as the default (so a later change
     * to the default reaches the user), the trimmed text otherwise.
     */
    fun normalize(prompt: HelperPrompt, text: String?): String? =
        text?.trim()?.takeIf { it.isNotEmpty() && it != default(prompt).trim() }

    /** Checks an edited template: required placeholders present, no unknown ones, not empty or too long. */
    fun check(prompt: HelperPrompt, text: String): PromptCheck {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (text.isBlank()) errors += "The prompt is empty."
        if (text.length > MAX_LENGTH) errors += "The prompt is ${text.length} characters; the limit is $MAX_LENGTH."
        val known = placeholders(prompt)
        known.filter { it.required && it.token !in text }.forEach { errors += "Add ${it.token}: without it the model never sees the chat." }
        PLACEHOLDER.findAll(text).map { it.value }.distinct().filter { token -> known.none { it.token == token } }.forEach { token ->
            warnings += "$token isn't a placeholder here, so it's sent as typed."
        }
        return PromptCheck(errors, warnings)
    }

    /** Fills [template]'s placeholders. One pass: text that is filled in is never scanned again. */
    fun render(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: m.value }

    fun title(settings: AppSettings, excerpt: String): String = render(template(settings, HelperPrompt.TITLE), mapOf(CHAT.name to excerpt))

    fun suggestions(settings: AppSettings, excerpt: String): String = render(template(settings, HelperPrompt.SUGGESTIONS), mapOf(CHAT.name to excerpt))

    /**
     * The compaction request's last user message. Only this text changes with the user's edit; the
     * system prompt, tools and history before it are the chat's own, so the request still reuses
     * the chat's prompt cache.
     */
    fun compaction(settings: AppSettings): String = COMPACTION_PREAMBLE + " " + template(settings, HelperPrompt.COMPACTION)
}
