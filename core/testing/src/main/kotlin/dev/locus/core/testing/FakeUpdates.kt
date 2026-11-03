package dev.locus.core.testing

import dev.locus.core.data.updates.ApkCheck
import dev.locus.core.data.updates.UpdateBuild
import dev.locus.core.data.updates.UpdateCache
import dev.locus.core.data.updates.UpdateCacheStore
import dev.locus.core.data.updates.UpdatePlatform
import dev.locus.core.data.updates.UpdateWork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** The update check's Android side in memory: what was installed, notified and handed to the installer. */
class FakeUpdatePlatform(override val downloadDir: File) : UpdatePlatform {
    override var supportedAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a")
    var installedFacts = ApkCheck.Facts("dev.locus", 53, setOf(RELEASE_KEY))

    /** What a downloaded APK turns out to be; by default a newer Locus with the same key. */
    var archiveFacts: (File) -> ApkCheck.Facts? = { ApkCheck.Facts("dev.locus", 63, setOf(RELEASE_KEY)) }
    var store: String? = null
    var installAllowed = true
    val installs = mutableListOf<File>()
    val notified = mutableListOf<String>()
    var cleared = 0

    override fun installed() = installedFacts
    override fun archive(file: File) = archiveFacts(file)
    override fun storeInstaller() = store
    override fun canInstall() = installAllowed
    override fun install(file: File, packageName: String) {
        installs += file
    }
    override fun notifyAvailable(version: String) {
        notified += version
    }
    override fun clearNotification() {
        cleared++
    }

    companion object {
        const val RELEASE_KEY = "0fc4ab07aa5522dfabac92d818fd5cf075ead96da4e2b40a757ac80541490e1b"
        val RELEASE_BUILD = UpdateBuild(enabled = true, debug = false, versionName = "0.1.4")
    }
}

/** The daily check's schedule, in memory. */
class FakeUpdateWork : UpdateWork {
    var scheduled = false
        private set
    var calls = 0
        private set

    override fun schedule() {
        scheduled = true
        calls++
    }

    override fun cancel() {
        scheduled = false
        calls++
    }
}

class InMemoryUpdateCache(initial: UpdateCache = UpdateCache()) : UpdateCacheStore {
    private val state = MutableStateFlow(initial)
    override val data: Flow<UpdateCache> = state
    override suspend fun update(transform: (UpdateCache) -> UpdateCache): UpdateCache {
        state.update(transform)
        return state.value
    }
    val value: UpdateCache get() = state.value
}
