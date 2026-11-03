package dev.locus.core.database

import dev.locus.core.database.Sqlite3.Companion.literal
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/**
 * [CLAIM_RUN], the scheduled task single-flight claim, on a real SQLite with the current schema: who may
 * take a task, and that the second of two claims in a row gets nothing.
 */
class ClaimRunSqlTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(17).createAll())
        db.run(
            "INSERT INTO scheduled_tasks (id, title, prompt, intervalMinutes, enabled, createdAt) VALUES ('t', 'Weather', 'Rain?', 60, 1, 0);",
        )
    }

    /** Runs the claim for [workId] and returns the rows it changed. */
    private fun claim(workId: String, now: Long, staleBefore: Long): Int = db.run(
        CLAIM_RUN.replace(":workId", literal(workId)).replace(":now", "$now").replace(":staleBefore", "$staleBefore").replace(":id", literal("t")) +
            "; SELECT changes();",
    ).trim().toInt()

    private fun marker() = db.run("SELECT runWorkId, runConversationId, runStartedAt FROM scheduled_tasks WHERE id = 't';")

    @Test
    fun `a free task is claimed once, and the second claim gets nothing`() {
        assertEquals(1, claim("periodic", now = 1_000, staleBefore = 0))
        assertEquals(0, claim("once", now = 1_001, staleBefore = 1))
        assertEquals("periodic||1000", marker())
    }

    @Test
    fun `a claim with a chat holds the task until it is stale`() {
        db.run("UPDATE scheduled_tasks SET runWorkId = 'a', runConversationId = 'chat', runStartedAt = 500 WHERE id = 't';")
        assertEquals(0, claim("b", now = 1_000, staleBefore = 499))
        // Its own work's retry can't re-claim it either: the prompt may have gone out.
        assertEquals(0, claim("a", now = 1_000, staleBefore = 499))
        assertEquals(1, claim("b", now = 1_000, staleBefore = 500))
        assertEquals("b||1000", marker())
    }

    @Test
    fun `a claim without a chat is re-taken only by its own work`() {
        db.run("UPDATE scheduled_tasks SET runWorkId = 'a', runConversationId = NULL, runStartedAt = 500 WHERE id = 't';")
        assertEquals(0, claim("b", now = 1_000, staleBefore = 0))
        assertEquals(1, claim("a", now = 1_000, staleBefore = 0))
        assertEquals("a||1000", marker())
    }

    @Test
    fun `release and naming the chat apply only to the holder`() {
        assertEquals(1, claim("a", now = 1_000, staleBefore = 0))
        fun name(work: String) = db.run(
            SET_RUN_CONVERSATION.replace(":conversationId", literal("chat")).replace(":id", literal("t")).replace(":workId", literal(work)) + "; SELECT changes();",
        )
        fun release(work: String) = db.run(RELEASE_RUN.replace(":id", literal("t")).replace(":workId", literal(work)) + "; SELECT changes();")
        assertEquals("0", name("b"))
        assertEquals("1", name("a"))
        assertEquals("0", release("b"))
        assertEquals("a|chat|1000", marker())
        assertEquals("1", release("a"))
        assertEquals("||", marker())
    }
}
