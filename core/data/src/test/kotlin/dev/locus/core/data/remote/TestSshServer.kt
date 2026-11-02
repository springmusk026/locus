package dev.locus.core.data.remote

import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey

/**
 * An in-process SSH and SFTP server on a loopback port (Apache MINA SSHD), the approach of the
 * benchmark's FixtureSsh: `exec` runs the host's `/bin/sh` in [home] with HOME set, and SFTP sees the
 * host's real paths, so exec and SFTP agree. [home]'s folder must be named [USER].
 *
 * Logins: [PASSWORD]; with [keyless], any password (keyboard-interactive with an empty answer, what
 * Locus's no-credentials login sends); with [publicKey], that key. [port] 0 picks a free one; pass the
 * old port and the same [keyDir] to bring the "same machine" back after [close].
 */
class TestSshServer(
    val home: File,
    private val keyDir: File,
    private val keyless: Boolean = false,
    private val publicKey: PublicKey? = null,
    port: Int = 0,
) : AutoCloseable {
    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        this.port = port
        keyPairProvider = SimpleGeneratorHostKeyProvider(File(keyDir, "hostkey.ser").toPath())
        passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && (keyless || password == PASSWORD) }
        publicKey?.let { expected -> publickeyAuthenticator = PublickeyAuthenticator { user, key, _ -> user == USER && key == expected } }
        commandFactory = CommandFactory { _, command -> HostCommand(command) }
        subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
        fileSystemFactory = NativeFileSystemFactory().apply { usersHomeDir = home.parentFile.path }
        start()
    }

    val port: Int get() = server.port

    override fun close() = server.stop(true)

    private inner class HostCommand(private val command: String) : Command {
        private lateinit var output: OutputStream
        private lateinit var error: OutputStream
        private lateinit var exit: ExitCallback
        private var process: Process? = null

        override fun setInputStream(input: InputStream) = Unit
        override fun setOutputStream(output: OutputStream) { this.output = output }
        override fun setErrorStream(error: OutputStream) { this.error = error }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }

        override fun start(channel: ChannelSession, environment: Environment) {
            val p = ProcessBuilder("/bin/sh", "-c", command)
                .directory(home)
                .apply { environment().apply { clear(); put("HOME", home.path); put("PATH", System.getenv("PATH") ?: "/usr/bin:/bin") } }
                .start()
            process = p
            Thread({
                p.outputStream.close()
                val out = Thread { runCatching { p.inputStream.copyTo(output); output.flush() } }.apply { start() }
                val err = Thread { runCatching { p.errorStream.copyTo(error); error.flush() } }.apply { start() }
                val code = p.waitFor()
                out.join()
                err.join()
                runCatching { exit.onExit(code) }
            }, "test-ssh-exec").apply { isDaemon = true }.start()
        }

        override fun destroy(channel: ChannelSession) {
            process?.destroyForcibly()
        }
    }

    companion object {
        const val USER = "builder"
        const val PASSWORD = "test-password-not-secret"
    }
}
