package dev.locus.core.database

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The v19 → v20 migration (Needs you, docs/design/needs-you.md): `scheduled_tasks.lastSeenRunAt`, set to each
 * task's `lastRunAt` so older results don't all show as new, and a new `proposals` table that goes with its chat.
 * The claim that runs a proposal once, and the statement a restore uses to retire proposals from a backup, are
 * tested on the migrated table. A phone still on v18 takes 18 → 19 → 20 in one go; that path is covered too.
 */
class NeedsYouMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(19).createAll())
    }

    /** What Room runs to go from 19 to 20 (see the generated LocusDatabase_AutoMigration_19_20_Impl), then the spec's post-migrate step. */
    private fun migrate() = db.run(MIGRATE_19_20)

    private fun shape(table: String) =
        "SELECT name, type, \"notnull\", CASE WHEN dflt_value = 'NULL' THEN NULL ELSE dflt_value END, pk FROM pragma_table_info('$table');"
    private fun indices(table: String) = "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' AND name NOT LIKE 'sqlite_%' ORDER BY name;"
    private fun foreignKeys(table: String) = "SELECT \"table\", \"from\", \"to\", on_delete FROM pragma_foreign_key_list('$table');"

    private val chat = "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt) VALUES ('c', 'Scheduled: Inbox', 'p', 'prompt', 1, 2);"

    private fun task(id: String, lastRunAt: String) =
        "INSERT INTO scheduled_tasks (id, title, prompt, intervalMinutes, enabled, createdAt, lastRunAt, lastConversationId, lastSummary, lastOutcome, " +
            "runWorkId, runConversationId, runStartedAt, requiresCharging, unmeteredOnly, batteryNotLow, triggerJson, triggerStateJson) " +
            "VALUES ('$id', 'Inbox', 'Check mail', 60, 1, 1, $lastRunAt, 'c', 'Two new', 'DONE', NULL, NULL, NULL, 0, 0, 0, NULL, NULL);"

    private fun proposal(id: String, status: String = "PENDING", expiresAt: Long = 1_000) =
        "INSERT INTO proposals (id, conversationId, taskId, source, callId, toolName, argumentsJson, summary, reason, flags, taintSources, previewJson, " +
            "createdAt, expiresAt, status) VALUES ('$id', 'c', 't', 'Inbox', 'call-$id', 'email_send', '{\"to\":\"a@b.c\"}', 'Send email to a@b.c', NULL, " +
            "'MUST_CONFIRM', 'email_read', NULL, 10, $expiresAt, '$status');"

    @Test
    fun `existing tasks keep everything and their last result counts as seen`() {
        db.run(chat + task("t", "500") + task("never", "NULL"))
        val before = db.run("SELECT * FROM scheduled_tasks ORDER BY id;")
        migrate()
        // The new column comes last: what was there is unchanged, and each task's last run is marked seen.
        assertEquals(before.lines().joinToString("\n") { row -> row + "|" + (if (row.startsWith("t|")) "500" else "") }, db.run("SELECT * FROM scheduled_tasks ORDER BY id;"))
        assertEquals("0", db.run("SELECT COUNT(*) FROM proposals;"))
    }

    @Test
    fun `the schema matches a fresh v20 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(ExportedSchema(20).createAll()) }
        for (table in listOf("scheduled_tasks", "proposals")) {
            assertEquals(fresh.run(shape(table)), db.run(shape(table)), table)
            assertEquals(fresh.run(indices(table)), db.run(indices(table)), table)
            assertEquals(fresh.run(foreignKeys(table)), db.run(foreignKeys(table)), table)
        }
    }

    @Test
    fun `proposals belong to a chat that exists and go with it`() {
        migrate()
        db.run(chat + proposal("p"))
        assertEquals("p|c|PENDING|0", db.run("SELECT id, conversationId, status, dismissed FROM proposals;"))
        assertFailsWith<IllegalStateException> { db.run(proposal("q").replace("'c', 't'", "'missing', 't'")) }
        db.run("DELETE FROM conversations WHERE id = 'c';")
        assertEquals("0", db.run("SELECT COUNT(*) FROM proposals;"))
    }

    private fun claim(id: String, now: Long) = db.run(CLAIM_PROPOSAL.replace(":id", "'$id'").replace(":now", "$now") + "; SELECT changes();")

    @Test
    fun `a proposal is claimed once, never after its expiry, and never once declined`() {
        migrate()
        db.run(chat + proposal("p") + proposal("old", expiresAt = 100) + proposal("no", status = "DECLINED"))
        assertEquals("1", claim("p", now = 50))
        // A second tap (or a tap after process death) finds it running and changes nothing.
        assertEquals("0", claim("p", now = 51))
        assertEquals("RUNNING|50", db.run("SELECT status, decidedAt FROM proposals WHERE id = 'p';"))
        assertEquals("0", claim("old", now = 100))
        assertEquals("0", claim("no", now = 50))
        assertEquals("PENDING", db.run("SELECT status FROM proposals WHERE id = 'old';"))
    }

    @Test
    fun `a restored backup's waiting and running proposals are retired, decided ones kept`() {
        migrate()
        db.run(chat + proposal("wait") + proposal("run", status = "RUNNING") + proposal("done", status = "DONE") + proposal("no", status = "DECLINED"))
        db.run("$RETIRE_RESTORED_PROPOSALS;")
        assertEquals("done|DONE\nno|DECLINED\nrun|STALE\nwait|STALE", db.run("SELECT id, status FROM proposals ORDER BY id;"))
        assertEquals("0", claim("wait", now = 50))
    }

    @Test
    fun `from v18, chats and tasks are kept and every table matches a fresh v20 database`() {
        val old = Sqlite3(tmp.newFile("old.db")).apply {
            run(ExportedSchema(18).createAll())
            run(
                chat + "INSERT INTO scheduled_tasks (id, title, prompt, intervalMinutes, enabled, createdAt, lastRunAt, lastConversationId, lastSummary, lastOutcome, " +
                    "runWorkId, runConversationId, runStartedAt, requiresCharging, unmeteredOnly, batteryNotLow) " +
                    "VALUES ('t', 'Inbox', 'Check mail', 60, 1, 1, 7, 'c', 'Two new', 'DONE', NULL, NULL, NULL, 0, 0, 0);",
            )
        }
        old.run(TriggersMigrationTest.MIGRATE_18_19 + "\n" + MIGRATE_19_20)
        assertEquals("Scheduled: Inbox", old.run("SELECT title FROM conversations;"))
        assertEquals("7|7||", old.run("SELECT lastRunAt, lastSeenRunAt, triggerJson, triggerStateJson FROM scheduled_tasks;"))

        val fresh = Sqlite3(tmp.newFile("fresh20.db")).apply { run(ExportedSchema(20).createAll()) }
        val tables = "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name;"
        assertEquals(fresh.rows(tables), old.rows(tables))
        for (table in fresh.rows(tables)) {
            assertEquals(fresh.run(shape(table)), old.run(shape(table)), table)
            assertEquals(fresh.run(indices(table)), old.run(indices(table)), table)
            assertEquals(fresh.run(foreignKeys(table)), old.run(foreignKeys(table)), table)
        }
    }

    companion object {
        val MIGRATE_19_20 =
            "ALTER TABLE `scheduled_tasks` ADD COLUMN `lastSeenRunAt` INTEGER DEFAULT NULL;\n" +
                ExportedSchema(20).createSql("proposals") + ";\n" +
                "CREATE INDEX IF NOT EXISTS `index_proposals_conversationId` ON `proposals` (`conversationId`);\n" +
                "CREATE INDEX IF NOT EXISTS `index_proposals_status` ON `proposals` (`status`);\n" +
                NeedsYouMigration.MARK_SEEN + ";"
    }
}
