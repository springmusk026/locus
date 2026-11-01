package dev.locus.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import dev.locus.core.model.AppSettings
import dev.locus.core.model.IntegrityKey
import dev.locus.core.model.PolicySeal
import dev.locus.core.model.SealCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The HMAC-SHA256 key that seals permission state, kept in the Android Keystore: its material never
 * enters Locus's memory, and only Locus's uid can use it. Linux programs share that uid but can't
 * reach the Keystore, because the binder devices are hidden from them (docs/design/linux-isolation.md).
 */
@Singleton
class KeystoreIntegrityKey @Inject constructor() : IntegrityKey {
    @Volatile private var key: SecretKey? = null
    @Volatile private var created = false

    private fun key(): SecretKey = key ?: synchronized(this) {
        key ?: load().also { key = it }
    }

    private fun load(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = runCatching { store.getKey(ALIAS, null) as? SecretKey }
        existing.getOrNull()?.let { return it }
        // A key that exists but can't be read (invalidated by an OS update) is replaced. That isn't
        // a first use: what it sealed no longer checks out and falls back to the safe settings once.
        return generate(replacing = existing.isFailure || store.containsAlias(ALIAS))
    }

    private fun generate(replacing: Boolean): SecretKey {
        if (replacing) runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).build())
        return generator.generateKey().also { if (!replacing) created = true }
    }

    override fun mac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        try {
            mac.init(key())
        } catch (_: java.security.InvalidKeyException) {
            // KeyPermanentlyInvalidatedException among them: replace the key and try once more.
            mac.init(synchronized(this) { generate(replacing = true).also { key = it } })
        }
        return mac.doFinal(data)
    }

    override fun createdThisRun(): Boolean {
        key()
        return created
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "locus_policy_hmac"
    }
}

/** Something sealed failed its check and was reset; shown to the user once (app: IntegrityNotice). */
sealed interface IntegrityAlert {
    /** Tool permissions and reviewer settings were changed outside Locus and reset to safe ones. */
    data object ToolPermissions : IntegrityAlert

    /** Connectors added or changed outside Locus were moved back to drafts. */
    data class Connectors(val ids: List<String>) : IntegrityAlert
}

/** Collects [IntegrityAlert]s until the user has seen them. */
@Singleton
class IntegrityAlerts @Inject constructor() {
    private val _alerts = MutableStateFlow<List<IntegrityAlert>>(emptyList())
    val alerts: StateFlow<List<IntegrityAlert>> = _alerts.asStateFlow()

    fun report(alert: IntegrityAlert) = _alerts.update { current ->
        when (alert) {
            is IntegrityAlert.Connectors -> {
                val before = current.filterIsInstance<IntegrityAlert.Connectors>().flatMap { it.ids }
                current.filterNot { it is IntegrityAlert.Connectors } + IntegrityAlert.Connectors((before + alert.ids).distinct().sorted())
            }
            else -> if (alert in current) current else current + alert
        }
    }

    fun dismiss() {
        _alerts.value = emptyList()
    }
}

/**
 * The settings DataStore with the permission settings sealed ([PolicySeal]).
 *
 * Reads: settings whose seal fails come out [PolicySeal.restricted], the user is told through
 * [alerts], and the restricted settings are written back (sealed) in [scope]. Settings from a
 * version without seals are trusted once when the key is new, and sealed.
 *
 * Writes: the stored settings are checked first, so an update never seals permissions it didn't
 * check; the result is always sealed. Callers need nothing special: every update reseals.
 */
class SealedSettingsStore(
    private val inner: DataStore<AppSettings>,
    private val key: IntegrityKey,
    private val alerts: IntegrityAlerts,
    private val scope: CoroutineScope,
) : DataStore<AppSettings> {
    private val repairing = AtomicBoolean(false)

    /** The last settings that checked out, so unchanged settings aren't checked against the Keystore again. */
    @Volatile private var lastTrusted: AppSettings? = null

    override val data: Flow<AppSettings> = inner.data.map { stored ->
        val trusted = trusted(stored)
        if (trusted !== stored || (stored.policySeal.isEmpty() && !PolicySeal.isDefault(stored))) repair()
        trusted
    }

    override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings =
        inner.updateData { stored -> seal(transform(trusted(stored))) }

    /** [stored] if its permissions check out, else the restricted version (and an alert). */
    private fun trusted(stored: AppSettings): AppSettings {
        if (stored == lastTrusted) return stored
        return when (PolicySeal.check(stored, key)) {
            SealCheck.DEFAULTS, SealCheck.VALID, SealCheck.FIRST_USE -> stored.also { lastTrusted = it }
            SealCheck.TAMPERED -> {
                alerts.report(IntegrityAlert.ToolPermissions)
                PolicySeal.restricted(stored)
            }
        }
    }

    private fun seal(s: AppSettings): AppSettings {
        if (PolicySeal.isDefault(s)) return s.copy(policySeal = "").also { lastTrusted = it }
        return try {
            PolicySeal.sealed(s, key).also { lastTrusted = it }
        } catch (_: Exception) {
            // The Keystore can't be used: other settings still save, permissions stay restricted.
            alerts.report(IntegrityAlert.ToolPermissions)
            PolicySeal.restricted(s)
        }
    }

    /** Writes the checked (restricted or first-use) settings back, sealed; once at a time. */
    private fun repair() {
        if (!repairing.compareAndSet(false, true)) return
        scope.launch {
            try {
                runCatching { updateData { it } }
            } finally {
                repairing.set(false)
            }
        }
    }
}

/**
 * Seals a settings file that isn't open in a DataStore: a restored backup's, staged for the next
 * start (docs/design/backup.md). The user chose to restore it, so its permissions are taken as
 * theirs and sealed with this phone's key; the backup's own seal, made with another key, would fail.
 */
suspend fun resealSettingsFile(file: java.io.File, key: IntegrityKey) {
    if (!file.isFile) return
    val format = JsonSerializer(AppSettings.serializer(), AppSettings())
    val restored = file.inputStream().use { format.readFrom(it) }
    val sealed = if (PolicySeal.isDefault(restored)) restored.copy(policySeal = "") else PolicySeal.sealed(restored, key)
    val tmp = java.io.File(file.path + ".tmp")
    java.io.FileOutputStream(tmp).use { out ->
        format.writeTo(sealed, out)
        out.fd.sync()
    }
    check(tmp.renameTo(file)) { "Could not seal the restored settings" }
}
