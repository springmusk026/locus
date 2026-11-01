package dev.locus.core.datastore

import android.content.Context
import java.io.File

/** Where the app's DataStore files live, for code that has to touch them without opening a DataStore. */
object DataStoreFiles {
    /** The settings DataStore ([DataStoreModule]). */
    const val SETTINGS = "settings.json"

    /**
     * The path androidx.datastore's `Context.dataStoreFile(name)` returns. In datastore 1.3.0
     * (DataStoreFile.android.kt) that is `File(applicationContext.filesDir, "datastore/$fileName")`;
     * this builds the same path from [context] itself, because `applicationContext` is still null in
     * `Application.attachBaseContext`, where a pending restore is applied.
     */
    fun file(context: Context, name: String): File = File(context.filesDir, "datastore/$name")
}
