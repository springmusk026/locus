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
 * Activity's text search (#316) on a real SQLite with the v21 schema: the indexed query ([AUDIT_SEARCH]) reads the
 * log through `audit_fts` instead of scanning it, neither search query reads `conversations` (so a chat being written
 * doesn't run them again), and both return exactly what the old `LIKE` search ([AUDIT_SEARCH_LIKE]) returns, for
 * English, Spanish and Chinese text.
 */
class AuditSearchSqlTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(21).createAll())
    }

    private fun chat(id: String, title: String, parent: String? = null) =
        "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt, parentConversationId) " +
            "VALUES (${literal(id)}, ${literal(title)}, 'p', '', 1, 1, ${parent?.let(::literal) ?: "NULL"});"

    private var at = 0L

    private fun call(chat: String, tool: String, args: String, preview: String, decision: String = "AUTO_ALLOWED", error: Boolean = false): String {
        at += 1000
        return "INSERT INTO audit_log (id, conversationId, callId, toolName, argumentsJson, decision, resultPreview, isError, startedAt, finishedAt) " +
            "VALUES ('a$at', ${literal(chat)}, 'c$at', ${literal(tool)}, ${literal(args)}, ${literal(decision)}, ${literal(preview)}, ${if (error) 1 else 0}, $at, ${at + 5});"
    }

    /** Chats and calls in English, Spanish and Chinese, with mixed case, accents, LIKE wildcards and quotes. */
    private fun fixture() = db.run(
        chat("en", "Kotlin build fixes") + chat("es", "Plan de viaje a Málaga") + chat("zh", "项目周报") +
            chat("helper", "Helper: research", parent = "en") + chat("other", "Shopping list") +
            call("en", "read_file", """{"path":"src/main/kotlin/App.kt"}""", "package dev.locus\nfun main()") +
            call("en", "shell", """{"command":"./gradlew assembleDebug"}""", "BUILD SUCCESSFUL in 42s", error = false) +
            call("en", "web_search", """{"query":"KOTLIN coroutines flow"}""", "Found 10 results") +
            call("helper", "web_fetch", """{"url":"https://kotlinlang.org/docs/flow.html"}""", "Asynchronous Flow") +
            call("en", "shell", """{"command":"echo 100% done_now"}""", "100% done_now", error = true) +
            call("en", "write_file", """{"path":"notes.md","content":"say \"hi\" to the team"}""", "Wrote 1 line", decision = "USER_DENIED") +
            call("es", "web_search", """{"query":"vuelos baratos a Málaga en verano"}""", "Encontré 3 resultados: Árbol de Navidad, CAFÉ y café") +
            call("es", "read_file", """{"path":"viaje/ÁRBOL.txt"}""", "El árbol está en la plaza de España") +
            call("es", "email_send", """{"to":"ana@example.com","subject":"Reserva confirmada"}""", "Correo enviado a Ana") +
            call("zh", "shell", """{"command":"打开文件管理器"}""", "找到三个文件") +
            call("zh", "read_file", """{"path":"周报/第一周.md"}""", "本周完成了文件同步和搜索功能") +
            call("zh", "web_search", """{"query":"安卓 全文搜索 中文分词"}""", "找到 5 条结果") +
            call("other", "task_budget", """{"limit":10}""", "Continue", decision = "USER_ALLOWED"),
    )

    private val base = mapOf(
        "since" to "0", "until" to "9223372036854775807", "allTools" to "1", "tools" to "'-'", "allDecisions" to "1",
        "decisions" to "'-'", "onlyErrors" to "0", "limit" to "50",
    )

    /** [sql] with each `:name` replaced by its value from [values], as SQL literals. */
    private fun ids(sql: String, values: Map<String, String>): List<String> =
        db.rows(Regex(":([A-Za-z]+)").replace(sql.replace("SELECT *", "SELECT id")) { values[it.groupValues[1]] ?: error("no value for ${it.value}") } + ";")

    private fun like(s: String) = "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun list(items: List<String>) = items.ifEmpty { listOf("-") }.joinToString(", ") { literal(it) }

    /**
     * The old search and the new one for [text], in one view: every chat, one chat, or a chat and its helpers, with
     * [extra] filters. The new one gets its chat lists the way ActivityRepository gets them.
     */
    private fun compare(text: String, textTools: List<String> = emptyList(), chat: String? = null, withHelpers: Boolean = false, extra: Map<String, String> = emptyMap()) {
        val pattern = like(text)
        val old = ids(
            AUDIT_SEARCH_LIKE,
            base + extra + mapOf(
                "conversationId" to (chat?.let(::literal) ?: "NULL"), "withHelpers" to if (withHelpers) "1" else "0",
                "pattern" to literal(pattern), "textTools" to list(textTools),
            ),
        )
        val titles = db.rows("SELECT id FROM conversations WHERE title LIKE ${literal(pattern)} ESCAPE '\\' ORDER BY id;")
        val helpers = if (chat != null && withHelpers) db.rows("SELECT id FROM conversations WHERE parentConversationId = ${literal(chat)} ORDER BY id;") else emptyList()
        val values = base + extra + mapOf(
            "pattern" to literal(pattern), "textTools" to list(textTools), "titleChats" to list(titles),
            "allChats" to if (chat == null) "1" else "0", "chats" to list(listOfNotNull(chat) + helpers),
            "match" to literal("\"" + text.replace("\"", "\"\"") + "\""),
        )
        val label = "'$text' tools=$textTools chat=$chat helpers=$withHelpers $extra"
        assertEquals(old, ids(AUDIT_SEARCH_SCAN, values), "scan: $label")
        // The index needs a trigram: three characters or more (ActivityRepository only uses it then).
        if (text.codePointCount(0, text.length) >= 3) assertEquals(old, ids(AUDIT_SEARCH, values), "index: $label")
    }

    private val queries = listOf(
        // English: case folding, a path, a title, LIKE's wildcards and quotes taken literally.
        "kotlin", "KOTLIN", "Kotlin build", "src/main", "assembleDebug", "100%", "done_now", "_now", "say \"hi\"", "\"hi\"", "fixes", "zzz",
        // Spanish: accents, upper-case accented letters (LIKE folds ASCII only, and so does the search), a title.
        "Málaga", "málaga", "malaga", "árbol", "ÁRBOL", "Árbol", "café", "CAFÉ", "España", "viaje", "Reserva",
        // Chinese: no spaces between words; substrings of three or more characters go through the index.
        "文件管理器", "三个文件", "文件同步", "周报", "项目周报", "第一周", "全文搜索", "中文分词", "搜索功能",
        // Too short for a trigram: matched row by row.
        "文件", "搜", "a", "ab", "%", "_",
    )

    @Test
    fun `the search returns exactly what the LIKE search returned, in English, Spanish and Chinese`() {
        fixture()
        for (q in queries) {
            compare(q)
            compare(q, chat = "en")
            compare(q, chat = "en", withHelpers = true)
            compare(q, chat = "zh")
        }
        // Tool labels that matched (worked out in Kotlin) and the other filters.
        compare("search", textTools = listOf("web_search"))
        compare("búsqueda", textTools = listOf("web_search"), chat = "es")
        compare("文件", textTools = listOf("read_file"))
        val narrowed = mapOf("allTools" to "0", "tools" to "'shell', 'read_file'", "allDecisions" to "0", "decisions" to "'AUTO_ALLOWED', 'USER_ALLOWED'", "onlyErrors" to "0", "since" to "2000", "until" to "13000")
        for (q in listOf("kotlin", "文件管理器", "árbol", "100%", "Continue", "a")) {
            compare(q, extra = narrowed)
            compare(q, extra = narrowed + ("onlyErrors" to "1"))
            compare(q, extra = mapOf("limit" to "1"))
        }
    }

    @Test
    fun `the index finds what LIKE finds, including Chinese substrings unicode61 would miss`() {
        fixture()
        fun search(text: String) = ids(
            AUDIT_SEARCH,
            base + mapOf(
                "pattern" to literal(like(text)), "textTools" to list(emptyList()), "titleChats" to list(emptyList()), "allChats" to "1",
                "chats" to list(emptyList()), "match" to literal("\"$text\""),
            ),
        )
        assertEquals(listOf("a10000"), search("文件管理"))
        assertEquals(listOf("a11000"), search("文件同步"))
        assertEquals(listOf("a12000"), search("中文分词"))
        assertEquals(listOf("a3000"), search("coroutines"))
        // The index folds every letter's case, so "árbol" also finds a7000's "Árbol"; LIKE folds ASCII only, so its
        // check drops that row, as the old search did. "ÁRBOL" is "Árbol" to LIKE (Á itself, then ASCII letters).
        assertEquals(listOf("a8000"), search("árbol"))
        assertEquals(listOf("a8000", "a7000"), search("ÁRBOL"))
    }

    @Test
    fun `neither search query reads conversations`() {
        assertFalse("conversations" in AUDIT_SEARCH)
        assertFalse("conversations" in AUDIT_SEARCH_SCAN)
        assertTrue("conversations" in AUDIT_SEARCH_LIKE)
    }

    /** [sql] with its parameters bound as values SQLite doesn't know when it plans (as Room binds them). */
    private fun plan(sql: String): String {
        val names = Regex(":([A-Za-z]+)").findAll(sql).map { it.groupValues[1] }.toSet()
        val values = mapOf(
            "since" to "0", "until" to "9223372036854775807", "allTools" to "1", "tools" to "'x'", "allDecisions" to "1", "decisions" to "'x'",
            "onlyErrors" to "0", "limit" to "50", "pattern" to "'%item 4242%'", "match" to "'item'", "textTools" to "'x'", "titleChats" to "'x'",
            "allChats" to "1", "chats" to "'x'", "conversationId" to "NULL", "withHelpers" to "0",
        )
        val sets = names.joinToString("\n") { ".parameter set :$it ${values.getValue(it)}" }
        return db.rows(".parameter init\n$sets\nEXPLAIN QUERY PLAN $sql;").joinToString("\n")
    }

    @Test
    fun `on 50,000 calls the indexed search reads only the rows it found, and the old one scans the log`() {
        db.run(
            chat("main", "Main") +
                "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 50000) " +
                "INSERT INTO audit_log (id, conversationId, callId, toolName, argumentsJson, decision, resultPreview, isError, startedAt, finishedAt) " +
                "SELECT 'a' || i, 'chat' || (i % 200), 'c' || i, CASE WHEN i % 3 = 0 THEN 'web_search' ELSE 'read_file' END, " +
                "'{\"q\":\"item ' || i || '\"}', 'AUTO_ALLOWED', 'result ' || i, i % 13 = 0, i * 1000, i * 1000 + 5 FROM n;",
        )
        val indexed = plan(AUDIT_SEARCH)
        assertTrue("SCAN audit_fts VIRTUAL TABLE" in indexed, indexed)
        assertTrue("SEARCH audit_log USING INTEGER PRIMARY KEY (rowid=?)" in indexed, indexed)
        assertTrue("SEARCH audit_log USING COVERING INDEX index_audit_log_toolName (toolName=?)" in indexed, indexed)
        assertTrue("SEARCH audit_log USING COVERING INDEX index_audit_log_conversationId (conversationId=?)" in indexed, indexed)
        assertFalse(indexed.lines().any { it.contains("SCAN audit_log") }, indexed)
        // Before: the log is walked through the time index, every row in the span (here all 50,000), its JSON matched
        // with LIKE, and titles and helpers read from conversations.
        val old = plan(AUDIT_SEARCH_LIKE)
        assertTrue("SEARCH audit_log USING INDEX index_audit_log_startedAt" in old, old)
        assertFalse("audit_fts" in old, old)
        assertTrue("conversations" in old, old)

        // And the result is the window it should be: the matching calls, newest first.
        val found = ids(
            AUDIT_SEARCH,
            base + mapOf(
                "pattern" to literal("%item 4242%"), "textTools" to list(emptyList()), "titleChats" to list(emptyList()), "allChats" to "1",
                "chats" to list(emptyList()), "match" to literal("\"item 4242\""),
            ),
        )
        assertEquals(listOf("a42429", "a42428", "a42427", "a42426", "a42425", "a42424", "a42423", "a42422", "a42421", "a42420", "a4242"), found)
    }
}
