package dev.locus.core.datastore

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The vault key never throws saved keys away on its own: failures close the vault and change nothing,
 * before the first unlock nothing is even tried, only the user's reset sets the old keys aside (kept,
 * at most three copies), and a copy whose old key works again is restored.
 */
class VaultKeysTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * A phone: SharedPreferences, the Android Keystore and a stand-in for Tink's AndroidKeysetManager.
     * A keyset entry is "<alias that wraps it>:<data key id>"; opening it under another alias fails
     * like a wrong master key does (AEADBadTagException), and a missing alias fails like a lost key.
     */
    private inner class Phone {
        val prefs = mutableMapOf<String, String>()
        val keystore = mutableMapOf<String, Aead>()
        val deletedAliases = mutableListOf<String>()
        val broken = mutableMapOf<String, () -> Exception>()
        private val dataKeys = mutableMapOf<String, Aead>()
        var opens = 0
        var unlocked = true
        var now = 1_000_000L
        val unlockCallbacks = mutableListOf<() -> Unit>()
        val vaultFile = File(tmp.root, "datastore/secrets.enc")
        val log = DiagnosticsLog(File(tmp.root, "diagnostics"))

        init {
            AeadConfig.register()
        }

        private fun newAead(): Aead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)

        fun open(name: String, alias: String): Aead {
            opens++
            broken[alias]?.let { throw it() }
            val saved = prefs[name]
            if (saved == null) {
                keystore.getOrPut(alias) { newAead() }
                val id = "ks${dataKeys.size}"
                dataKeys[id] = newAead()
                prefs[name] = "$alias:$id"
                return dataKeys.getValue(id)
            }
            val (wrappedBy, id) = saved.split(':')
            if (alias !in keystore) throw KeyStoreException("the master key android-keystore://$alias exists but is unusable")
            if (wrappedBy != alias) throw AEADBadTagException("wrong key")
            return dataKeys.getValue(id)
        }

        /** A new process: a fresh VaultKeys over the same storage. */
        fun start() = VaultKeys(
            prefs = object : KeysetPrefs {
                override fun get(name: String) = prefs[name]
                override fun edit(changes: Map<String, String?>): Boolean {
                    changes.forEach { (k, v) -> if (v == null) prefs.remove(k) else prefs[k] = v }
                    return true
                }
            },
            masterKeys = object : MasterKeys {
                override fun exists(alias: String) = alias in keystore
                override fun delete(alias: String) {
                    keystore.remove(alias)
                    deletedAliases += alias
                }
            },
            openKeyset = ::open,
            vaultFile = vaultFile,
            userUnlocked = { unlocked },
            log = log,
            clock = { now },
            pause = {},
            onLocked = { unlockCallbacks += it },
        )

        fun currentAlias(): String = prefs.getValue(VaultKeys.KEYSET_NAME).substringBefore(':')

        fun writeVault(keys: VaultKeys, secrets: Map<String, String>) = runBlocking {
            vaultFile.parentFile.mkdirs()
            vaultFile.outputStream().use { AeadSerializer(keys.aead(), secretsJson(), DataStoreFiles.SECRETS.encodeToByteArray()).writeTo(secrets, it) }
        }

        fun readVault(keys: VaultKeys): Map<String, String> = runBlocking {
            vaultFile.inputStream().use { AeadSerializer(keys.aead(), secretsJson(), DataStoreFiles.SECRETS.encodeToByteArray()).readFrom(it) }
        }

        /** Everything the vault depends on, to check that nothing changed. */
        fun snapshot() = Triple(prefs.filterKeys { !it.startsWith("locus_vault_failed") }, keystore.keys.toSet(), vaultFile.takeIf { it.exists() }?.readBytes()?.toList())

        /** A phone with saved keys under a working key. */
        fun withSavedKeys(secrets: Map<String, String> = mapOf("provider:a" to "sk-1")): Phone = apply {
            val keys = start()
            assertEquals(VaultState.Ready, keys.ensureOpen())
            writeVault(keys, secrets)
        }
    }

    @Test
    fun `a working key opens and its saved keys are read`() {
        val phone = Phone().withSavedKeys()
        val keys = phone.start()
        assertEquals(VaultState.Ready, keys.ensureOpen())
        assertEquals(mapOf("provider:a" to "sk-1"), phone.readVault(keys))
    }

    @Test
    fun `a passing Keystore failure is retried within the start`() {
        val phone = Phone().withSavedKeys()
        val alias = phone.currentAlias()
        var failures = 0
        // Fails once, then works.
        phone.broken[alias] = { failures++; phone.broken.remove(alias); ProviderException("busy") }
        val keys = phone.start()
        assertEquals(VaultState.Ready, keys.ensureOpen())
        assertEquals(1, failures)
        assertTrue(phone.log.entries().isEmpty())
    }

    @Test
    fun `a failure closes the vault and changes or deletes nothing`() = runTest {
        val phone = Phone().withSavedKeys()
        val before = phone.snapshot()
        phone.broken[phone.currentAlias()] = { ProviderException("Keystore operation failed") }

        val keys = phone.start()
        assertIs<VaultState.Unavailable>(keys.ensureOpen())
        assertFailsWith<VaultUnavailableException> { keys.aead() }

        // Through SecretStore: reads and writes fail clearly; the UI sees no names and nothing to scrub.
        val store = DataStoreFactory.create(serializer = VaultFormat(keys::aead), produceFile = { phone.vaultFile })
        val secrets = SecretStore(store, VaultFormat(keys::aead), keys)
        assertFailsWith<VaultUnavailableException> { secrets.get("provider:a") }
        assertFailsWith<VaultUnavailableException> { secrets.put("provider:a", "sk-2") }
        assertEquals(emptySet(), secrets.names.first())
        assertEquals(emptyList(), secrets.valuesForScrubbing().toList())
        assertTrue(secrets.unavailableReason()!!.contains("can't open your saved keys"))

        assertEquals(before, phone.snapshot())
        assertTrue(phone.deletedAliases.isEmpty())
        assertEquals("Saved keys couldn't be opened", phone.log.entries().single().title)
    }

    @Test
    fun `before the first unlock nothing is tried, counted or reset`() {
        val phone = Phone().withSavedKeys()
        val before = phone.snapshot()
        val opensBefore = phone.opens
        phone.unlocked = false
        repeat(5) {
            val keys = phone.start()
            assertEquals(VaultState.Locked, keys.ensureOpen())
            assertEquals(VaultState.Locked, keys.reset())
        }
        assertEquals(opensBefore, phone.opens)
        assertEquals(before, phone.snapshot())
        assertFalse(phone.prefs.keys.any { it.startsWith("locus_vault_failed") })

        // At unlock it opens by itself.
        val keys = phone.start()
        keys.ensureOpen()
        phone.unlocked = true
        phone.unlockCallbacks.last().invoke()
        assertEquals(VaultState.Ready, keys.state.value)
    }

    @Test
    fun `a key that is gone for good offers the reset but changes nothing`() {
        val phone = Phone().withSavedKeys()
        // The keyset no longer decrypts under the master key: a different key now has the alias.
        phone.broken[phone.currentAlias()] = { GeneralSecurityException("decrypt", AEADBadTagException("tag mismatch")) }
        val before = phone.snapshot()
        val state = phone.start().ensureOpen()
        assertIs<VaultState.NeedsReset>(state)
        assertTrue(state.permanent)
        assertEquals(before, phone.snapshot())

        // And a missing Keystore alias.
        val other = Phone().withSavedKeys()
        other.keystore.clear()
        assertIs<VaultState.NeedsReset>(other.start().ensureOpen())
        assertTrue(other.deletedAliases.isEmpty())
    }

    @Test
    fun `unexplained failures offer the reset only after three separate starts`() {
        val phone = Phone().withSavedKeys()
        phone.broken[phone.currentAlias()] = { KeyStoreException("the master key exists but is unusable") }

        val first = phone.start()
        assertIs<VaultState.Unavailable>(first.ensureOpen())
        // More tries in the same start don't count again.
        phone.now += 60_000
        assertIs<VaultState.Unavailable>(first.ensureOpen())
        phone.now += 60_000
        assertIs<VaultState.Unavailable>(first.ensureOpen())
        assertIs<VaultState.Unavailable>(phone.start().ensureOpen())
        val third = phone.start().ensureOpen()
        assertIs<VaultState.NeedsReset>(third)
        assertFalse(third.permanent)
    }

    @Test
    fun `the confirmed reset sets the old keys aside, keeps the old alias and makes new keys`() {
        val phone = Phone().withSavedKeys()
        val oldAlias = phone.currentAlias()
        val oldKeyset = phone.prefs.getValue(VaultKeys.KEYSET_NAME)
        val oldVault = phone.vaultFile.readBytes()
        phone.broken[oldAlias] = { KeyStoreException("unusable") }
        repeat(2) { phone.start().ensureOpen() }
        val fresh = phone.start()
        assertIs<VaultState.NeedsReset>(fresh.ensureOpen())
        // Nothing was set aside before the user agreed.
        assertFalse(phone.prefs.keys.any { ".invalid." in it })
        assertEquals(VaultState.Ready, fresh.reset())

        val at = phone.now
        assertEquals(oldKeyset, phone.prefs["${VaultKeys.KEYSET_NAME}.invalid.$at"])
        assertContentEquals(oldVault, File(phone.vaultFile.parentFile, "secrets.enc.invalid.$at").readBytes())
        assertFalse(phone.vaultFile.exists())
        assertTrue(oldAlias in phone.keystore)
        assertTrue(phone.deletedAliases.isEmpty())
        assertNotEquals(oldAlias, phone.currentAlias())
        assertEquals("${VaultKeys.DEFAULT_ALIAS}.$at", phone.currentAlias())
        assertTrue(phone.log.entries().any { it.title == "Saved keys were reset" })
        // The new vault works.
        phone.writeVault(fresh, mapOf("provider:a" to "sk-new"))
        assertEquals(mapOf("provider:a" to "sk-new"), phone.readVault(phone.start()))
    }

    @Test
    fun `at most three copies are kept`() {
        val phone = Phone().withSavedKeys()
        val aliases = mutableListOf<String>()
        repeat(4) { round ->
            val alias = phone.currentAlias()
            aliases += alias
            phone.broken[alias] = { GeneralSecurityException(AEADBadTagException("tag")) }
            phone.now += 1_000
            val keys = phone.start()
            assertIs<VaultState.NeedsReset>(keys.ensureOpen())
            assertEquals(VaultState.Ready, keys.reset())
            phone.writeVault(keys, mapOf("provider:a" to "sk-$round"))
        }
        assertEquals(3, phone.prefs.keys.count { it.startsWith("${VaultKeys.KEYSET_NAME}.invalid.") })
        assertEquals(3, phone.vaultFile.parentFile.listFiles()!!.count { it.name.startsWith("secrets.enc.invalid.") })
        // Only the oldest copy, dropped inside the user's own reset, lost its alias.
        assertEquals(listOf(aliases.first()), phone.deletedAliases)
    }

    @Test
    fun `a set-aside copy whose old key works again is restored at the next start`() {
        val phone = Phone().withSavedKeys(mapOf("provider:a" to "sk-1", "provider:b" to "old"))
        val oldAlias = phone.currentAlias()
        phone.broken[oldAlias] = { KeyStoreException("Keystore daemon not responding") }
        repeat(2) { phone.start().ensureOpen() }
        val keys = phone.start()
        assertIs<VaultState.NeedsReset>(keys.ensureOpen())
        assertEquals(VaultState.Ready, keys.reset())
        // The user enters one key again under the new key.
        phone.writeVault(keys, mapOf("provider:b" to "new"))

        // The old key comes back (it was the Keystore, not the key).
        phone.broken.clear()
        val next = phone.start()
        assertEquals(VaultState.Ready, next.ensureOpen())
        assertEquals(mapOf("provider:a" to "sk-1", "provider:b" to "new"), phone.readVault(next))
        assertTrue(VaultKeys.RECOVERED_NOTICE in phone.log.notices())
        assertTrue(phone.log.entries().any { it.title == "Saved keys were recovered" })

        // Restored once: a key removed afterwards doesn't come back.
        phone.writeVault(next, mapOf("provider:b" to "new"))
        val later = phone.start()
        later.ensureOpen()
        assertEquals(mapOf("provider:b" to "new"), phone.readVault(later))
    }

    @Test
    fun `programming errors are thrown, not treated as key loss`() {
        val phone = Phone().withSavedKeys()
        phone.broken[phone.currentAlias()] = { IllegalArgumentException("bad uri") }
        assertFailsWith<IllegalArgumentException> { phone.start().ensureOpen() }
    }

    @Test
    fun `failures are classified from the exception and its causes`() {
        assertEquals(KeyFailure.PERMANENT, classifyKeyFailure(GeneralSecurityException("x", AEADBadTagException("tag"))))
        assertEquals(KeyFailure.UNKNOWN, classifyKeyFailure(KeyStoreException("the master key exists but is unusable")))
        assertEquals(KeyFailure.UNKNOWN, classifyKeyFailure(ProviderException("Keystore operation failed")))
    }
}
