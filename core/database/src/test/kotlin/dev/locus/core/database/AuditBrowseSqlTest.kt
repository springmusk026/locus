package dev.locus.core.database

import dev.locus.core.database.Sqlite3.Companion.literal
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Activity screen's queries on a real SQLite with the current schema and 50,000 audit rows: the browse
 * queries (no text) read the log through its indexes and don't touch `conversations`, so Room doesn't run them
 * again for every chat write, and they return what the general query returns.
 */
class AuditBrowseSqlTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(21).createAll())
        db.run(
            "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt) VALUES ('main', 'Main', 'p', '', 1, 1);" +
                "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt, parentConversationId) VALUES ('helper', 'Helper', 'p', '', 1, 1, 'main');" +
                "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 50000) " +
                "INSERT INTO audit_log (id, conversationId, callId, toolName, argumentsJson, decision, resultPreview, isError, startedAt, finishedAt) " +
                // Most rows in one big chat; a few in 'main' and its helper.
                "SELECT 'a' || i, CASE WHEN i % 5000 = 0 THEN 'main' WHEN i % 7777 = 0 THEN 'helper' ELSE 'chat' || (i % 200) END, 'c' || i, " +
                "CASE WHEN i % 3 = 0 THEN 'web_search' ELSE 'read_file' END, '{\"q\":\"item ' || i || '\"}', " +
                "CASE WHEN i % 11 = 0 THEN 'USER_DENIED' ELSE 'AUTO_ALLOWED' END, 'result ' || i, i % 13 = 0, i * 1000, i * 1000 + 5 FROM n;",
        )
    }

    private val params = mapOf(
        "since" to "0",
        "until" to "9223372036854775807",
        "allTools" to "1",
        "tools" to "'x'",
        "allDecisions" to "1",
        "decisions" to "'x'",
        "onlyErrors" to "0",
        "limit" to "50",
        "conversationId" to literal("main"),
    )

    /**
     * [sql] run with its `:name` parameters bound the way Room binds them, as values SQLite doesn't know
     * when it plans the query (a literal in its place would let it fold `:x IS NULL` away).
     */
    private fun bound(sql: String, values: Map<String, String> = params): String {
        val names = Regex(":([A-Za-z]+)").findAll(sql).map { it.groupValues[1] }.toSet()
        val sets = names.joinToString("\n") { ".parameter set :$it ${values[it] ?: error("no value for :$it")}" }
        return ".parameter init\n$sets\n$sql"
    }

    private fun plan(sql: String, values: Map<String, String> = params): String =
        db.rows(bound("EXPLAIN QUERY PLAN $sql;", values)).joinToString("\n")

    @Test
    fun `browsing every chat reads the log in time order, with no sort and no scan of the table`() {
        val plan = plan(AUDIT_BROWSE_ALL)
        assertTrue("index_audit_log_startedAt" in plan, plan)
        assertFalse("TEMP B-TREE" in plan, plan)
        assertFalse(plan.lines().any { it.contains("SCAN audit_log") && !it.contains("USING INDEX") }, plan)
    }

    @Test
    fun `browsing one chat finds its rows through the conversation index`() {
        val plan = plan(AUDIT_BROWSE_CHAT)
        assertTrue("SEARCH audit_log USING INDEX index_audit_log_conversationId (conversationId=?)" in plan, plan)
    }

    @Test
    fun `the old query with its OR on the chat can't use the conversation index`() {
        // What made a one-chat view read the whole log: the plan the general query has for it.
        val general = "SELECT * FROM audit_log WHERE startedAt >= :since AND startedAt <= :until " +
            "AND (:conversationId IS NULL OR conversationId = :conversationId) ORDER BY startedAt DESC LIMIT :limit"
        val plan = plan(general)
        assertFalse("index_audit_log_conversationId" in plan, plan)
    }

    @Test
    fun `only the query for a chat's helpers, and the text search, read conversations`() {
        assertFalse("conversations" in AUDIT_BROWSE_ALL)
        assertFalse("conversations" in AUDIT_BROWSE_CHAT)
        assertTrue("conversations" in AUDIT_BROWSE_CHAT_AND_HELPERS)
        val helpers = plan(AUDIT_BROWSE_CHAT_AND_HELPERS)
        assertTrue("index_audit_log_conversationId" in helpers, helpers)
    }

    @Test
    fun `the browse queries return what the general query returns`() {
        val general = "SELECT id FROM audit_log WHERE startedAt >= :since AND startedAt <= :until " +
            "AND (:conversationId IS NULL OR conversationId = :conversationId " +
            "OR (:withHelpers AND conversationId IN (SELECT id FROM conversations WHERE parentConversationId = :conversationId))) " +
            "AND (:allTools OR toolName IN (:tools)) AND (:allDecisions OR (decision IN (:decisions) AND toolName != 'task_budget')) " +
            "AND (NOT :onlyErrors OR isError = 1) ORDER BY startedAt DESC LIMIT :limit"
        fun ids(sql: String, values: Map<String, String>) = db.rows(bound(sql.replace("SELECT *", "SELECT id") + ";", params + values))

        val narrowed = mapOf("allTools" to "0", "tools" to "'web_search'", "allDecisions" to "0", "decisions" to "'USER_DENIED'", "onlyErrors" to "1", "since" to "100000", "until" to "40000000")
        for (extra in listOf(emptyMap(), narrowed)) {
            assertEquals(ids(general, extra + mapOf("conversationId" to "NULL", "withHelpers" to "0")), ids(AUDIT_BROWSE_ALL, extra), "all chats $extra")
            assertEquals(ids(general, extra + mapOf("withHelpers" to "0")), ids(AUDIT_BROWSE_CHAT, extra), "one chat $extra")
            assertEquals(ids(general, extra + mapOf("withHelpers" to "1")), ids(AUDIT_BROWSE_CHAT_AND_HELPERS, extra), "chat and helpers $extra")
        }
        // The window is real: 50 rows, newest first.
        val all = ids(AUDIT_BROWSE_ALL, emptyMap())
        assertEquals(50, all.size)
        assertEquals("a50000", all.first())
        assertTrue(ids(AUDIT_BROWSE_CHAT_AND_HELPERS, mapOf("limit" to "1000")).size > ids(AUDIT_BROWSE_CHAT, mapOf("limit" to "1000")).size)
    }
}
