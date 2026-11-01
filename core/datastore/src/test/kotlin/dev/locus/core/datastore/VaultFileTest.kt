package dev.locus.core.datastore

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A restored vault is staged as a whole file and swapped in at the next start (docs/design/backup.md).
 * That only works if the staged file is exactly what the vault's own DataStore reads: same keyset,
 * same associated data.
 */
class VaultFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun aead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun format(aead: Aead, name: String = DataStoreFiles.SECRETS) = AeadSerializer(aead, secretsJson(), name.encodeToByteArray())

    @Test
    fun `a staged vault file is read by the vault's own store once it's in place`() = runTest {
        val aead = aead()
        val current = SecretStore(InMemory(mapOf("provider:old" to "sk-old")), format(aead))
        val staged = File(tmp.root, "restore/staged/vault.enc")

        current.writeVaultFile(mapOf("provider:a" to "sk-123", "empty" to "", "ssh:h:password" to "pw"), staged)

        // The swap: the staged file becomes the vault file, and the app opens it at the next start.
        val target = File(tmp.root, "files/datastore/${DataStoreFiles.SECRETS}")
        target.parentFile.mkdirs()
        staged.renameTo(target)
        val store = DataStoreFactory.create(serializer = format(aead), produceFile = { target })
        assertEquals(mapOf("provider:a" to "sk-123", "ssh:h:password" to "pw"), store.data.first())
    }

    @Test
    fun `a vault file can't be read under another name or another keyset`() = runTest {
        val aead = aead()
        val file = File(tmp.root, "vault.enc")
        SecretStore(InMemory(emptyMap()), format(aead)).writeVaultFile(mapOf("provider:a" to "sk-123"), file)

        assertTrue(runCatching { file.inputStream().use { format(aead, "settings.json").readFrom(it) } }.isFailure)
        assertTrue(runCatching { file.inputStream().use { format(aead()).readFrom(it) } }.isFailure)
    }

    private class InMemory(initial: Map<String, String>) : androidx.datastore.core.DataStore<Map<String, String>> {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
        override val data = state
        override suspend fun updateData(transform: suspend (Map<String, String>) -> Map<String, String>): Map<String, String> =
            transform(state.value).also { state.value = it }
    }
}
