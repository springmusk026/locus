package dev.locus.core.testing

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * A Context for JVM unit tests that only answers where files go, all under [root]. Anything else
 * reaches the empty base and fails, which keeps tests honest about what they touch.
 *
 * The native library directory holds an empty `libproot.so`, so LinuxEnvironment reports "not
 * installed" instead of looking up the device's ABIs.
 */
class TestContext(val root: File) : ContextWrapper(null) {
    private val files = File(root, "files").apply { mkdirs() }
    private val cache = File(root, "cache").apply { mkdirs() }
    private val native = File(root, "native").apply { mkdirs(); File(this, "libproot.so").createNewFile() }
    private val info = ApplicationInfo().apply { nativeLibraryDir = native.path }

    /** Services started, e.g. the runner's foreground AgentService; recorded, never run. */
    val startedServices = mutableListOf<Intent>()

    override fun getFilesDir(): File = files
    override fun getCacheDir(): File = cache
    override fun getApplicationInfo(): ApplicationInfo = info
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "dev.locus.test"

    override fun startService(service: Intent): ComponentName? = null.also { startedServices += service }
    override fun startForegroundService(service: Intent): ComponentName? = null.also { startedServices += service }
}
