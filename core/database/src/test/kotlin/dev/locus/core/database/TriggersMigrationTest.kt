package dev.locus.core.database

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The v18 → v19 migration (event triggers, docs/design/event-triggers.md): two nullable columns on
 * `scheduled_tasks` (the trigger and its source's state) and a new `trigger_events` table that goes
 * with its task. Every existing task stays on its schedule (no trigger) with its run columns untouched.
 * A phone still on v17 takes 17 → 18 (chats' `chatOnly`) and then 18 → 19 in one go; that path is
 * covered too.
 */
class TriggersMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(18).createAll())
    }

    /** What Room runs to go from 18 to 19 (see the generated LocusDatabase_AutoMigration_18_19_Impl). */
    private fun migrate() = db.run(MIGRATE_18_19)

    // Room's migration adds the nullable columns with DEFAULT NULL; a fresh table has no default. Room's
    // schema check doesn't compare defaults an entity doesn't declare, so the two read the same.
    private fun shape(table: String) =
        "SELECT name, type, \"notnull\", CASE WHEN dflt_value = 'NULL' THEN NULL ELSE dflt_value END, pk FROM pragma_table_info('$table');"
    private fun indices(table: String) = "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' AND name NOT LIKE 'sqlite_%' ORDER BY name;"
    private fun foreignKeys(table: String) = "SELECT \"table\", \"from\", \"to\", on_delete FROM pragma_foreign_key_list('$table');"

    private val task = "INSERT INTO scheduled_tasks (id, title, prompt, intervalMinutes, enabled, createdAt, lastRunAt, lastConversationId, lastSummary, lastOutcome, " +
        "runWorkId, runConversationId, runStartedAt, requiresCharging, unmeteredOnly, batteryNotLow) " +
        "VALUES ('t', 'Weather', 'Will it rain?', 60, 1, 1, 2, 'c', 'No rain', 'DONE', 'w', 'c', 3, 1, 0, 1);"

    @Test
    fun `existing tasks keep everything and stay on their schedule`() {
        db.run(task)
        val before = db.run("SELECT * FROM scheduled_tasks;")
        migrate()
        assertEquals("$before||", db.run("SELECT * FROM scheduled_tasks;"))
        assertEquals("0", db.run("SELECT COUNT(*) FROM trigger_events;"))
    }

    @Test
    fun `the schema matches a fresh v19 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(ExportedSchema(19).createAll()) }
        for (table in listOf("scheduled_tasks", "trigger_events")) {
            assertEquals(fresh.run(shape(table)), db.run(shape(table)), table)
            assertEquals(fresh.run(indices(table)), db.run(indices(table)), table)
            assertEquals(fresh.run(foreignKeys(table)), db.run(foreignKeys(table)), table)
        }
    }

    @Test
    fun `events belong to a task that exists and go with it`() {
        migrate()
        db.run(task)
        db.run(
            "UPDATE scheduled_tasks SET triggerJson = '{\"source\":\"NTFY\"}' WHERE id = 't';" +
                "INSERT INTO trigger_events (id, taskId, source, receivedAt, title, payload, decision) VALUES ('e', 't', 'NTFY', 10, 'Backup failed', 'text', 'FIRED');",
        )
        assertEquals("e|t|NTFY|10|Backup failed|text|FIRED|||", db.run("SELECT * FROM trigger_events;"))
        assertFailsWith<IllegalStateException> {
            db.run("INSERT INTO trigger_events (id, taskId, source, receivedAt, title, payload, decision) VALUES ('f', 'missing', 'NTFY', 1, 'x', '', 'FIRED');")
        }
        db.run("DELETE FROM scheduled_tasks WHERE id = 't';")
        assertEquals("0", db.run("SELECT COUNT(*) FROM trigger_events;"))
    }

    @Test
    fun `the claim still works on a migrated task`() {
        migrate()
        db.run(task.replace("'w', 'c', 3", "NULL, NULL, NULL"))
        db.run(CLAIM_RUN.replace(":workId", "'w2'").replace(":now", "100").replace(":staleBefore", "0").replace(":id", "'t'") + ";")
        assertEquals("w2|100", db.run("SELECT runWorkId, runStartedAt FROM scheduled_tasks;"))
    }

    @Test
    fun `from v17, chats and tasks are kept and every table matches a fresh v19 database`() {
        val old = Sqlite3(tmp.newFile("old.db")).apply {
            run(ExportedSchema(17).createAll())
            run(
                "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt, toolDiscovery) " +
                    "VALUES ('c', 'Old chat', 'p', 'prompt', 1, 2, 'v1');" + task,
            )
        }
        val chat = "SELECT id, title, providerId, systemPrompt, createdAt, updatedAt, toolDiscovery FROM conversations;"
        val chatBefore = old.run(chat)
        val taskBefore = old.run("SELECT * FROM scheduled_tasks;")
        old.run(ChatOnlyMigrationTest.MIGRATE_17_18 + "\n" + MIGRATE_18_19)
        assertEquals(chatBefore, old.run(chat))
        assertEquals("0", old.run("SELECT chatOnly FROM conversations WHERE id = 'c';").trim())
        assertEquals("$taskBefore||", old.run("SELECT * FROM scheduled_tasks;"))
        assertEquals("0", old.run("SELECT COUNT(*) FROM trigger_events;").trim())

        val fresh = Sqlite3(tmp.newFile("fresh19.db")).apply { run(ExportedSchema(19).createAll()) }
        val tables = "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name;"
        assertEquals(fresh.rows(tables), old.rows(tables))
        for (table in fresh.rows(tables)) {
            assertEquals(fresh.run(shape(table)), old.run(shape(table)), table)
            assertEquals(fresh.run(indices(table)), old.run(indices(table)), table)
            assertEquals(fresh.run(foreignKeys(table)), old.run(foreignKeys(table)), table)
        }
    }

    companion object {
        val MIGRATE_18_19 =
            "ALTER TABLE `scheduled_tasks` ADD COLUMN `triggerJson` TEXT DEFAULT NULL;\n" +
                "ALTER TABLE `scheduled_tasks` ADD COLUMN `triggerStateJson` TEXT DEFAULT NULL;\n" +
                ExportedSchema(19).createSql("trigger_events") + ";\n" +
                "CREATE INDEX IF NOT EXISTS `index_trigger_events_taskId_receivedAt` ON `trigger_events` (`taskId`, `receivedAt`);"
    }
}
