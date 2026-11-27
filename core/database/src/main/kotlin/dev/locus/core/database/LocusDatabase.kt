package dev.locus.core.database

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.AutoMigrationSpec
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MessageFtsEntity::class, AuditEntity::class, AuditFtsEntity::class, ScheduledTaskEntity::class, UsageEventEntity::class, FileChangeEntity::class, TaskRunEntity::class, ProjectEntity::class, TriggerEventEntity::class, ProposalEntity::class],
    version = 21,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7, spec = BranchesMigration::class), AutoMigration(from = 7, to = 8, spec = SearchMigration::class), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14), AutoMigration(from = 14, to = 15), AutoMigration(from = 15, to = 16), AutoMigration(from = 16, to = 17), AutoMigration(from = 17, to = 18), AutoMigration(from = 18, to = 19), AutoMigration(from = 19, to = 20, spec = NeedsYouMigration::class)],
)
abstract class LocusDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun auditDao(): AuditDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun usageEventDao(): UsageEventDao
    abstract fun fileChangeDao(): FileChangeDao
    abstract fun taskRunDao(): TaskRunDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun projectDao(): ProjectDao
    abstract fun triggerEventDao(): TriggerEventDao
    abstract fun proposalDao(): ProposalDao
}

/**
 * v21 indexes the Activity log for search (#316, docs/design/search.md, "Activity's queries"). `audit_log` gets an
 * INTEGER PRIMARY KEY, `seq`, for the index's rowids to point at, so the table is rebuilt: a new table, every row
 * copied in time order (so `seq` follows `startedAt`), the old one dropped and the new one renamed, then its indexes,
 * with `id` (the old primary key) unique. Then the trigram FTS5 table `audit_fts` over the arguments and result
 * previews, built once from the copied rows. Room creates the index's sync triggers after migrations, as for
 * `message_fts`. A manual migration: Room's AutoMigration refuses a new NOT NULL column without a default, and a
 * default would give every copied row the same `seq`. Registered in [DatabaseModule].
 */
object ActivitySearchMigration : Migration(20, 21) {
    override suspend fun migrate(connection: SQLiteConnection) {
        STATEMENTS.forEach(connection::execSQL)
    }

    /** The audit columns both versions have, in the order they're copied. */
    private const val COLUMNS = "`id`, `conversationId`, `callId`, `toolName`, `argumentsJson`, `decision`, `resultPreview`, `isError`, `startedAt`, `finishedAt`"

    /** What the migration runs, in order; the tests run the same statements. */
    val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `_new_audit_log` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `callId` TEXT NOT NULL, " +
            "`toolName` TEXT NOT NULL, `argumentsJson` TEXT NOT NULL, `decision` TEXT NOT NULL, `resultPreview` TEXT NOT NULL, " +
            "`isError` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)",
        "INSERT INTO `_new_audit_log` ($COLUMNS) SELECT $COLUMNS FROM `audit_log` ORDER BY `startedAt`, `rowid`",
        "DROP TABLE `audit_log`",
        "ALTER TABLE `_new_audit_log` RENAME TO `audit_log`",
        "CREATE INDEX IF NOT EXISTS `index_audit_log_conversationId` ON `audit_log` (`conversationId`)",
        "CREATE INDEX IF NOT EXISTS `index_audit_log_startedAt` ON `audit_log` (`startedAt`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_audit_log_id` ON `audit_log` (`id`)",
        "CREATE INDEX IF NOT EXISTS `index_audit_log_toolName` ON `audit_log` (`toolName`)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS `audit_fts` USING FTS5(`argumentsJson`, `resultPreview`, tokenize=`trigram`, " +
            "content=`audit_log`, content_rowid=`seq`)",
        // Re-reads every row of audit_log into the index (FTS5's 'rebuild' command).
        "INSERT INTO audit_fts(audit_fts) VALUES('rebuild')",
    )
}

/**
 * v20 adds Needs you (docs/design/needs-you.md): the `proposals` table and `scheduled_tasks.lastSeenRunAt`. Every
 * task's last result counts as seen, so the update doesn't greet the user with all of them as new.
 */
class NeedsYouMigration : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL(MARK_SEEN)
    }

    companion object {
        const val MARK_SEEN = "UPDATE scheduled_tasks SET lastSeenRunAt = lastRunAt"
    }
}

/**
 * v7 adds message branches. Existing chats become a single branch: each message's parent is the
 * previous message of the same chat, and the active leaf stays null (the newest message).
 */
class BranchesMigration : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL(BACKFILL_PARENTS)
    }

    companion object {
        const val BACKFILL_PARENTS = "UPDATE messages SET parentId = (SELECT p.id FROM messages AS p " +
            "WHERE p.conversationId = messages.conversationId AND p.seq < messages.seq ORDER BY p.seq DESC LIMIT 1)"
    }
}

/**
 * v8 adds full-text search over messages: a plain-text `searchText` column and the FTS5 index
 * `message_fts` over it. Room adds both, and creates the index's sync triggers once migrations are
 * done. This fills `searchText` for existing messages from their JSON parts, the same way
 * `dev.locus.core.data.search.SearchText` derives it for new ones, then builds the index from it.
 * See docs/design/search.md.
 */
class SearchMigration : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL(BACKFILL_SEARCH_TEXT)
        connection.execSQL(REBUILD_INDEX)
    }

    companion object {
        /**
         * User messages: the names of attached files, then the typed text; assistant replies: their text.
         * Notes Locus adds for the model (`<locus_mode>`, `<locus_result>`) are left out, as are tool
         * calls, tool results, reasoning and checkpoints. The highlight markers (U+E000, U+E001) are
         * removed so that only snippet() can produce them.
         */
        const val BACKFILL_SEARCH_TEXT = "UPDATE messages SET searchText = NULLIF(trim(replace(replace(" +
            "COALESCE((SELECT group_concat(json_extract(p.value, '$.name'), ' ' ORDER BY p.key) FROM json_each(messages.partsJson) AS p " +
            "WHERE messages.role = 'USER' AND json_extract(p.value, '$.kind') = 'attachment'), '') || ' ' || " +
            "COALESCE((SELECT group_concat(json_extract(p.value, '$.text'), '' ORDER BY p.key) FROM json_each(messages.partsJson) AS p " +
            "WHERE json_extract(p.value, '$.kind') = 'text' " +
            "AND ltrim(json_extract(p.value, '$.text'), char(32, 9, 10, 13)) NOT LIKE '<locus_mode>%' " +
            "AND ltrim(json_extract(p.value, '$.text'), char(32, 9, 10, 13)) NOT LIKE '<locus_result>%'), ''), " +
            "char(57344), ''), char(57345), ''), char(32, 9, 10, 13)), '') " +
            "WHERE role IN ('USER', 'ASSISTANT') AND json_valid(partsJson)"

        /** Re-reads every row of `messages` into the index (FTS5's 'rebuild' command). */
        const val REBUILD_INDEX = "INSERT INTO message_fts(message_fts) VALUES('rebuild')"
    }
}
