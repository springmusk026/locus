package dev.locus.core.data.updates

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.di.IoDispatcher
import dev.locus.core.data.repository.SettingsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/** The update check's parts; [UpdateBuild] comes from the app module (its BuildConfig). */
@Module
@InstallIn(SingletonComponent::class)
internal object UpdatesModule {
    @Provides
    @Singleton
    fun updates(
        @ApplicationContext context: Context,
        settings: SettingsRepository,
        http: HttpClient,
        build: UpdateBuild,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): Updates = Updates(
        settings = settings,
        source = ReleaseSource(http),
        cache = DataStoreUpdateCache(
            DataStoreFactory.create(
                serializer = UpdateCacheSerializer,
                // It's only a cache: an unreadable one starts over.
                corruptionHandler = ReplaceFileCorruptionHandler { UpdateCache() },
                produceFile = { context.dataStoreFile("updates.json") },
            ),
        ),
        work = WorkManagerUpdateWork(context),
        platform = AndroidUpdatePlatform(context),
        build = build,
        scope = scope,
        io = io,
        packageName = context.packageName,
    )
}
