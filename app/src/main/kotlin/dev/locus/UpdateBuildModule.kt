package dev.locus

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.locus.core.data.updates.UpdateBuild

/** What core:data's update check needs to know about this build (docs/design/updates.md). */
@Module
@InstallIn(SingletonComponent::class)
object UpdateBuildModule {
    @Provides
    fun updateBuild(): UpdateBuild = UpdateBuild(
        enabled = BuildConfig.UPDATE_CHECK_ENABLED,
        // The debug app is dev.locus.debug: a release APK would install beside it, not over it.
        debug = BuildConfig.DEBUG,
        versionName = BuildConfig.VERSION_NAME,
        // BuildConfig carries the base code; installed() reads the per-ABI one from Android when it matters.
        versionCode = BuildConfig.VERSION_CODE.toLong(),
    )
}
