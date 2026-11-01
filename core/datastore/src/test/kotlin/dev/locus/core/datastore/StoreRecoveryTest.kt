package dev.locus.core.datastore

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import dev.locus.core.model.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unreadable settings or vault files start over (keeping a copy) instead of failing every read. An
 * unusable Keystore key is covered by [VaultKeysTest].
 */
class StoreRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun log() = DiagnosticsLog(File(tmp.root, "diagnostics"))

    @Test
    fun `unreadable settings reset to the defaults, keep a copy and tell the user`() = runTest {
        val file = File(tmp.root, "datastore/settings.json").apply { parentFile.mkdirs(); writeText("{ not json") }
        val log = log()
        val store = DataStoreFactory.create(
            serializer = JsonSerializer(AppSettings.serializer(), AppSettings()),
            corruptionHandler = corruptionHandler({ file }, "Settings", AppSettings(), log, notice = "settings reset"),
            produceFile = { file },
        )

        assertEquals(AppSettings(), store.data.first())
        assertEquals("{ not json", File(file.parentFile, "settings.json.corrupt").readText())
        val entry = log.entries().single()
        assertEquals(DiagnosticsLog.Kind.STORAGE, entry.kind)
        assertEquals("Settings couldn't be read and was reset", entry.title)
        assertEquals(listOf("settings reset"), log.notices())
        // And it can be written again.
        store.updateData { it.copy(autoTitle = false) }
        assertEquals(false, store.data.first().autoTitle)
    }

    @Test
    fun `a vault that doesn't decrypt starts empty, keeps a copy and tells the user`() = runTest {
        AeadConfig.register()
        fun aead() = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        val file = File(tmp.root, "datastore/secrets.enc").apply { parentFile.mkdirs() }
        // Written under one key, read under another (as after the keyset was replaced).
        file.outputStream().use { AeadSerializer(aead(), secretsJson(), DataStoreFiles.SECRETS.encodeToByteArray()).writeTo(mapOf("provider:a" to "sk-1"), it) }
        val log = log()
        val store = DataStoreFactory.create(
            serializer = AeadSerializer(aead(), secretsJson(), DataStoreFiles.SECRETS.encodeToByteArray()),
            corruptionHandler = corruptionHandler({ file }, "Saved keys", emptyMap(), log, notice = "keys reset"),
            produceFile = { file },
        )

        assertEquals(emptyMap(), store.data.first())
        assertTrue(File(file.parentFile, "secrets.enc.corrupt").length() > 0)
        assertEquals("Saved keys couldn't be read and was reset", log.entries().single().title)
        assertEquals(listOf("keys reset"), log.notices())
    }
}
