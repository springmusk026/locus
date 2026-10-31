package dev.locus.core.ondevice

import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role

/**
 * What a message contributes to the model's context: its role and parts, without ids or timestamps
 * (which differ between the turn we produced and the message the agent stores for it).
 */
internal data class TurnKey(val role: Role, val parts: List<Part>) {
    companion object {
        fun of(message: Message) = TurnKey(
            message.role,
            // How a tool call was authorised is display-only and never reaches the model.
            message.parts.map { if (it is Part.ToolResult) it.copy(decision = null) else it },
        )

        /** The message the agent appends for a finished turn: the text (if any), then the tool calls. */
        fun assistant(text: String, calls: List<Part.ToolCall>) = TurnKey(
            Role.ASSISTANT,
            buildList {
                if (text.isNotEmpty()) add(Part.Text(text))
                addAll(calls)
            },
        )
    }
}

internal object TranscriptPrefix {
    /**
     * True when [request] is exactly what a conversation has already seen ([consumed]) plus one new
     * user or tool message, so that conversation can carry on by sending only the new message.
     */
    fun continues(consumed: List<TurnKey>, request: List<TurnKey>): Boolean =
        consumed.isNotEmpty() &&
            request.size == consumed.size + 1 &&
            request.last().role != Role.ASSISTANT &&
            request.subList(0, consumed.size) == consumed
}
