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
        // No version code: BuildConfig.VERSION_CODE is one per-ABI code shared by every APK's dex, and
        // which one depends on the ABIs built together, so reading it made the arm64-v8a APK differ
        // between a two-ABI release build and F-Droid's one-ABI build (docs/FDROID.md). The update
        // check reads the installed code from Android instead.
    )
}
