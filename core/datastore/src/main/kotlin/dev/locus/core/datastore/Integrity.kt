package dev.locus.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import dev.locus.core.model.AppSettings
import dev.locus.core.model.EndpointSeal
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
open class KeystoreHmacKey(private val alias: String) : IntegrityKey {
    @Volatile private var key: SecretKey? = null
    @Volatile private var created = false

    private fun key(): SecretKey = key ?: synchronized(this) {
        key ?: load().also { key = it }
    }

    private fun load(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = runCatching { store.getKey(alias, null) as? SecretKey }
        existing.getOrNull()?.let { return it }
        // A key that exists but can't be read (invalidated by an OS update) is replaced. That isn't
        // a first use: what it sealed no longer checks out and falls back to the safe settings once.
        return generate(replacing = existing.isFailure || store.containsAlias(alias))
    }

    private fun generate(replacing: Boolean): SecretKey {
        if (replacing) runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias) }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).build())
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
    }
}

/** Seals tool permissions and connector approvals. */
@Singleton
class KeystoreIntegrityKey @Inject constructor() : KeystoreHmacKey("locus_policy_hmac")

/**
 * Seals where requests and keys go ([dev.locus.core.model.EndpointSeal]). A key of its own: that it
 * exists means this install has sealed its endpoints, which no file written outside Locus can undo.
 */
@Singleton
class EndpointIntegrityKey @Inject constructor() : KeystoreHmacKey("locus_endpoint_hmac")

/**
 * Seals what instructs the agent: active skills, helper definitions and persona files
 * ([dev.locus.core.agent.home.InstructionSeal]). A key of its own, like [EndpointIntegrityKey], so the
 * update that introduces these seals trusts what it finds once (the run that creates the key) and an
 * unsealed set is a change outside Locus in every run after.
 */
@Singleton
class InstructionIntegrityKey @Inject constructor() : KeystoreHmacKey("locus_instructions_hmac")

/** Something sealed failed its check and was reset; shown to the user once (app: IntegrityNotice). */
sealed interface IntegrityAlert {
    /** Tool permissions and reviewer settings were changed outside Locus and reset to safe ones. */
    data object ToolPermissions : IntegrityAlert

    /**
     * Provider addresses, proxies, certificates or SSH machines were changed outside Locus: providers
     * wait for the user to confirm their address, proxies and certificates were dropped, SSH host keys cleared.
     */
    data object Endpoints : IntegrityAlert

    /** Connectors added or changed outside Locus were moved back to drafts. */
    data class Connectors(val ids: List<String>) : IntegrityAlert

    /** Active skills changed outside Locus: held as drafts for review, the approved version put back. */
    data class Skills(val names: List<String>) : IntegrityAlert

    /** Helper definitions changed outside Locus: held as drafts for review, the approved version put back. */
    data class Helpers(val names: List<String>) : IntegrityAlert

    /** Persona files changed outside Locus: held for review, the approved version put back. */
    data class Persona(val files: List<String>) : IntegrityAlert
}

/** Collects [IntegrityAlert]s until the user has seen them. */
@Singleton
class IntegrityAlerts @Inject constructor() {
    private val _alerts = MutableStateFlow<List<IntegrityAlert>>(emptyList())
    val alerts: StateFlow<List<IntegrityAlert>> = _alerts.asStateFlow()

    fun report(alert: IntegrityAlert) = _alerts.update { current ->
        when (alert) {
            is IntegrityAlert.Connectors -> merge(current, alert.ids, { it.ids }, IntegrityAlert::Connectors)
            is IntegrityAlert.Skills -> merge(current, alert.names, { it.names }, IntegrityAlert::Skills)
            is IntegrityAlert.Helpers -> merge(current, alert.names, { it.names }, IntegrityAlert::Helpers)
            is IntegrityAlert.Persona -> merge(current, alert.files, { it.files }, IntegrityAlert::Persona)
            else -> if (alert in current) current else current + alert
        }
    }

    fun dismiss() {
        _alerts.value = emptyList()
    }

    /** One alert of type [T], last, naming everything reported so far. */
    private inline fun <reified T : IntegrityAlert> merge(
        current: List<IntegrityAlert>,
        added: List<String>,
        names: (T) -> List<String>,
        make: (List<String>) -> T,
    ): List<IntegrityAlert> =
        current.filterNot { it is T } + make((current.filterIsInstance<T>().flatMap(names) + added).distinct().sorted())
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
    /** Seals provider addresses, proxies, certificates and SSH machines ([EndpointSeal]); null leaves them unsealed. */
    private val endpointKey: IntegrityKey? = null,
) : DataStore<AppSettings> {
    private val repairing = AtomicBoolean(false)

    /** The last settings that checked out, so unchanged settings aren't checked against the Keystore again. */
    @Volatile private var lastTrusted: AppSettings? = null

    /** Once this process has written an endpoint seal, an empty one is never a first use again. */
    @Volatile private var endpointsSealed = false

    override val data: Flow<AppSettings> = inner.data.map { stored ->
        val trusted = trusted(stored)
        if (trusted !== stored || (stored.policySeal.isEmpty() && !PolicySeal.isDefault(stored)) || endpointsUnsealed(stored)) repair()
        trusted
    }

    override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings =
        inner.updateData { stored -> seal(transform(trusted(stored))) }

    private fun endpointsUnsealed(s: AppSettings) = endpointKey != null && s.endpointSeal.isEmpty() && !EndpointSeal.isDefault(s)

    /** [stored] if its permissions and endpoints check out, else the restricted version (and an alert). */
    private fun trusted(stored: AppSettings): AppSettings {
        if (stored == lastTrusted) return stored
        val policies = when (PolicySeal.check(stored, key)) {
            SealCheck.DEFAULTS, SealCheck.VALID, SealCheck.FIRST_USE -> stored
            // Sealed before the Telegram chat id was: drop what the old seal didn't cover; [data] reseals it.
            SealCheck.LEGACY -> PolicySeal.migrated(stored, System.currentTimeMillis())
            SealCheck.TAMPERED -> {
                alerts.report(IntegrityAlert.ToolPermissions)
                return endpoints(PolicySeal.restricted(stored), stored)
            }
        }
        return endpoints(policies, stored).also { if (it === policies) lastTrusted = it }
    }

    /** [s] (checked against [stored]'s endpoint seal), or with its endpoints restricted when they fail. */
    private fun endpoints(s: AppSettings, stored: AppSettings): AppSettings {
        val k = endpointKey ?: return s
        return when (EndpointSeal.check(stored, k, firstUseAllowed = !endpointsSealed)) {
            SealCheck.DEFAULTS, SealCheck.VALID, SealCheck.FIRST_USE, SealCheck.LEGACY -> s
            SealCheck.TAMPERED -> {
                alerts.report(IntegrityAlert.Endpoints)
                EndpointSeal.restricted(s)
            }
        }
    }

    private fun seal(s: AppSettings): AppSettings = sealEndpoints(sealPolicies(s)).also { lastTrusted = it }

    private fun sealPolicies(s: AppSettings): AppSettings {
        if (PolicySeal.isDefault(s)) return s.copy(policySeal = "")
        return try {
            PolicySeal.sealed(s, key)
        } catch (_: Exception) {
            // The Keystore can't be used: other settings still save, permissions stay restricted.
            alerts.report(IntegrityAlert.ToolPermissions)
            PolicySeal.restricted(s)
        }
    }

    private fun sealEndpoints(s: AppSettings): AppSettings {
        val k = endpointKey ?: return s
        return try {
            EndpointSeal.sealed(s, k).also { if (it.endpointSeal.isNotEmpty()) endpointsSealed = true }
        } catch (_: Exception) {
            alerts.report(IntegrityAlert.Endpoints)
            EndpointSeal.restricted(s)
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
suspend fun resealSettingsFile(file: java.io.File, key: IntegrityKey, endpointKey: IntegrityKey? = null) {
    if (!file.isFile) return
    val format = JsonSerializer(AppSettings.serializer(), AppSettings())
    val restored = file.inputStream().use { format.readFrom(it) }
    val policies = if (PolicySeal.isDefault(restored)) restored.copy(policySeal = "") else PolicySeal.sealed(restored, key)
    val sealed = endpointKey?.let { EndpointSeal.sealed(policies, it) } ?: policies
    val tmp = java.io.File(file.path + ".tmp")
    java.io.FileOutputStream(tmp).use { out ->
        format.writeTo(sealed, out)
        out.fd.sync()
    }
    check(tmp.renameTo(file)) { "Could not seal the restored settings" }
}
