package dev.locus.core.agent

import dev.locus.core.agent.tools.Todos
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TodoItem
import dev.locus.core.model.TodoStatus
import dev.locus.core.model.Verification
import dev.locus.core.model.startsTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The evidence-based completion check (docs/research/agent-algorithms-2026.md, item 10). Models
 * declare victory too early, and self-critique without outside feedback doesn't fix that (Huang et
 * al. 2023), so this looks only at evidence in the transcript: when the agent ends a turn (a reply
 * with no tool calls) while something concrete is still open, Locus appends one hidden [ModeNote]
 * asking it to finish, verify, or say plainly what's unverified, and the run goes on once more.
 *
 * Open means: checklist items pending or in progress, the task's last `run_command` exited non-zero,
 * or files edited in this task after the chat's last test or build. At most once per task; the note
 * is appended (never inserted), so the cached prefix holds. No model judges anything here.
 */
object CompletionCheck {

    /** What's still open when the agent stopped. Each non-empty kind counts as one item, each checklist entry as one. */
    data class Open(
        val todos: List<TodoItem> = emptyList(),
        /** The task's last `run_command`, when it exited non-zero: the command and its exit code. */
        val failedCommand: FailedCommand? = null,
        /** Files edited after the last test or build (set only when one ran in the chat). */
        val unverifiedEdits: List<String> = emptyList(),
    ) {
        val count: Int get() = todos.size + (if (failedCommand != null) 1 else 0) + (if (unverifiedEdits.isNotEmpty()) 1 else 0)
        val isEmpty: Boolean get() = count == 0
    }

    data class FailedCommand(val command: String, val exitCode: Int)

    /** Tools that change workspace files (a call of them through `call_tool` counts too). */
    val EDIT_TOOLS = setOf("write_file", "edit_file", "multi_edit", "move_path", "copy_path", "delete_path")

    private const val RUN_COMMAND = "run_command"
    private const val MARK = "Completion check: Locus found "
    private val COUNT = Regex(Regex.escape(MARK) + "(\\d+) open item")
    private val EXIT = Regex("^Exit code (-?\\d+)")
    private const val MAX_NAMED = 5

    /**
     * The messages of the task [transcript] ends with: everything after the last message the user
     * started a turn with. A message told mid-task, a note from Locus and a compaction checkpoint
     * don't start a task.
     */
    fun task(transcript: List<Message>): List<Message> {
        val start = transcript.indexOfLast { startsTask(it) }
        return transcript.drop(start + 1)
    }

    /** The message that started the task [transcript] ends with, or null when it's not in view (summarised away). */
    fun taskStart(transcript: List<Message>): Message? = transcript.lastOrNull { startsTask(it) }

    private fun startsTask(m: Message) = m.startsTurn() && m.parts.none { it is Part.Checkpoint } && !ModeNote.isMidTask(m)

    /** Whether this task already had its note: at most one per task. */
    fun alreadyAsked(transcript: List<Message>): Boolean = task(transcript).any { openItemsIn(it) != null }

    /** What's open at the end of [transcript]; empty when nothing is. */
    fun open(transcript: List<Message>): Open {
        val task = task(transcript)
        val todos = Todos.latest(task).orEmpty().filter { it.status == TodoStatus.PENDING || it.status == TodoStatus.IN_PROGRESS }

        val results = transcript.asSequence().filter { it.role == Role.TOOL }.flatMap { it.toolResults }.associateBy { it.callId }
        // Every call that has a result, in order, with the index of the message it's in.
        val taskFrom = transcript.size - task.size
        val calls = transcript.withIndex().filter { it.value.role == Role.ASSISTANT }.flatMap { (i, m) ->
            m.toolCalls.mapNotNull { c ->
                val r = results[c.id] ?: return@mapNotNull null
                val (name, args) = DiscoveryTools.unwrap(c.name, c.argumentsJson)
                Call(i, name, args, r)
            }
        }

        val lastCommand = calls.lastOrNull { it.index >= taskFrom && it.name == RUN_COMMAND && ran(it.result) }
        val failed = lastCommand?.let { c ->
            val exit = EXIT.find(c.result.content.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            if (exit != null && exit != 0) FailedCommand(arg(c.args, "command").orEmpty(), exit) else null
        }

        // Positions in [calls], so a test and an edit in the same reply keep their order.
        val lastCheck = calls.indexOfLast { c ->
            c.name == RUN_COMMAND && ran(c.result) &&
                arg(c.args, "command")?.let(Verification::detect)?.kind.let { it == Verification.Kind.TEST || it == Verification.Kind.BUILD }
        }
        // Only in a chat where a test or build ran: elsewhere there's no check the edits skipped.
        val edits = if (lastCheck < 0) {
            emptyList()
        } else {
            calls.drop(lastCheck + 1)
                .filter { it.index >= taskFrom && it.name in EDIT_TOOLS && !it.result.isError }
                .map { c -> arg(c.args, "path") ?: arg(c.args, "to") ?: "a file (${c.name})" }
                .distinct()
        }
        return Open(todos, failed, edits)
    }

    /** The note's text (to wrap with [ModeNote.wrap]): what's open, and what to do about it. */
    fun note(open: Open): String = buildString {
        val n = open.count
        append(MARK).append(n).append(if (n == 1) " open item" else " open items").append(" as you ended your turn:")
        if (open.todos.isNotEmpty()) {
            val shown = open.todos.take(MAX_NAMED).joinToString(", ") { "“${it.content.take(80)}”" }
            val more = if (open.todos.size > MAX_NAMED) " and ${open.todos.size - MAX_NAMED} more" else ""
            append("\n- Checklist items not marked completed: ").append(shown).append(more).append('.')
        }
        open.failedCommand?.let { f ->
            append("\n- Your last command exited with code ").append(f.exitCode)
            if (f.command.isNotBlank()) append(": `").append(f.command.lineSequence().first().take(120)).append('`')
            append('.')
        }
        if (open.unverifiedEdits.isNotEmpty()) {
            val shown = open.unverifiedEdits.take(MAX_NAMED).joinToString(", ")
            val more = if (open.unverifiedEdits.size > MAX_NAMED) " and ${open.unverifiedEdits.size - MAX_NAMED} more" else ""
            append("\n- Files changed after the last test or build, not checked since: ").append(shown).append(more).append('.')
        }
        append(
            "\nFinish what's left and run the check if you can. If something can't be done or checked now, or an item no longer applies, " +
                "say so plainly in your reply instead of claiming success; don't start unrelated work. Locus asks this only once per task.",
        )
    }

    /** The number of open items a completion-check note in [message] named, or null when it holds none. */
    fun openItemsIn(message: Message): Int? {
        if (message.role != Role.USER) return null
        return message.parts.asSequence().filterIsInstance<Part.Text>()
            .filter { ModeNote.isNote(it.text) }
            .firstNotNullOfOrNull { COUNT.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private data class Call(val index: Int, val name: String, val args: String, val result: Part.ToolResult)

    /** Only a command that ran has a status line; a refused or invalid call doesn't. */
    private fun ran(r: Part.ToolResult) = r.content.startsWith("Exit code") || r.content.startsWith("Stopped after")

    private fun arg(json: String, key: String): String? = runCatching {
        ((Json.parseToJsonElement(json) as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
