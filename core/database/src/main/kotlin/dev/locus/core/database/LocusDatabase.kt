package dev.locus.core.database

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, AuditEntity::class, ScheduledTaskEntity::class],
    version = 6,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
)
abstract class LocusDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun auditDao(): AuditDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
}
