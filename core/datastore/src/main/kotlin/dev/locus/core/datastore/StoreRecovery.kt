package dev.locus.core.datastore

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * A DataStore corruption handler that keeps the unreadable file as `<name>.corrupt` (so nothing is
 * thrown away for good), records what happened in [log], tells the user through [notice] when given,
 * and starts over with [default]. Without one, DataStore's CorruptionException reaches every reader
 * and the app fails on every start.
 */
internal fun <T> corruptionHandler(
    file: () -> File,
    what: String,
    default: T,
    log: DiagnosticsLog,
    notice: String? = null,
): ReplaceFileCorruptionHandler<T> = ReplaceFileCorruptionHandler { e ->
    val kept = runCatching {
        val source = file()
        File(source.parentFile, source.name + ".corrupt").also { source.copyTo(it, overwrite = true) }
    }.getOrNull()
    log.record(
        DiagnosticsLog.Kind.STORAGE,
        "$what couldn't be read and was reset",
        "${e.message ?: e::class.java.simpleName}${e.cause?.let { " (${it::class.java.simpleName})" }.orEmpty()}\n" +
            (kept?.let { "The unreadable file was kept as ${it.name}." } ?: "The unreadable file couldn't be copied."),
    )
    notice?.let(log::addNotice)
    default
}

/**
 * Opens the vault's key, recovering when the Android Keystore key behind it is gone or invalidated.
 *
 * Tink's AndroidKeysetManager throws when the keyset can't be decrypted with the master key ("This is
 * an irrecoverable error. You'd have to delete the keyset in Shared Preferences and all existing data
 * encrypted with it"), and a KeyStoreException when "the master key exists but is unusable"
 * (tink-java 1.24.0, AndroidKeysetManager). Thrown inside Hilt, that would crash every launch.
 *
 * So: try [open]; on a key failure try once more (the Keystore can fail transiently, and resetting
 * throws away every saved key), then [reset] (delete the keyset, the master key and the unreadable
 * vault file), call [onReset] so the user is told, and open again, which makes fresh keys. A failure
 * after the reset is thrown.
 */
internal fun <T> openVaultKey(open: () -> T, reset: () -> Unit, onReset: (Throwable) -> Unit, pause: () -> Unit = { Thread.sleep(200) }): T {
    val first = try {
        return open()
    } catch (e: Exception) {
        if (!isKeyFailure(e)) throw e
        e
    }
    pause()
    try {
        return open()
    } catch (e: Exception) {
        if (!isKeyFailure(e)) throw e
    }
    reset()
    onReset(first)
    return open()
}

/** What Tink and the Keystore throw when the keyset can't be unwrapped: GeneralSecurityException (KeyStoreException, AEADBadTagException), ProviderException, or IOException for an unparsable keyset. */
internal fun isKeyFailure(e: Throwable): Boolean = e is GeneralSecurityException || e is ProviderException || e is IOException

