package dev.locus.core.agent.integrations

/**
 * A conservative look at Postgres SQL text before it is sent to a database.
 *
 * It is not a parser. [readOnlyProblem] is a second fence in front of Supabase's read-only query
 * endpoint, which already runs as `supabase_read_only_user` (a role with `pg_read_all_data` and
 * `default_transaction_read_only = on`). It refuses anything that could switch the transaction to
 * read-write, run several statements, hide code in dollar quotes, or call functions with side
 * effects. When in doubt it refuses, and the agent can use the confirmed write tool instead.
 */
internal object SqlCheck {
    /** Statements a read-only query may start with. */
    private val READ_STARTS = setOf("select", "with", "explain", "show", "values", "table")

    /**
     * Words that write, lock or change the session when they appear inside one SELECT, WITH or EXPLAIN
     * statement (data-modifying CTEs, SELECT INTO, FOR UPDATE, EXPLAIN ANALYZE). Separate statements
     * such as BEGIN or SET are already refused by the one-statement rule.
     */
    private val WRITE_WORDS = setOf(
        "insert", "update", "delete", "merge", "truncate", "drop", "alter", "create", "grant", "revoke", "copy", "call", "do",
        "set", "reset", "begin", "commit", "rollback", "savepoint", "lock", "vacuum", "analyze", "analyse", "cluster", "reindex",
        "refresh", "listen", "notify", "prepare", "execute", "discard", "into", "import", "checkpoint",
    )

    /** Calls with side effects, or that run SQL from a string, that a SELECT could make. */
    private val RISKY_CALLS = listOf(
        "set_config", "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf", "pg_rotate_logfile", "pg_switch_wal",
        "pg_create_restore_point", "pg_promote", "pg_sleep\\w*", "pg_advisory\\w*", "pg_try_advisory\\w*", "pg_notify",
        "pg_read_file", "pg_read_binary_file", "pg_ls_\\w*", "pg_stat_file", "pg_file_\\w*", "lo_\\w*", "dblink\\w*",
        "nextval", "setval", "query_to_xml\\w*", "cursor_to_xml\\w*", "http\\w*",
    ).map { Regex("\\b$it\\s*\\(", RegexOption.IGNORE_CASE) }

    /** Extension schemas whose functions act outside the query (jobs, HTTP, secrets). */
    private val RISKY_SCHEMAS = Regex("\\b(cron|net|vault|pgsodium|extensions|dblink)\\s*\\.\\s*[A-Za-z_\"][\\w\"]*\\s*\\(", RegexOption.IGNORE_CASE)

    /** Supabase Vault and pgsodium: decrypted secrets must not reach the model. */
    private val SECRET_SCHEMAS = Regex("\\b(vault|pgsodium)\\s*\\.", RegexOption.IGNORE_CASE)

    /** A quoted name used as a function (its real name was blanked out by [strip]). */
    private val QUOTED_CALL = Regex("\"\"\\s*\\(")

    /** Words that drop, empty, overwrite or hand out data; used to word the confirmation of a write. */
    private val DESTRUCTIVE_WORDS = setOf("drop", "truncate", "delete", "update", "alter", "grant", "revoke", "merge", "reassign", "rename", "replace")

    /** Why [sql] can't go to the read-only endpoint, or null if it looks like one plain read. */
    fun readOnlyProblem(sql: String): String? {
        val code = strip(sql) ?: return "The query has an unclosed quote or comment."
        if (Regex("\\$(?!\\d)").containsMatchIn(code)) return "Dollar-quoted strings aren't allowed in read-only queries."
        val body = code.trim().trimEnd(';', ' ', '\n', '\t', '\r')
        if (body.isEmpty()) return "The query is empty."
        if (';' in body) return "Send one statement per call."
        val words = words(body)
        if (words.firstOrNull() !in READ_STARTS) return "A read-only query must start with SELECT, WITH, EXPLAIN, SHOW, VALUES or TABLE."
        words.firstOrNull { it in WRITE_WORDS }?.let { return "'${it.uppercase()}' isn't allowed in a read-only query." }
        val call = RISKY_CALLS.firstNotNullOfOrNull { it.find(body) } ?: RISKY_SCHEMAS.find(body)
        call?.let { return "Calling ${it.value.substringBefore('(').trim()} isn't allowed in a read-only query." }
        if (QUOTED_CALL.containsMatchIn(body)) return "Call functions by their plain name in a read-only query."
        if (SECRET_SCHEMAS.containsMatchIn(body) || Regex("\"(vault|pgsodium)\"", RegexOption.IGNORE_CASE).containsMatchIn(sql)) return "The vault and pgsodium schemas hold secrets; they aren't read through the agent."
        return null
    }

    /** Destructive keywords in [sql] (outside strings and comments), upper-cased, for a confirmation message. */
    fun destructiveWords(sql: String): List<String> {
        // If the text can't be stripped, scan all of it: a false alarm is better than a missed DROP.
        val code = strip(sql) ?: sql
        return words(code).filter { it in DESTRUCTIVE_WORDS }.distinct().map { it.uppercase() }
    }

    private fun words(code: String) = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(code).map { it.value.lowercase() }.toList()

    /**
     * [sql] with comments removed and string literals and quoted identifiers replaced by an empty
     * placeholder, or null if a quote or comment never closes. Block comments nest, as in Postgres;
     * backslash escapes count only in E'' strings (standard_conforming_strings is on).
     */
    fun strip(sql: String): String? {
        val out = StringBuilder()
        var i = 0
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            val next = if (i + 1 < n) sql[i + 1] else '\u0000'
            when {
                c == '-' && next == '-' -> {
                    while (i < n && sql[i] != '\n') i++
                    out.append(' ')
                }
                c == '/' && next == '*' -> {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        if (sql[i] == '/' && i + 1 < n && sql[i + 1] == '*') {
                            depth++
                            i += 2
                        } else if (sql[i] == '*' && i + 1 < n && sql[i + 1] == '/') {
                            depth--
                            i += 2
                        } else {
                            i++
                        }
                    }
                    if (depth > 0) return null
                    out.append(' ')
                }
                c == '\'' || c == '"' -> {
                    val escapes = c == '\'' && i > 0 && (sql[i - 1] == 'E' || sql[i - 1] == 'e') &&
                        (i < 2 || !(sql[i - 2].isLetterOrDigit() || sql[i - 2] == '_'))
                    if (escapes) out.setLength(out.length - 1) // the E prefix belongs to the literal
                    i++
                    var closed = false
                    while (i < n) {
                        val ch = sql[i]
                        if (escapes && ch == '\\') {
                            i += 2
                            continue
                        }
                        if (ch == c) {
                            if (i + 1 < n && sql[i + 1] == c) {
                                i += 2
                                continue
                            }
                            i++
                            closed = true
                            break
                        }
                        i++
                    }
                    if (!closed) return null
                    out.append(if (c == '\'') " '' " else "\"\"")
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
