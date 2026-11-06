package dev.locus.core.data.repository

import dev.locus.core.agent.SavedOutput
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.database.AuditDao
import dev.locus.core.database.ConversationDao
import dev.locus.core.database.MessageDao
import dev.locus.core.database.UsageEventDao
import dev.locus.core.database.UsageEventEntity
import dev.locus.core.model.FileTrees
import dev.locus.core.model.UsageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When temporary chats ([dev.locus.core.model.Conversation.temporary]) are deleted (docs/design/temporary-chats.md):
 *
 * - When the user leaves one (its screen closes), at once, unless a task is still running in it.
 * - A task that's still running finishes first; the chat goes as soon as it does, and after
 *   [RUNNING_LIMIT_MS] (24 hours) at most: then the task is stopped and the chat deleted.
 * - At start-up ([sweep]), every temporary chat no screen has open: the process ended (killed, updated,
 *   restored from a backup) with nothing running, so nothing is left to finish.
 *
 * What it costs stays: each request's tokens and model are kept as usage rows linked to no chat, so
 * Insights and the spend still count them. Everything else goes: messages and branches, its helpers'
 * chats, its tool calls in Activity, its "Allow for this chat" approvals, and the long outputs it saved
 * under `.locus/out/`. Files it wrote elsewhere in the workspace stay, as the chat says.
 */
@Singleton
class TemporaryChats @Inject constructor(
    private val chats: ConversationDao,
    private val messages: MessageDao,
    private val usageEvents: UsageEventDao,
    private val audit: AuditDao,
    private val conversations: ConversationRepository,
    private val runner: AgentRunner,
    private val workspace: Workspace,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Open screens per chat (a chat can be on two screens in a moment of navigation). */
    private val open = ConcurrentHashMap<String, Int>()

    /** [RUNNING_LIMIT_MS]; shorter in tests, whose model streams would time out long before a day. */
    internal var runningLimitMs: Long = RUNNING_LIMIT_MS

    /** Chats left with a task running: deleted when it ends. */
    private val waiting = ConcurrentHashMap<String, Job>()

    /** A chat screen opened [id]: it isn't deleted while open. */
    fun opened(id: String) {
        open.merge(id, 1, Int::plus)
        waiting.remove(id)?.cancel()
    }

    /** A chat screen for [id] closed. A temporary chat no other screen has open is deleted once idle. */
    fun left(id: String) {
        open.computeIfPresent(id) { _, n -> (n - 1).takeIf { it > 0 } }
        if (open.containsKey(id)) return
        scope.launch {
            val chat = chats.get(id) ?: return@launch
            if (chat.hidden && chat.parentConversationId == null) deleteWhenIdle(id)
        }
    }

    /**
     * At start-up: deletes temporary chats left over from the last run that no screen has open (a chat
     * shown again after Android restored the app keeps its own until the user leaves it). Returns how many.
     */
    suspend fun sweep(): Int {
        var deleted = 0
        for (chat in chats.temporaryChats()) {
            if (open.containsKey(chat.id) || waiting.containsKey(chat.id)) continue
            if (runner.states.value[chat.id]?.running == true) deleteWhenIdle(chat.id) else if (delete(chat.id)) deleted++
        }
        return deleted
    }

    private fun deleteWhenIdle(id: String) {
        if (runner.states.value[id]?.running != true) {
            scope.launch { if (!open.containsKey(id)) delete(id) }
            return
        }
        val job = scope.launch {
            val idle = withTimeoutOrNull(runningLimitMs) { runner.states.first { it[id]?.running != true } }
            // Still running a day later: the user isn't coming back for it.
            if (idle == null) runner.cancel(id)
            if (!open.containsKey(id)) delete(id)
        }
        waiting.put(id, job)?.cancel()
        job.invokeOnCompletion { waiting.remove(id, job) }
    }

    /**
     * Deletes temporary chat [id] now, with its helpers, keeping only what it cost. Returns false when
     * there's no such chat, or it isn't temporary (a normal chat is never deleted here).
     */
    suspend fun delete(id: String): Boolean {
        val root = chats.get(id)?.takeIf { it.hidden && it.parentConversationId == null } ?: return false
        val all = buildList {
            val queue = ArrayDeque(listOf(root.id))
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (next in this) continue
                add(next)
                chats.children(next).forEach { queue.add(it.id) }
            }
        }
        val spilled = HashSet<String>()
        for (chatId in all) {
            try {
                val rows = messages.tree(chatId).map { it.message }
                // A forked copy's usage was counted on its source already (MessageEntity.copiedFrom).
                val kept = rows.filter { it.role == "ASSISTANT" && it.copiedFrom == null && (it.usageJson != null || it.providerId != null) }.map { m ->
                    UsageEventEntity(
                        conversationId = null,
                        source = (if (chatId == root.id) UsageSource.CHAT else UsageSource.HELPER).wire,
                        createdAt = m.createdAt,
                        providerId = m.providerId,
                        model = m.model,
                        usageJson = m.usageJson,
                        totalMs = m.totalMs,
                    )
                }
                if (kept.isNotEmpty()) usageEvents.insertAll(kept)
                usageEvents.detach(chatId)
                rows.forEach { m -> SPILL.findAll(m.partsJson).forEach { spilled += it.groupValues[1] } }
                audit.deleteForConversation(chatId)
                conversations.delete(chatId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next start-up's sweep tries again.
            }
        }
        spilled.forEach(::deleteSpill)
        return true
    }

    /** One saved long output (`.locus/out/<name>`), only if it's a plain file of Locus's own naming. */
    private fun deleteSpill(name: String) {
        runCatching {
            val dir = File(workspace.base, SavedOutput.DIR)
            if (FileTrees.isLink(dir) || FileTrees.isLink(dir.parentFile ?: return)) return
            val file = File(dir, name)
            if (file.isFile && !FileTrees.isLink(file)) file.delete()
        }
    }

    companion object {
        /** How long a left temporary chat's running task may go on before it's stopped and the chat deleted. */
        const val RUNNING_LIMIT_MS = 24L * 60 * 60 * 1000

        /** A saved output's path in a tool result: `.locus/out/20261101-142233-3fa-web_read.log`. */
        private val SPILL = Regex("""\.locus/out/((?:cmd-)?\d{8}-\d{6}-[0-9a-f]{3}(?:-[A-Za-z0-9_-]+)?\.log)""")
    }
}
