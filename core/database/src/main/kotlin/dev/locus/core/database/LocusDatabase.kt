package dev.locus.core.database

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MessageFtsEntity::class, AuditEntity::class, ScheduledTaskEntity::class, UsageEventEntity::class],
    version = 10,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7, spec = BranchesMigration::class), AutoMigration(from = 7, to = 8, spec = SearchMigration::class), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10)],
)
abstract class LocusDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun auditDao(): AuditDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun usageEventDao(): UsageEventDao
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
