package dev.locus.core.data.ssh

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import dev.locus.core.agent.tools.RemoteJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyFingerprint
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SessionExit
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SshClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSH to machines the user added in Settings. Host keys are pinned: the user confirms the
 * fingerprint once, and any other key is refused afterwards. Credentials come from the vault.
 */
@Singleton
class SshService @Inject constructor(private val settings: SettingsRepository) {

    /** Connects without authenticating to read a server's host key fingerprint, for the user to confirm. */
    suspend fun probeFingerprint(host: String, port: Int): String = withContext(Dispatchers.IO) {
        var seen: String? = null
        val client = SshClient(host, port = port, hostKeyVerifier = object : HostKeyVerifier {
            override suspend fun verify(key: PublicKey): Boolean {
                seen = KeyFingerprint.sha256(key.encoded)
                return false
            }
        })
        when (val result = client.connect()) {
            is ConnectResult.TransportError -> throw ToolException("Can't reach $host:$port (${result.cause.message}).")
            is ConnectResult.AlgorithmMismatch -> throw ToolException("No common SSH algorithms: ${result.message}")
            else -> Unit
        }
        runCatching { client.disconnect() }
        seen ?: throw ToolException("The server did not present a host key.")
    }

    /** Runs [block] on an authenticated connection to [hostRef] (alias or id), then disconnects. */
    suspend fun <T> withClient(hostRef: String, block: suspend (SshClient, SshHost) -> T): T = withContext(Dispatchers.IO) {
        val host = settings.current().sshHosts.firstOrNull { it.alias.equals(hostRef, true) || it.id == hostRef }
            ?: throw ToolException("No SSH machine “$hostRef”. Known: ${settings.current().sshHosts.joinToString { it.alias }.ifEmpty { "none, ask the user to add one in Settings" }}")
        if (host.fingerprint.isBlank()) throw ToolException("${host.alias}'s host key hasn't been confirmed yet. Ask the user to confirm it in Settings → SSH machines.")
        val client = SshClient(host.host, port = host.port, hostKeyVerifier = object : HostKeyVerifier {
            override suspend fun verify(key: PublicKey) = KeyFingerprint.sha256(key.encoded) == host.fingerprint
        })
        when (val result = client.connect()) {
            ConnectResult.Success -> Unit
            is ConnectResult.HostKeyRejected -> throw ToolException(
                "SECURITY: ${host.alias} presented a different host key (${KeyFingerprint.sha256(result.key.encoded)}) than the one the user confirmed. Not connecting. Tell the user.",
            )
            is ConnectResult.TransportError -> throw ToolException("Can't reach ${host.host}:${host.port} (${result.cause.message}).")
            is ConnectResult.AlgorithmMismatch -> throw ToolException("No common SSH algorithms: ${result.message}")
            is ConnectResult.ProtocolError -> throw ToolException("SSH error: ${result.message}")
        }
        try {
            val (password, key, passphrase) = settings.sshCredentials(host.id)
            val login = SshLogin.choose(host, password, key, passphrase) ?: throw ToolException("No credentials saved for ${host.alias}.")
            val auth = when (login) {
                is SshLogin.Key -> client.authenticatePublicKey(host.user, login.privateKey, login.passphrase)
                is SshLogin.Password -> client.authenticatePassword(host.user, login.password)
                SshLogin.NoCredentials -> client.authenticate(host.user, NoCredentialsAuthHandler)
            }
            when (auth) {
                AuthResult.Success -> Unit
                is AuthResult.Failure if login == SshLogin.NoCredentials -> throw ToolException(
                    "${host.alias} wants credentials (server allows: ${auth.allowedMethods.joinToString()}). " +
                        "Ask the user to set a key or password for it in Settings → SSH machines.",
                )
                is AuthResult.Failure -> throw ToolException("${host.alias} rejected the login (server allows: ${auth.allowedMethods.joinToString()}).")
                is AuthResult.Error -> throw ToolException("Login to ${host.alias} failed: ${auth.message}")
                else -> throw ToolException("Login to ${host.alias} failed.")
            }
            block(client, host)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    suspend fun exec(hostRef: String, command: String, timeoutMs: Long, maxChars: Int = 30_000): String = withClient(hostRef) { client, host ->
        val session = client.openSession() ?: throw ToolException("Couldn't open a session on ${host.alias}.")
        try {
            if (!session.requestExec(command)) throw ToolException("${host.alias} refused to run the command.")
            val out = StringBuilder()
            val exit = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val stdout = async { for (chunk in session.stdout) append(out, chunk, maxChars) }
                    val stderr = async { for (chunk in session.stderr) append(out, chunk, maxChars) }
                    stdout.await()
                    stderr.await()
                }
                session.exitInfo.await()
            }
            val status = when (exit) {
                null -> "Stopped after ${timeoutMs / 1000}s (timeout)."
                is SessionExit.Status -> "Exit code ${exit.code}."
                is SessionExit.Signal -> "Killed by signal ${exit.signalName}."
            }
            "$status\n${out.toString().ifBlank { "(no output)" }}"
        } finally {
            session.close()
        }
    }

    suspend fun download(hostRef: String, remotePath: String, sink: (ByteArray) -> Unit): Long = withClient(hostRef) { client, _ ->
        val sftp = client.openSftp().orThrow()
        val handle = sftp.open(remotePath, setOf(SftpOpenFlag.READ)).orThrow()
        var offset = 0L
        try {
            while (true) {
                val chunk = sftp.read(handle, offset, 32 * 1024).orThrow() ?: break
                if (chunk.isEmpty()) break
                sink(chunk)
                offset += chunk.size
                if (offset > MAX_TRANSFER) throw ToolException("File is larger than ${MAX_TRANSFER / 1_000_000} MB.")
            }
        } finally {
            sftp.close(handle)
        }
        offset
    }

    suspend fun upload(hostRef: String, remotePath: String, data: ByteArray) = withClient(hostRef) { client, _ ->
        val sftp = client.openSftp().orThrow()
        val handle = sftp.open(remotePath, setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE, SftpOpenFlag.TRUNCATE)).orThrow()
        try {
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + 32 * 1024, data.size)
                sftp.write(handle, offset.toLong(), data.copyOfRange(offset, end)).orThrow()
                offset = end
            }
        } finally {
            sftp.close(handle)
        }
    }

    private fun append(out: StringBuilder, chunk: ByteArray, max: Int) {
        synchronized(out) {
            if (out.length < max) out.append(String(chunk, Charsets.UTF_8).take(max - out.length))
        }
    }

    private fun <T> SftpResult<T>.orThrow(): T = when (this) {
        is SftpResult.Success -> value
        is SftpResult.ServerError -> throw ToolException("SFTP: $this")
        is SftpResult.ProtocolError -> throw ToolException("SFTP protocol error: $message")
        else -> throw ToolException("SFTP failed: $this")
    }

    private companion object {
        const val MAX_TRANSFER = 100L * 1024 * 1024
    }
}

fun sshTools(ssh: SshService, settings: SettingsRepository, workspace: Workspace): List<Tool> =
    listOf(SshHosts(settings), SshExec(ssh), SshJobStart(ssh), SshJobStatus(ssh), SshJobStop(ssh), SftpDownload(ssh, workspace), SftpUpload(ssh, workspace))

/** Long jobs that outlive the SSH session and Locus itself (see [RemoteJobs]). */
private class SshJobStart(private val ssh: SshService) : Tool {
    override val risk = ToolRisk.EXECUTE
    override val spec = ToolSpec(
        "ssh_job_start",
        "Start a long-running command on one of the user's machines, detached, so it keeps running if the phone sleeps or Locus " +
            "is closed (builds, deploys, downloads, training). Returns a job id; check it later with ssh_job_status. " +
            "Use ssh_exec instead for anything that finishes within a couple of minutes.",
        ObjectSchema()
            .string("host", "Machine alias.")
            .string("command", "Shell command or script (POSIX sh). Runs in the login user's home directory.")
            .string("name", "Short name for the job, e.g. \"build site\".", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Start a background job on ${args.str("host")}: ${args.str("command")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = RemoteJobs.newId(args.str("name")) { java.util.UUID.randomUUID().toString() }
        val text = ssh.exec(args.str("host").orEmpty(), RemoteJobs.start(id, args.str("command").orEmpty()), 30_000)
        if (!text.startsWith("Exit code 0.")) return ToolResult(text, isError = true)
        return ToolResult("Started job $id on ${args.str("host")}. Check it with ssh_job_status (host, id \"$id\"). Output is kept in ~/.locus-jobs/$id.log.")
    }
}

private class SshJobStatus(private val ssh: SshService) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "ssh_job_status",
        "Check a background job started with ssh_job_start: running, finished with its exit code, or stopped, plus the end of its output. " +
            "Without an id, lists all jobs on the machine.",
        ObjectSchema()
            .string("host", "Machine alias.")
            .string("id", "Job id from ssh_job_start.", required = false)
            .integer("lines", "Output lines to show from the end (default 40, max 500).", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Check job ${args.str("id") ?: "list"} on ${args.str("host")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.str("id")
        if (id != null && !RemoteJobs.isValidId(id)) throw ToolException("Unknown job id \"$id\". Call without an id to list jobs.")
        val command = if (id == null) RemoteJobs.list() else RemoteJobs.status(id, args.int("lines") ?: 40)
        val text = ssh.exec(args.str("host").orEmpty(), command, 30_000)
        return ToolResult(text.removePrefix("Exit code 0.").trim(), isError = !text.startsWith("Exit code 0."))
    }
}

private class SshJobStop(private val ssh: SshService) : Tool {
    override val risk = ToolRisk.EXECUTE
    override val spec = ToolSpec(
        "ssh_job_stop",
        "Stop a running background job started with ssh_job_start.",
        ObjectSchema().string("host", "Machine alias.").string("id", "Job id.").build(),
    )

    override fun describe(args: JsonObject) = "Stop job ${args.str("id")} on ${args.str("host")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.str("id").orEmpty()
        if (!RemoteJobs.isValidId(id)) throw ToolException("Unknown job id \"$id\".")
        val text = ssh.exec(args.str("host").orEmpty(), RemoteJobs.stop(id), 30_000)
        return ToolResult(text.removePrefix("Exit code 0.").trim(), isError = !text.startsWith("Exit code 0."))
    }
}

private class SshHosts(private val settings: SettingsRepository) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec("ssh_hosts", "List the machines the user has allowed you to reach over SSH.", ObjectSchema().build())
    override fun describe(args: JsonObject) = "List SSH machines"
    override suspend fun execute(args: JsonObject): ToolResult {
        val hosts = settings.current().sshHosts
        if (hosts.isEmpty()) return ToolResult("No SSH machines yet. The user can add one in Settings → SSH machines.")
        return ToolResult(
            hosts.joinToString("\n") { h ->
                "- ${h.alias}: ${h.user}@${h.host}:${h.port}" +
                    (if (h.authMethod == SshAuth.NONE) " (no authentication, sandbox machine)" else "") +
                    (if (h.fingerprint.isBlank()) " (host key not confirmed yet)" else "")
            },
        )
    }
}

private class SshExec(private val ssh: SshService) : Tool {
    override val risk = ToolRisk.EXECUTE
    override val spec = ToolSpec(
        "ssh_exec",
        "Run a shell command on one of the user's machines over SSH (see ssh_hosts). Non-interactive; returns exit code and output. " +
            "For long jobs, start them detached (e.g. nohup … &) and check back.",
        ObjectSchema()
            .string("host", "Machine alias.")
            .string("command", "Shell command.")
            .integer("timeout_seconds", "Defaults to 120, max 1800.")
            .build(),
    )

    override fun describe(args: JsonObject) = "On ${args.str("host")}: ${args.str("command")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val timeout = (args.int("timeout_seconds") ?: 120).coerceIn(1, 1800)
        val text = ssh.exec(args.str("host").orEmpty(), args.str("command").orEmpty(), timeout * 1000L)
        return ToolResult(text, isError = !text.startsWith("Exit code 0."))
    }
}

private class SftpDownload(private val ssh: SshService, private val workspace: Workspace) : Tool {
    override val risk = ToolRisk.WRITE
    override val spec = ToolSpec(
        "sftp_download",
        "Copy a file from an SSH machine into the workspace (max 100 MB).",
        ObjectSchema()
            .string("host", "Machine alias.")
            .string("remote_path", "Absolute path on the machine.")
            .string("workspace_path", "Destination path in the workspace.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Download ${args.str("host")}:${args.str("remote_path")} → ${args.str("workspace_path")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val target = workspace.resolve(args.str("workspace_path").orEmpty())
        if (target.isDirectory) throw ToolException("Destination is a folder; give a file path.")
        target.parentFile?.mkdirs()
        val tmp = java.io.File(target.parentFile, ".${target.name}.part")
        val bytes = tmp.outputStream().use { out -> ssh.download(args.str("host").orEmpty(), args.str("remote_path").orEmpty()) { out.write(it) } }
        if (target.exists()) workspace.backup(target, dev.locus.core.agent.tools.TrashEntry.Reason.OVERWRITTEN)
        tmp.renameTo(target)
        return ToolResult("Downloaded $bytes bytes to ${workspace.relative(target)}.")
    }
}

private class SftpUpload(private val ssh: SshService, private val workspace: Workspace) : Tool {
    override val risk = ToolRisk.EXECUTE
    override val spec = ToolSpec(
        "sftp_upload",
        "Copy a workspace file to an SSH machine, replacing the remote file if it exists.",
        ObjectSchema()
            .string("host", "Machine alias.")
            .string("workspace_path", "File in the workspace.")
            .string("remote_path", "Absolute destination path on the machine.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Upload ${args.str("workspace_path")} → ${args.str("host")}:${args.str("remote_path")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val file = workspace.existing(args.str("workspace_path").orEmpty())
        if (file.length() > 100L * 1024 * 1024) throw ToolException("File is larger than 100 MB.")
        ssh.upload(args.str("host").orEmpty(), args.str("remote_path").orEmpty(), file.readBytes())
        return ToolResult("Uploaded ${file.length()} bytes to ${args.str("host")}:${args.str("remote_path")}.")
    }
}
