package dev.locus.core.database

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, AuditEntity::class, ScheduledTaskEntity::class],
    version = 7,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7, spec = BranchesMigration::class)],
)
abstract class LocusDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun auditDao(): AuditDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
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
