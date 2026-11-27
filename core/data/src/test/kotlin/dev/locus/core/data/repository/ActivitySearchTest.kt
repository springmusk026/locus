package dev.locus.core.data.repository

import dev.locus.core.database.AuditDao
import dev.locus.core.database.AuditEntity
import dev.locus.core.database.ConversationEntity
import dev.locus.core.model.AuditEntry
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Activity's text search through [ActivityRepository] (#316): text of three characters or more goes to the indexed
 * query, shorter text to the row-by-row one, chat titles and a receipt's helper chats are passed in as lists, and
 * writing a chat (a run moves `updatedAt` as it streams) doesn't start the search again unless the chats it names
 * change. The SQL itself is tested in core/database (AuditSearchSqlTest).
 */
class ActivitySearchTest {
    /** Which audit queries were started, in order. */
    private val started = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** A list as the test wrote it: without the placeholder ActivityRepository binds for an empty one. */
    private fun List<String>.real() = filter { it != "\u0000" }

    private fun counting(dao: AuditDao): AuditDao = object : AuditDao by dao {
        override fun observeSearch(
            match: String, pattern: String, textTools: List<String>, titleChats: List<String>, allChats: Boolean, chats: List<String>, since: Long,
            until: Long, allTools: Boolean, tools: List<String>, allDecisions: Boolean, decisions: List<String>, onlyErrors: Boolean, limit: Int,
        ) = dao.observeSearch(match, pattern, textTools, titleChats, allChats, chats, since, until, allTools, tools, allDecisions, decisions, onlyErrors, limit)
            .onStart { started += "index $match titles=${titleChats.real()} chats=${if (allChats) "all" else chats.real()}" }

        override fun observeSearchScan(
            pattern: String, textTools: List<String>, titleChats: List<String>, allChats: Boolean, chats: List<String>, since: Long,
            until: Long, allTools: Boolean, tools: List<String>, allDecisions: Boolean, decisions: List<String>, onlyErrors: Boolean, limit: Int,
        ) = dao.observeSearchScan(pattern, textTools, titleChats, allChats, chats, since, until, allTools, tools, allDecisions, decisions, onlyErrors, limit)
            .onStart { started += "scan $pattern" }

        override fun observeFiltered(
            since: Long, conversationId: String?, allTools: Boolean, tools: List<String>, allDecisions: Boolean, decisions: List<String>,
            onlyErrors: Boolean, pattern: String?, textTools: List<String>, limit: Int, until: Long, withHelpers: Boolean,
        ) = dao.observeFiltered(since, conversationId, allTools, tools, allDecisions, decisions, onlyErrors, pattern, textTools, limit, until, withHelpers)
            .onStart { started += "like $pattern" }
    }

    private suspend fun TestScope.setUp(): Pair<TestGraph, ActivityRepository> {
        val g = TestGraph(backgroundScope)
        val db = g.database
        db.conversationDao.upsert(ConversationEntity("en", "Kotlin build", "p", "", 1, 1))
        db.conversationDao.upsert(ConversationEntity("zh", "项目周报", "p", "", 1, 1))
        db.conversationDao.upsert(ConversationEntity("helper", "Research", "p", "", 1, 1, parentConversationId = "en"))
        db.auditDao.insert(AuditEntity("a1", "en", "c1", "shell", """{"command":"./gradlew build"}""", "AUTO_ALLOWED", "BUILD SUCCESSFUL", false, 1000, 1005))
        db.auditDao.insert(AuditEntity("a2", "zh", "c2", "shell", """{"command":"打开文件管理器"}""", "AUTO_ALLOWED", "找到三个文件", false, 2000, 2005))
        db.auditDao.insert(AuditEntity("a3", "helper", "c3", "web_search", """{"query":"kotlin flow"}""", "AUTO_ALLOWED", "Found 3", false, 3000, 3005))
        return g to ActivityRepository(counting(db.auditDao), g.secrets)
    }

    private fun List<AuditEntry>.ids() = map { it.id }

    /** Waits in real time (the repository's flows hop to other dispatchers) until [done], for at most five seconds. */
    private suspend fun until(done: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!done()) delay(10) }
    }

    @Test
    fun `text of three characters or more uses the index, with matching titles passed in`() = runTest {
        val (_, repo) = setUp()
        // "kotlin" is in a3's arguments and in chat en's title (so a1 too).
        assertEquals(listOf("a3", "a1"), repo.observe(AuditQuery(text = "kotlin"), 50).first().ids())
        assertEquals(listOf("index \"kotlin\" titles=[en] chats=all"), started)
        // Chinese: a substring of three characters inside a run with no spaces.
        assertEquals(listOf("a2"), repo.observe(AuditQuery(text = "文件管"), 50).first().ids())
        assertEquals("index \"文件管\" titles=[] chats=all", started.last())
    }

    @Test
    fun `shorter text, which has no trigram, is matched row by row`() = runTest {
        val (_, repo) = setUp()
        assertEquals(listOf("a2"), repo.observe(AuditQuery(text = "文件"), 50).first().ids())
        assertEquals(listOf("scan %文件%"), started)
        // A title match counts here too: 周报 is in chat zh's title.
        assertEquals(listOf("a2"), repo.observe(AuditQuery(text = "周报"), 50).first().ids())
    }

    @Test
    fun `a receipt's search covers the chat and its helpers`() = runTest {
        val (_, repo) = setUp()
        assertEquals(listOf("a3"), repo.observe(AuditQuery(text = "flow", conversationId = "en", withHelpers = true), 50).first().ids())
        assertEquals(listOf("index \"flow\" titles=[] chats=[en, helper]"), started)
        assertEquals(emptyList(), repo.observe(AuditQuery(text = "flow", conversationId = "en"), 50).first().ids())
    }

    @Test
    fun `writing a chat doesn't start the search again, renaming one into the results does`() = runTest {
        val (g, repo) = setUp()
        val shown = mutableListOf<List<String>>()
        backgroundScope.launch { repo.observe(AuditQuery(text = "kotlin"), 50).collect { synchronized(shown) { shown += it.ids() } } }
        until { synchronized(shown) { shown.isNotEmpty() } }
        // A run streaming into a chat moves its updatedAt over and over.
        repeat(20) { g.database.conversationDao.touch("zh", 10L + it) }
        g.database.conversationDao.upsert(ConversationEntity("zh", "Kotlin notes", "p", "", 1, 99))
        until { synchronized(shown) { shown.last() == listOf("a3", "a2", "a1") } }
        // Started twice: at first, and when the rename added a chat to the titles; never for the 20 writes.
        assertEquals(listOf("index \"kotlin\" titles=[en] chats=all", "index \"kotlin\" titles=[en, zh] chats=all"), started)
    }
}
