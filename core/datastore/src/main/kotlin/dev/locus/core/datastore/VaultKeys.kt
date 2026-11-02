package dev.locus.core.datastore

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.datastore.core.Serializer
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.BadPaddingException

/** Whether the vault's key is open (docs/design/diagnostics.md, "An unusable Keystore key"). */
sealed interface VaultState {
    /** Nothing tried yet in this process. */
    data object NotOpened : VaultState

    data object Ready : VaultState

    /** The user hasn't unlocked the phone since it started; the key is tried again on unlock. */
    data object Locked : VaultState

    /** The key failed, possibly for a passing reason; tried again later. Nothing was changed. */
    data class Unavailable(val reason: String) : VaultState

    /**
     * The key looks permanently unusable, or failed on [RESET_AFTER_FAILED_STARTS] separate starts.
     * Only the user can reset ([VaultAccess.reset]); until then the vault stays closed and unchanged.
     */
    data class NeedsReset(val reason: String, val permanent: Boolean) : VaultState
}

/** What features that need a saved key show while the vault is closed. */
fun VaultState.userMessage(): String? = when (this) {
    VaultState.Ready -> null
    VaultState.Locked -> "Saved keys open after you unlock the phone for the first time since it restarted."
    is VaultState.NeedsReset -> "Locus can't open your saved keys. Open Locus to reset them, or see Settings → About & privacy → Diagnostics."
    VaultState.NotOpened, is VaultState.Unavailable -> "Locus can't open your saved keys right now. Try again in a moment; details are in Settings → About & privacy → Diagnostics."
}

/**
 * Thrown by vault reads and writes while the key is closed. An IOException, not DataStore's
 * CorruptionException, so the vault's corruption handler never replaces the file over it.
 */
class VaultUnavailableException(message: String) : IOException(message)

/** The vault key's state and the user's reset, as [SecretStore] and the app see them. */
interface VaultAccess {
    val state: StateFlow<VaultState>

    /** Opens the key if it isn't open (blocking: Keystore work and a short retry pause). */
    fun ensureOpen(): VaultState

    /** The user's confirmed reset: sets the old keyset and vault aside and makes new keys. */
    fun reset(): VaultState

    companion object {
        /** Always open: for tests and plain-JSON vaults. */
        val AlwaysOpen: VaultAccess = object : VaultAccess {
            override val state: StateFlow<VaultState> = MutableStateFlow(VaultState.Ready)
            override fun ensureOpen() = VaultState.Ready
            override fun reset() = VaultState.Ready
        }
    }
}

/** The SharedPreferences file that holds Tink's keyset (and this class's bookkeeping). */
internal interface KeysetPrefs {
    fun get(name: String): String?

    /** Applies every change in one commit; a null value removes the entry. False if it wasn't written. */
    fun edit(changes: Map<String, String?>): Boolean
}

/** The Android Keystore, for the few things done outside Tink. */
internal interface MasterKeys {
    /** Whether [alias] exists, or null when the Keystore couldn't answer. */
    fun exists(alias: String): Boolean?

    fun delete(alias: String)
}

/**
 * Opens the vault's Tink keyset without ever throwing anything away on its own.
 *
 * - Before the user's first unlock, nothing is tried ([VaultState.Locked]); [onLocked] arranges a
 *   retry at unlock.
 * - A key failure is retried once after a short pause. If it still fails, the vault is closed
 *   ([VaultState.Unavailable]) and nothing is changed. Only a failure that means the key is gone for
 *   good ([classifyKeyFailure]), or failures on [RESET_AFTER_FAILED_STARTS] separate starts, offer
 *   the reset ([VaultState.NeedsReset]); the app asks the user.
 * - [reset] (only on the user's OK) moves the keyset entry and the vault file aside under a
 *   timestamped `.invalid.<ms>` name, keeps the old Keystore alias, and makes the new key under a new
 *   alias. The newest [KEEP_COPIES] copies are kept.
 * - Each start, once the current key is open, set-aside copies are tried with their old alias; a copy
 *   that opens is merged back into the vault (keys entered since win) and marked recovered.
 */
class VaultKeys internal constructor(
    private val prefs: KeysetPrefs,
    private val masterKeys: MasterKeys,
    /** Builds Tink's AndroidKeysetManager for the keyset stored under `keysetName`, wrapped by `alias`. */
    private val openKeyset: (keysetName: String, alias: String) -> Aead,
    private val vaultFile: File,
    private val userUnlocked: () -> Boolean,
    private val log: DiagnosticsLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: () -> Unit = { Thread.sleep(200) },
    /** Arranges for the callback to run once the user unlocks the phone. */
    private val onLocked: (retry: () -> Unit) -> Unit = {},
    private val retryEveryMs: Long = 30_000,
) : VaultAccess {
    private val _state = MutableStateFlow<VaultState>(VaultState.NotOpened)
    override val state: StateFlow<VaultState> = _state.asStateFlow()

    @Volatile private var aead: Aead? = null
    private var lastAttempt: Long? = null
    private var countedThisStart = false
    private var waitingForUnlock = false

    /** The open AEAD, or a [VaultUnavailableException] saying why there is none. */
    fun aead(): Aead {
        aead?.let { return it }
        val state = ensureOpen()
        return aead ?: throw VaultUnavailableException(state.userMessage() ?: "Saved keys aren't open")
    }

    @Synchronized
    override fun ensureOpen(): VaultState {
        val now = _state.value
        if (now == VaultState.Ready) return now
        val last = lastAttempt
        if (last != null && now !is VaultState.Locked && clock() - last < retryEveryMs) return now
        return attempt()
    }

    private fun attempt(): VaultState {
        lastAttempt = clock()
        if (!runCatching(userUnlocked).getOrDefault(true)) {
            // Before the first unlock the Keystore can't be trusted to answer; never count or reset.
            if (!waitingForUnlock) {
                waitingForUnlock = true
                onLocked { synchronized(this) { waitingForUnlock = false; lastAttempt = null }; runCatching { ensureOpen() } }
            }
            return set(VaultState.Locked)
        }
        if (!finishPendingMove()) return set(VaultState.Unavailable("The old vault file couldn't be moved aside"))
        val alias = currentAlias()
        val opened = try {
            openWithRetry(KEYSET_NAME, alias)
        } catch (e: Exception) {
            if (!isKeyFailure(e)) throw e
            return failed(e, alias)
        }
        aead = opened
        if (prefs.get(FAILED_STARTS) != null) prefs.edit(mapOf(FAILED_STARTS to null))
        recoverCopies(opened)
        return set(VaultState.Ready)
    }

    private fun openWithRetry(keysetName: String, alias: String): Aead = try {
        openKeyset(keysetName, alias)
    } catch (e: Exception) {
        if (!isKeyFailure(e)) throw e
        // The Keystore can fail transiently (tink-java's own AndroidKeystoreAesGcm retries once too).
        pause()
        openKeyset(keysetName, alias)
    }

    private fun failed(e: Exception, alias: String): VaultState {
        val keyGone = if (prefs.get(KEYSET_NAME) != null) masterKeys.exists(alias)?.not() ?: false else false
        val kind = if (keyGone) KeyFailure.PERMANENT else classifyKeyFailure(e)
        var failedStarts = prefs.get(FAILED_STARTS)?.toIntOrNull() ?: 0
        if (!countedThisStart) {
            countedThisStart = true
            failedStarts++
            prefs.edit(mapOf(FAILED_STARTS to failedStarts.toString()))
            log.record(
                DiagnosticsLog.Kind.STORAGE,
                "Saved keys couldn't be opened",
                "Android Keystore (${if (keyGone) "the key is missing" else kind.name.lowercase()}): ${describe(e)}\n" +
                    "Failed on $failedStarts start(s) in a row. Nothing was changed or deleted.",
            )
        }
        val reason = describe(e)
        return set(
            if (kind == KeyFailure.PERMANENT || failedStarts >= RESET_AFTER_FAILED_STARTS) {
                VaultState.NeedsReset(reason, permanent = kind == KeyFailure.PERMANENT)
            } else {
                VaultState.Unavailable(reason)
            },
        )
    }

    @Synchronized
    override fun reset(): VaultState {
        // Only a closed vault is reset, and never before the user's first unlock.
        if (_state.value == VaultState.Ready) return VaultState.Ready
        if (!runCatching(userUnlocked).getOrDefault(true)) return set(VaultState.Locked)
        val at = clock()
        val oldAlias = currentAlias()
        val keyset = prefs.get(KEYSET_NAME)
        val copies = copies() + Copy(at, oldAlias, recovered = false)
        // One commit: the keyset moves aside, the new alias and the copy are recorded, and the vault
        // file's move is marked pending, so a crash midway is finished at the next start.
        val written = prefs.edit(
            mapOf(
                keysetCopy(at) to keyset,
                KEYSET_NAME to null,
                ALIAS to "$DEFAULT_ALIAS.$at",
                COPIES to encode(copies),
                FAILED_STARTS to null,
                PENDING_MOVE to at.toString(),
            ),
        )
        if (!written) return set(VaultState.Unavailable("The reset couldn't be saved"))
        finishPendingMove()
        prune()
        log.record(
            DiagnosticsLog.Kind.STORAGE,
            "Saved keys were reset",
            "You chose Reset. The old keyset and vault file were kept as copies (.invalid.$at), and the old Keystore key " +
                "\"$oldAlias\" was kept. Locus tries them again at each start.",
        )
        aead = null
        lastAttempt = null
        countedThisStart = false
        _state.value = VaultState.NotOpened
        return attempt()
    }

    /** Moves the vault file aside after a reset; false if it is still there under the old key. */
    private fun finishPendingMove(): Boolean {
        val at = prefs.get(PENDING_MOVE)?.toLongOrNull() ?: return true
        if (vaultFile.exists() && !vaultFile.renameTo(vaultCopy(at))) return false
        prefs.edit(mapOf(PENDING_MOVE to null))
        return true
    }

    /** Keeps the newest [KEEP_COPIES] copies. Runs only inside the user's reset. */
    private fun prune() {
        val all = copies().sortedByDescending { it.at }
        val (keep, drop) = all.take(KEEP_COPIES) to all.drop(KEEP_COPIES)
        if (drop.isEmpty()) return
        prefs.edit(drop.associate { keysetCopy(it.at) to null } + (COPIES to encode(keep)))
        val inUse = keep.map { it.alias }.toSet() + currentAlias()
        for (copy in drop) {
            vaultCopy(copy.at).delete()
            if (copy.alias !in inUse) runCatching { masterKeys.delete(copy.alias) }
        }
    }

    private fun recoverCopies(current: Aead) {
        for (copy in copies().filter { !it.recovered }.sortedByDescending { it.at }) {
            // Without its keyset entry Tink would make a new keyset (and key) instead of reading one.
            if (prefs.get(keysetCopy(copy.at)) == null) continue
            val old = runCatching { openKeyset(keysetCopy(copy.at), copy.alias) }.getOrNull() ?: continue
            val file = vaultCopy(copy.at)
            val saved = if (file.exists()) runCatching { read(old, file) }.getOrNull() ?: continue else emptyMap()
            val now = if (vaultFile.exists()) runCatching { read(current, vaultFile) }.getOrElse { return } else emptyMap()
            // Keys entered since the reset win over the recovered ones.
            val merged = saved + now
            if (runCatching { write(current, merged) }.isFailure) return
            prefs.edit(mapOf(COPIES to encode(copies().map { if (it.at == copy.at) it.copy(recovered = true) else it })))
            log.record(
                DiagnosticsLog.Kind.STORAGE,
                "Saved keys were recovered",
                "The copy set aside at the reset (.invalid.${copy.at}) opened with its old Keystore key \"${copy.alias}\". " +
                    "${saved.size} saved key(s) were restored; keys entered since were kept.",
            )
            log.addNotice(RECOVERED_NOTICE)
        }
    }

    private fun read(aead: Aead, file: File): Map<String, String> =
        runBlocking { file.inputStream().use { AeadSerializer(aead, secretsJson(), AD).readFrom(it) } }

    private fun write(aead: Aead, secrets: Map<String, String>) {
        val tmp = File(vaultFile.parentFile, vaultFile.name + ".tmp")
        vaultFile.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            runBlocking { AeadSerializer(aead, secretsJson(), AD).writeTo(secrets, out) }
            out.fd.sync()
        }
        if (!tmp.renameTo(vaultFile)) throw IOException("Couldn't replace the vault file")
    }

    private fun set(state: VaultState): VaultState = state.also { _state.value = it }

    private fun currentAlias() = prefs.get(ALIAS) ?: DEFAULT_ALIAS

    private data class Copy(val at: Long, val alias: String, val recovered: Boolean)

    private fun copies(): List<Copy> = prefs.get(COPIES).orEmpty().lines().mapNotNull { line ->
        val parts = line.trim().split(' ')
        val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val alias = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Copy(at, alias, parts.getOrNull(2) == "recovered")
    }

    private fun encode(copies: List<Copy>) = copies.joinToString("\n") { "${it.at} ${it.alias} ${if (it.recovered) "recovered" else "pending"}" }

    private fun keysetCopy(at: Long) = "$KEYSET_NAME.invalid.$at"

    private fun vaultCopy(at: Long) = File(vaultFile.parentFile, "${vaultFile.name}.invalid.$at")

    companion object {
        const val KEYSET_NAME = "locus_secrets_keyset"
        const val KEYSET_PREFS = "locus_tink"
        const val DEFAULT_ALIAS = "locus_secrets_master"
        private const val ALIAS = "locus_vault_master_alias"
        private const val COPIES = "locus_vault_set_aside"
        private const val FAILED_STARTS = "locus_vault_failed_starts"
        private const val PENDING_MOVE = "locus_vault_pending_move"
        const val KEEP_COPIES = 3
        const val RESET_AFTER_FAILED_STARTS = 3
        private val AD = DataStoreFiles.SECRETS.encodeToByteArray()

        const val RECOVERED_NOTICE =
            "Locus could open the saved keys it had set aside at the last reset, and restored them. " +
                "Keys you entered since were kept. Details are in Settings → About & privacy → Diagnostics."

        private fun describe(e: Throwable) =
            "${e::class.java.simpleName}: ${e.message}${e.cause?.let { " (${it::class.java.simpleName})" }.orEmpty()}"
    }
}

internal enum class KeyFailure { TEMPORARY, PERMANENT, UNKNOWN }

/**
 * What a key failure means, from the exception and its causes:
 * - [KeyPermanentlyInvalidatedException]: "the key can no longer be used because it has been
 *   permanently invalidated" (AOSP javadoc). Permanent.
 * - [BadPaddingException] (AEADBadTagException): the keyset doesn't decrypt under the master key, so it
 *   was wrapped by another key. tink-java 1.24.0's AndroidKeystoreAesGcm rethrows it without a retry,
 *   since "ciphertext is invalid. There is no point in retrying." Permanent.
 * - `android.security.KeyStoreException` (API 33): `isTransientFailure()` or
 *   `requiresUserAuthentication()` ("the device being locked") is temporary;
 *   ERROR_KEY_DOES_NOT_EXIST and ERROR_KEY_CORRUPTED ("could not be recovered") are permanent.
 * - [UserNotAuthenticatedException]: temporary.
 * Anything else (Tink's "exists but is unusable" KeyStoreException, ProviderException, a vendor's
 * odd error) is unknown, and only counts toward [VaultKeys.RESET_AFTER_FAILED_STARTS].
 */
internal fun classifyKeyFailure(e: Throwable): KeyFailure {
    var permanent = false
    var temporary = false
    for (t in generateSequence(e) { it.cause }.take(10)) {
        when {
            t is KeyPermanentlyInvalidatedException || t is BadPaddingException -> permanent = true
            t is UserNotAuthenticatedException -> temporary = true
            Build.VERSION.SDK_INT >= 33 && t is android.security.KeyStoreException -> when {
                t.isTransientFailure || t.requiresUserAuthentication() -> temporary = true
                t.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST ||
                    t.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_CORRUPTED -> permanent = true
            }
        }
    }
    return when {
        temporary -> KeyFailure.TEMPORARY
        permanent -> KeyFailure.PERMANENT
        else -> KeyFailure.UNKNOWN
    }
}

/**
 * The vault file's format: datastore-tink's AeadSerializer under whatever key [keys] has open. While
 * the key is closed, reads and writes throw [VaultUnavailableException].
 */
internal class VaultFormat(private val keys: () -> Aead) : Serializer<Map<String, String>> {
    override val defaultValue: Map<String, String> = emptyMap()

    override suspend fun readFrom(input: InputStream): Map<String, String> =
        AeadSerializer(keys(), secretsJson(), DataStoreFiles.SECRETS.encodeToByteArray()).readFrom(input)

    override suspend fun writeTo(t: Map<String, String>, output: OutputStream) =
        AeadSerializer(keys(), secretsJson(), DataStoreFiles.SECRETS.encodeToByteArray()).writeTo(t, output)
}
