package dev.locus.core.data.updates

import java.io.File

/**
 * What this build is, from the app module's BuildConfig: whether the update check is compiled in
 * (`-Plocus.updateCheck=false` turns it off, docs/design/updates.md) and whether it's the debug app.
 */
data class UpdateBuild(val enabled: Boolean, val debug: Boolean, val versionName: String, val versionCode: Long)

/** The Android side of updating, behind an interface so [Updates] runs in JVM tests. */
interface UpdatePlatform {
    /** Build.SUPPORTED_ABIS, most preferred first. */
    val supportedAbis: List<String>

    /** Where downloads go (app-private, not backed up). */
    val downloadDir: File

    /** The installed app's package, version code and signers. */
    fun installed(): ApkCheck.Facts

    /** The same facts read from an APK file; null when Android can't read it. */
    fun archive(file: File): ApkCheck.Facts?

    /**
     * The app store that installed Locus, by name ("F-Droid"), when it's one that updates apps itself;
     * null for a sideload (browser, file manager, adb).
     */
    fun storeInstaller(): String?

    /** Whether the user lets Locus install apps ("Install unknown apps"). */
    fun canInstall(): Boolean

    /** Hands [file] to Android's installer; the outcome arrives at [UpdateInstallReceiver]. */
    fun install(file: File, packageName: String)

    /** Posts the low-importance "Update available" notification. */
    fun notifyAvailable(version: String)

    /** Removes it (the user skipped that version, or it's installed). */
    fun clearNotification()
}

/** Installers that keep Locus updated themselves, by package (docs/design/updates.md, "Stores"). */
object StoreInstallers {
    val names = mapOf(
        "org.fdroid.fdroid" to "F-Droid",
        "org.fdroid.basic" to "F-Droid",
        "org.fdroid.fdroid.privileged" to "F-Droid",
        "com.looker.droidify" to "Droid-ify",
        "com.machiav3lli.fdroid" to "Neo Store",
        "dev.imranr.obtainium" to "Obtainium",
        "dev.imranr.obtainium.fdroid" to "Obtainium",
        "com.android.vending" to "Google Play",
    )

    fun nameOf(installer: String?): String? = installer?.let(names::get)
}

object UpdateLinks {
    /** The "Update available" notification opens Settings → About & privacy with this action (MainActivity, LaunchActions). */
    const val ACTION_OPEN = "dev.locus.action.UPDATES"
}
