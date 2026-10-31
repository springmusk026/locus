package dev.locus.core.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LocusDatabase =
        Room.databaseBuilder(context, LocusDatabase::class.java, "locus.db")
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    @Provides
    fun conversationDao(db: LocusDatabase) = db.conversationDao()

    @Provides
    fun messageDao(db: LocusDatabase) = db.messageDao()

    @Provides
    fun auditDao(db: LocusDatabase) = db.auditDao()

    @Provides
    fun scheduledTaskDao(db: LocusDatabase) = db.scheduledTaskDao()

    @Provides
    fun usageEventDao(db: LocusDatabase) = db.usageEventDao()
}
