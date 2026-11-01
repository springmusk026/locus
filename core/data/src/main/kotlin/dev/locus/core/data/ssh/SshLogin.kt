package dev.locus.core.data.ssh

import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost
import org.connectbot.sshlib.AuthHandler
import org.connectbot.sshlib.AuthPublicKey
import org.connectbot.sshlib.KeyboardInteractiveCallback

/** How [SshService] logs in to a host, chosen from its [SshAuth] and the credentials saved in the vault. */
internal sealed interface SshLogin {
    data class Key(val privateKey: String, val passphrase: String?) : SshLogin

    data class Password(val password: String) : SshLogin

    /** No credentials: see [NoCredentialsAuthHandler]. */
    data object NoCredentials : SshLogin

    companion object {
        /** Null when the host needs credentials and none are saved. */
        fun choose(host: SshHost, password: String?, privateKey: String?, passphrase: String?): SshLogin? {
            val method = host.authMethod
            return when {
                // Saved secrets are ignored: the user chose not to send any.
                method == SshAuth.NONE -> NoCredentials
                method == SshAuth.KEY && !privateKey.isNullOrBlank() -> Key(privateKey, passphrase?.ifBlank { null })
                // A key host without a key falls back to a saved password, as before.
                !password.isNullOrBlank() -> Password(password)
                else -> null
            }
        }
    }
}

/**
 * Drives sshlib's [AuthHandler] flow without any credentials. sshlib's `SshClient.authenticate` sends the
 * RFC 4252 "none" request first and succeeds right there if the server accepts it; otherwise it tries the
 * methods the server lists. This handler offers no public keys and no password, and answers
 * keyboard-interactive prompts (RFC 4256) with empty strings, which is what servers that let anyone in
 * usually ask for. Anything else fails with the server's list of allowed methods.
 */
internal object NoCredentialsAuthHandler : AuthHandler {
    override suspend fun onPublicKeysNeeded(): List<AuthPublicKey> = emptyList()

    override suspend fun onSignatureRequest(key: AuthPublicKey, dataToSign: ByteArray): ByteArray? = null

    override suspend fun onKeyboardInteractivePrompt(
        name: String,
        instruction: String,
        prompts: List<KeyboardInteractiveCallback.Prompt>,
    ): List<String> = prompts.map { "" }

    override suspend fun onPasswordNeeded(): String? = null
}
