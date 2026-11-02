package dev.locus.bench

import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The fixture SSH server (Apache MINA SSHD, in process, on a loopback port): password login as [USER],
 * `exec` through the host's `/bin/sh` with [home] as the working directory and HOME, and SFTP over the
 * host's file system starting in [home]. [home]'s folder must be named [USER] (MINA's home layout).
 *
 * Commands get [environment] (no provider key, HOME = [home]). Like [HostShell], this is not a sandbox.
 */
class FixtureSsh(val home: File, keyDir: File, environment: Map<String, String>) : AutoCloseable {
    private val env = environment + ("HOME" to home.path)
    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider(File(keyDir, "hostkey.ser").toPath())
        passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
        commandFactory = CommandFactory { _, command -> HostCommand(command) }
        subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
        // The host's real paths, starting in [home]: exec and SFTP see the same absolute paths.
        fileSystemFactory = NativeFileSystemFactory().apply { usersHomeDir = home.parentFile.path }
        start()
    }

    val port: Int get() = server.port

    override fun close() = server.stop(true)

    /** One `exec` request: `/bin/sh -c` in [home], streams copied both ways. */
    private inner class HostCommand(private val command: String) : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var error: OutputStream
        private lateinit var exit: ExitCallback
        private var process: Process? = null

        override fun setInputStream(input: InputStream) { this.input = input }
        override fun setOutputStream(output: OutputStream) { this.output = output }
        override fun setErrorStream(error: OutputStream) { this.error = error }
        override fun setExitCallback(callback: ExitCallback) { exit = callback }

        override fun start(channel: ChannelSession, environment: Environment) {
            val p = ProcessBuilder("/bin/sh", "-c", command)
                .directory(home)
                .apply { environment().apply { clear(); putAll(env) } }
                .start()
            process = p
            Thread({
                p.outputStream.close()
                val out = Thread { p.inputStream.copyTo(output); output.flush() }.apply { start() }
                val err = Thread { p.errorStream.copyTo(error); error.flush() }.apply { start() }
                val code = p.waitFor()
                out.join()
                err.join()
                exit.onExit(code)
            }, "bench-ssh-exec").apply { isDaemon = true }.start()
        }

        override fun destroy(channel: ChannelSession) {
            process?.destroyForcibly()
        }
    }

    companion object {
        const val USER = "bench"
        const val PASSWORD = "bench-password-not-secret"
    }
}
