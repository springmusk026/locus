package dev.locus.core.data.repository

import dev.locus.core.agent.recoverTranscript
import dev.locus.core.data.agent.modelContext
import dev.locus.core.database.AuditStatRow
import dev.locus.core.database.ConversationEntity
import dev.locus.core.database.MessageStatRow
import dev.locus.core.database.TreeRow
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How a chat's stored tree becomes what is shown and sent. */
class BranchingTest {
    private fun msg(id: String, role: Role, vararg parts: Part, usage: TokenUsage? = null) =
        Message(id, "c", role, parts.toList(), createdAt = id.hashCode().toLong(), usage = usage)

    private fun text(id: String, role: Role, usage: TokenUsage? = null) = msg(id, role, Part.Text("$id text"), usage = usage)

    /** Rows as the DAO returns them: written order, each with its parent and the chat's active leaf. */
    private fun rows(leaf: String?, vararg messages: Pair<Message, String?>): List<TreeRow> = with(ConversationRepository) {
        messages.map { (m, parent) -> TreeRow(m.toEntity(parentId = parent), leaf) }
    }

    private fun thread(rows: List<TreeRow>) = with(ConversationRepository) { rows.toThread() }

    @Test
    fun `a chat migrated from v6 is sent exactly as before`() {
        val u1 = text("u1", Role.USER)
        val a1 = msg("a1", Role.ASSISTANT, Part.Reasoning("thinking"), Part.Text("Let me look."), Part.ToolCall("k1", "read_file", "{}"))
        val t1 = msg("t1", Role.TOOL, Part.ToolResult("k1", "read_file", "contents"))
        val a2 = text("a2", Role.ASSISTANT, usage = TokenUsage(100, 10))
        // The v7 backfill: each parent is the previous message; the leaf stays null.
        val shown = thread(rows(null, u1 to null, a1 to "u1", t1 to "a1", a2 to "t1"))
        assertEquals(listOf(u1, a1, t1, a2), shown.messages)
        assertTrue(shown.branches.isEmpty())
        // Reasoning is still stripped before sending.
        assertTrue(modelContext(shown.messages).none { m -> m.parts.any { it is Part.Reasoning } })
    }

    @Test
    fun `only the active branch is sent to the model`() {
        val u1 = text("u1", Role.USER)
        val old = text("old", Role.ASSISTANT)
        val u2 = text("u2", Role.USER)
        val oldReply = text("oldReply", Role.ASSISTANT)
        val regenerated = text("regenerated", Role.ASSISTANT)
        val all = arrayOf(u1 to null, old to "u1", u2 to "old", oldReply to "u2", regenerated to "u1")

        val onNew = thread(rows("regenerated", *all))
        assertEquals(listOf("u1", "regenerated"), modelContext(onNew.messages).map { it.id })
        assertEquals(1, onNew.branches.getValue("regenerated").index)
        assertEquals(2, onNew.branches.getValue("regenerated").count)

        val onOld = thread(rows("oldReply", *all))
        assertEquals(listOf("u1", "old", "u2", "oldReply"), modelContext(onOld.messages).map { it.id })
        assertEquals(0, onOld.branches.getValue("old").index)
    }

    @Test
    fun `a compaction checkpoint applies only to the branch it was written on`() {
        val u1 = text("u1", Role.USER)
        val a1 = text("a1", Role.ASSISTANT)
        val checkpoint = msg("cp", Role.USER, Part.Checkpoint("summary of u1 and a1", 2))
        val u2 = text("u2", Role.USER)
        val other = text("other", Role.USER)
        val all = arrayOf(u1 to null, a1 to "u1", checkpoint to "a1", u2 to "cp", other to "a1")

        val compacted = modelContext(thread(rows("u2", *all)).messages)
        assertEquals(2, compacted.size)
        assertTrue(compacted.first().text.contains("summary of u1 and a1"))
        assertEquals("u2", compacted.last().id)

        assertEquals(listOf("u1", "a1", "other"), modelContext(thread(rows("other", *all)).messages).map { it.id })
    }

    @Test
    fun `an inactive branch cut off mid-call doesn't affect recovery of the active one`() {
        val u1 = text("u1", Role.USER)
        val dangling = msg("d", Role.ASSISTANT, Part.ToolCall("k9", "run_command", "{}"))
        val done = text("done", Role.ASSISTANT)
        val all = arrayOf(u1 to null, dangling to "u1", done to "u1")

        val active = recoverTranscript(thread(rows("done", *all)).messages)
        assertTrue(active.missingResults.isEmpty())
        assertTrue(!active.unfinished)

        val switched = recoverTranscript(thread(rows("d", *all)).messages)
        assertEquals(listOf("k9"), switched.missingResults.map { it.callId })
        assertTrue(switched.unfinished)
    }

    @Test
    fun `forked chats get a marked title once`() = with(ConversationRepository) {
        assertEquals("Trip plan (fork)", forkTitle("Trip plan"))
        assertEquals("Trip plan (fork)", forkTitle("Trip plan (fork)"))
    }

    @Test
    fun `a regenerated reply is timed from the message it answers`() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 31).atStartOfDay(zone).toInstant().toEpochMilli()
        val stats = listOf(
            MessageStatRow("a", "USER", today, null),
            MessageStatRow("a", "ASSISTANT", today + 2_000, null, parentCreatedAt = today),
            // Regenerated a minute later: answers the same user message, not the reply before it.
            MessageStatRow("a", "ASSISTANT", today + 62_000, null, parentCreatedAt = today),
        )
        val report = AnalyticsRepository.buildReport(
            AnalyticsPeriod.All, 0, today + 100_000, stats, emptyList<AuditStatRow>(),
            listOf(ConversationEntity("a", "a", null, "", today, today)), AppSettings(), emptyList(), zone,
        )
        assertEquals(2, report.requests)
        assertEquals(62_000, report.replyP95Ms)
    }
}
