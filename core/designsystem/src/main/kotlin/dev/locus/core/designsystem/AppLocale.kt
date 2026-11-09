package dev.locus.core.designsystem

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * The per-app language (Settings → Language, docs/design/localization.md), through Android's own
 * [LocaleManager] (API 33+). Android stores the choice, keeps it in sync with App info → Language,
 * backs it up, and recreates the activities in it; `stringResource` follows. Below API 33 there is no
 * per-app language: Locus follows the phone's, and Settings doesn't show the row.
 *
 * Tags are BCP 47 lists ("es", "zh-Hans"); "" follows the phone.
 */
object AppLocale {
    /** Whether this phone has a per-app language (Android 13 and later). */
    @get:ChecksSdkIntAtLeast(api = 33)
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** The chosen language tags, "" when Locus follows the phone. */
    @RequiresApi(33)
    fun get(context: Context): String = context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()

    /** Switches Locus to [tags] ("" follows the phone). Android recreates the open activities in it. */
    @RequiresApi(33)
    fun set(context: Context, tags: String) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tags.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tags)
    }
}
