package dev.locus.core.data.repository

import dev.locus.core.data.repository.ConversationRepository.Companion.toEntity
import dev.locus.core.data.repository.ConversationRepository.Companion.toThread
import dev.locus.core.database.ConversationEntity
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ChatThread
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import dev.locus.core.testing.FakeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chat screen's read path (docs/design/transcript-reads.md): the same thread a full read gives,
 * minus provider payloads, emitted only when this chat changes, reading only new rows.
 */
class TranscriptReaderTest {
    private val db = FakeDatabase()
    private val messages = db.messageDao
    private val reader = TranscriptReader(messages::observeVersion, messages::viewRows, TranscriptReader::decode)

    private suspend fun chat(id: String) = db.conversationDao.upsert(ConversationEntity(id, "t", null, "system", 0, 0))

    private var n = 0

    private fun message(chat: String, role: Role, text: String, payload: String? = null) = Message(
        "m${n++}", chat, role, listOf(Part.Text(text)), createdAt = n.toLong(),
        format = ApiFormat.OPENAI_RESPONSES.takeIf { payload != null }, rawPayload = payload,
        usage = TokenUsage(100, 10).takeIf { role == Role.ASSISTANT },
        reply = ReplyInfo("p", "model", 10, 20).takeIf { role == Role.ASSISTANT },
    )

    /** Adds [message] after the chat's leaf (or after [parent]) and makes it the leaf, as the repository does. */
    private suspend fun add(message: Message, parent: String? = null): Message {
        messages.insertAsLeaf(message.toEntity(parentId = parent ?: messages.leaf(message.conversationId)), message.createdAt)
        return message
    }

    /** What the screen saw: every emission of [chat]'s thread while [block] ran. */
    private suspend fun watching(chat: String, block: suspend () -> Unit): List<ChatThread> {
        val seen = mutableListOf<ChatThread>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        reader.observe(chat).onEach { seen += it }.launchIn(scope)
        settle()
        block()
        settle()
        scope.cancel()
        return seen
    }

    private suspend fun settle() = repeat(5) { yield() }

    private suspend fun fullRead(chat: String): ChatThread = messages.tree(chat).toThread()

    private fun ChatThread.withoutPayloads() = copy(messages = messages.map { it.copy(rawPayload = null) })

    @Test
    fun `shows what a full read shows, without provider payloads`() = runTest {
        chat("c")
        val u1 = add(message("c", Role.USER, "hi"))
        val a1 = add(message("c", Role.ASSISTANT, "first", payload = "{\"encrypted\":\"…\"}"))
        // A regenerated reply: a second branch under u1.
        add(message("c", Role.ASSISTANT, "second", payload = "{\"encrypted\":\"…\"}"), parent = u1.id)
        add(message("c", Role.USER, "more"))

        val seen = watching("c") {}
        val full = fullRead("c")
        assertTrue(full.messages.any { it.rawPayload != null }, "requests keep their payloads")
        assertEquals(full.withoutPayloads(), seen.last())
        assertTrue(seen.last().messages.all { it.rawPayload == null })
        assertEquals(2, seen.last().branches.getValue(seen.last().messages[1].id).count)

        // Switching back to the first branch: the path changes without reading any row again.
        val rowsBefore = db.viewRowReads
        val switched = watching("c") { db.conversationDao.setActiveLeaf("c", a1.id) }
        assertEquals(fullRead("c").withoutPayloads(), switched.last())
        assertEquals(listOf(u1.id, a1.id), switched.last().messages.map { it.id })
        // The new watcher read the chat once (4 rows); the switch itself read nothing.
        assertEquals(rowsBefore + 4, db.viewRowReads)
    }

    @Test
    fun `other chats and touches don't re-emit or re-read`() = runTest {
        chat("open")
        chat("helper")
        add(message("open", Role.USER, "hi"))
        add(message("open", Role.ASSISTANT, "hello"))
        val rowsBefore = db.viewRowReads
        val seen = watching("open") {
            repeat(10) {
                add(message("helper", Role.ASSISTANT, "step $it"))
                db.conversationDao.touch("helper", it.toLong())
                db.conversationDao.touch("open", it.toLong())
                db.conversationDao.rename("open", "title $it")
            }
        }
        assertEquals(1, seen.size, "only the first read")
        assertEquals(rowsBefore + 2, db.viewRowReads)
    }

    @Test
    fun `new messages read only themselves`() = runTest {
        chat("c")
        repeat(50) { add(message("c", if (it % 2 == 0) Role.USER else Role.ASSISTANT, "message $it")) }
        val rowsBefore = db.viewRowReads
        val seen = watching("c") { repeat(5) { add(message("c", Role.ASSISTANT, "new $it")) } }
        assertEquals(55, seen.last().messages.size)
        assertEquals(fullRead("c").withoutPayloads(), seen.last())
        assertEquals(rowsBefore + 50 + 5, db.viewRowReads)
        // Unchanged messages keep their decoded instances, so the screen can compare them by identity.
        assertTrue(seen.first().messages.zip(seen.last().messages).all { (a, b) -> a === b })
    }

    @Test
    fun `a deleted chat empties, and an empty chat shows nothing`() = runTest {
        chat("c")
        add(message("c", Role.USER, "hi"))
        val seen = watching("c") { db.conversationDao.delete("c") }
        assertEquals(1, seen.first().messages.size)
        assertTrue(seen.last().messages.isEmpty())

        chat("empty")
        val none = watching("empty") {}
        assertTrue(none.single().messages.isEmpty())
        assertNull(messages.leaf("empty"))
    }

    /**
     * The measurement behind docs/design/transcript-reads.md: a 400-message chat stays open while a
     * helper chat works (40 steps, each saved and touched) and the open chat gets 10 messages. Before,
     * Room re-ran the full query (every row, every column, payloads included) on each of those writes.
     */
    @Test
    fun `read cost while another chat works`() = runTest {
        val payload = "x".repeat(4_000)
        chat("open")
        chat("helper")
        repeat(400) { add(message("open", if (it % 2 == 0) Role.USER else Role.ASSISTANT, "message $it", payload = payload.takeIf { _ -> it % 2 == 1 })) }

        val versionsBefore = db.versionReads
        val rowsBefore = db.viewRowReads
        val seen = watching("open") {
            repeat(40) {
                add(message("helper", Role.ASSISTANT, "step $it"))
                db.conversationDao.touch("helper", it.toLong())
                if (it % 4 == 0) add(message("open", Role.ASSISTANT, "reply $it", payload = payload))
            }
        }
        // Each run of the version query stands for one run of the old query, which read the whole open
        // chat (at least 400 rows, 200 of them with a 4,000-character payload) and emitted.
        val invalidations = db.versionReads - versionsBefore
        val newRows = db.viewRowReads - rowsBefore
        println(
            "transcript reads: $invalidations invalidations. Before: $invalidations emissions, ≥${invalidations * 400} rows, " +
                "≥${invalidations * 200 * 4_000L / 1_000_000} M payload chars. After: ${seen.size} emissions, $newRows rows, 0 payload chars",
        )
        assertEquals(1 + 10, seen.size, "the first read and one per message of this chat")
        assertEquals(400 + 10, newRows)
        assertTrue(invalidations >= 80)
    }
}
