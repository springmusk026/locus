package dev.locus.core.data.ssh

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.UntrustedContent
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.data.remote.RemoteEntry
import dev.locus.core.data.remote.RemoteSync
import dev.locus.core.data.remote.SftpFs
import dev.locus.core.data.remote.SftpTransfer
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
        execOn(client, host, command, timeoutMs, maxChars)
    }

    /** [exec] on a connection that is already open (remote execution keeps one open while it follows a job). */
    suspend fun execOn(client: SshClient, host: SshHost, command: String, timeoutMs: Long, maxChars: Int = 30_000): String {
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
            return "$status\n${out.toString().ifBlank { "(no output)" }}"
        } finally {
            session.close()
        }
    }

    /** Runs [block] with an SFTP session on [hostRef]: one connection, however many files it moves. */
    suspend fun <T> withSftp(hostRef: String, block: suspend (SftpFs) -> T): T = withClient(hostRef) { client, _ ->
        val sftp = client.openSftp().orThrow()
        try {
            block(SftpFs(sftp))
        } finally {
            runCatching { sftp.close() }
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
        return ToolResult(markSshOutput(text, args.str("host").orEmpty()), isError = !text.startsWith("Exit code 0."))
    }
}

/**
 * ssh_exec's text with the command's output marked as untrusted (it can hold anything the machine
 * read or fetched). The status line [SshService.exec] puts first ("Exit code 0.", a timeout or a signal)
 * stays outside, so the result reads the same way.
 */
internal fun markSshOutput(text: String, host: String): String {
    val status = text.substringBefore('\n')
    val output = text.substringAfter('\n', "")
    return "$status\n" + UntrustedContent.wrap(output.ifEmpty { "(no output)" }, UntrustedContent.note("output from $host"))
}

/** Limits for one sftp_upload or sftp_download: per file, per folder copy. */
internal object SftpLimits {
    const val MAX_FILE = 100L * 1024 * 1024
    const val MAX_FOLDER_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_FOLDER_FILES = 20_000

    /** Files moved at once in a folder copy, each with its own pipelined requests, over one connection. */
    const val PARALLEL_FILES = 4

    fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes bytes"
    }

    fun took(startedAt: Long): String = "%.1f s".format((System.currentTimeMillis() - startedAt) / 1000.0)
}

private class SftpDownload(private val ssh: SshService, private val workspace: Workspace) : Tool {
    override val risk = ToolRisk.WRITE
    override val spec = ToolSpec(
        "sftp_download",
        "Copy a file, or a folder with everything in it, from an SSH machine into the workspace, in one call. " +
            "Up to 100 MB per file; links on the machine are skipped.",
        ObjectSchema()
            .string("host", "Machine alias.")
            .string("remote_path", "Absolute path of a file or folder on the machine.")
            .string("workspace_path", "Destination in the workspace: the file's path, or the folder to copy a folder into.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Download ${args.str("host")}:${args.str("remote_path")} → ${args.str("workspace_path")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val host = args.str("host").orEmpty()
        val remote = args.str("remote_path").orEmpty().trim()
        val destination = args.str("workspace_path").orEmpty()
        val started = System.currentTimeMillis()
        return ssh.withSftp(host) { fs ->
            when (fs.kind(remote)) {
                null -> throw ToolException("$host:$remote doesn't exist.")
                RemoteEntry.Kind.DIR -> folder(fs, host, remote.trimEnd('/').ifEmpty { "/" }, destination, started)
                else -> {
                    val target = workspace.resolve(destination)
                    if (target.isDirectory) throw ToolException("Destination is a folder; give a file path.")
                    val bytes = file(fs, remote, target)
                    ToolResult("Downloaded ${SftpLimits.size(bytes)} to ${workspace.relative(target)} in ${SftpLimits.took(started)}.")
                }
            }
        }
    }

    private suspend fun folder(fs: SftpFs, host: String, remote: String, destination: String, started: Long): ToolResult {
        val files = mutableListOf<Pair<String, Long>>()
        val tooLarge = mutableListOf<String>()
        val refused = mutableListOf<String>()
        suspend fun walk(dir: String, prefix: String) {
            for (e in (fs.list(dir) ?: return).sortedBy { it.name }) {
                // A name is one path part: a server sending "..", "a/b" or a NUL is refused, not followed.
                if (!RemoteSync.isSafeName(e.name)) {
                    refused += "$prefix${e.name}"
                    continue
                }
                val rel = "$prefix${e.name}"
                when (e.kind) {
                    RemoteEntry.Kind.DIR -> walk("${dir.trimEnd('/')}/${e.name}", "$rel/")
                    RemoteEntry.Kind.FILE -> if (e.stat.size > SftpLimits.MAX_FILE) tooLarge += rel else files += rel to e.stat.size
                    RemoteEntry.Kind.OTHER -> Unit
                }
                if (files.size > SftpLimits.MAX_FOLDER_FILES) {
                    throw ToolException("$host:$remote has more than ${SftpLimits.MAX_FOLDER_FILES} files; copy a smaller folder, or pack it first (tar) with ssh_exec.")
                }
            }
        }
        walk(remote, "")
        val total = files.sumOf { it.second }
        if (total > SftpLimits.MAX_FOLDER_BYTES) {
            throw ToolException("$host:$remote holds ${SftpLimits.size(total)}, over the ${SftpLimits.size(SftpLimits.MAX_FOLDER_BYTES)} a folder copy takes; pack it (tar) with ssh_exec or copy part of it.")
        }
        val base = destination.trim().trimEnd('/')
        val bytes = java.util.concurrent.atomic.AtomicLong()
        val skipped = java.util.Collections.synchronizedList(mutableListOf<String>())
        SftpTransfer.forEachConcurrent(files, SftpLimits.PARALLEL_FILES) { (rel, _) ->
            val target = runCatching { workspace.resolve(if (base.isEmpty()) rel else "$base/$rel") }.getOrNull()
            if (target == null || target.isDirectory) {
                skipped += rel
            } else {
                bytes.addAndGet(file(fs, "$remote/$rel", target))
            }
        }
        val into = workspace.relative(workspace.resolve(base))
        return ToolResult(
            buildString {
                append("Downloaded ${files.size - skipped.size} files (${SftpLimits.size(bytes.get())}) from $host:$remote into $into in ${SftpLimits.took(started)}.")
                // Names come from the machine: marked as its content, not instructions.
                val names = buildString {
                    if (tooLarge.isNotEmpty()) append("Left on the machine, over 100 MB: ${tooLarge.joinToString()}.\n")
                    if (refused.isNotEmpty() || skipped.isNotEmpty()) append("Not copied (unsafe name, or a folder or link in the way): ${(refused + skipped).joinToString()}.")
                }
                if (names.isNotBlank()) append("\n").append(UntrustedContent.wrap(names.trim(), UntrustedContent.note("file names from $host")))
            },
        )
    }

    /** Downloads [remote] to [target] (from [Workspace.resolve]) through a temporary file; returns its size. */
    private suspend fun file(fs: SftpFs, remote: String, target: java.io.File): Long {
        val tmp = java.io.File(target.parentFile, ".${target.name}.part")
        // Linux can leave a link at the temporary name: remove it. The folders are made and the file
        // opened without following a link, and its real place is checked before a byte is written
        // (Workspace.write); the rename then fails if a folder on the way was swapped since.
        java.nio.file.Files.deleteIfExists(tmp.toPath())
        val bytes = try {
            workspace.write(tmp) { out -> java.io.BufferedOutputStream(out, 256 * 1024).let { b -> fs.download(remote, b, SftpLimits.MAX_FILE + 1).also { b.flush() } } }
        } catch (e: Throwable) {
            java.nio.file.Files.deleteIfExists(tmp.toPath())
            throw e
        }
        if (bytes > SftpLimits.MAX_FILE) {
            java.nio.file.Files.deleteIfExists(tmp.toPath())
            throw ToolException("$remote is larger than 100 MB.")
        }
        if (target.exists()) workspace.backup(target, dev.locus.core.agent.tools.TrashEntry.Reason.OVERWRITTEN)
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return bytes
    }
}

private class SftpUpload(private val ssh: SshService, private val workspace: Workspace) : Tool {
    override val risk = ToolRisk.EXECUTE
    override val spec = ToolSpec(
        "sftp_upload",
        "Copy a workspace file, or a folder with everything in it, to an SSH machine in one call, replacing files " +
            "that exist there. Missing folders on the machine are created. Up to 100 MB per file.",
        ObjectSchema()
            .string("host", "Machine alias.")
            .string("workspace_path", "File or folder in the workspace.")
            .string("remote_path", "Absolute destination on the machine: the file's path, or the folder to copy a folder into.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Upload ${args.str("workspace_path")} → ${args.str("host")}:${args.str("remote_path")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val host = args.str("host").orEmpty()
        val remote = args.str("remote_path").orEmpty().trim().trimEnd('/')
        if (remote.isEmpty()) throw ToolException("Give the destination path on the machine.")
        val source = workspace.existing(args.str("workspace_path").orEmpty())
        val started = System.currentTimeMillis()
        if (!source.isDirectory) {
            if (source.length() > SftpLimits.MAX_FILE) throw ToolException("File is larger than 100 MB.")
            val size = ssh.withSftp(host) { fs ->
                fs.mkdirs(remote.substringBeforeLast('/', "/").ifEmpty { "/" })
                // A replaced file keeps its permissions (a script stays executable).
                val mode = fs.mode(remote) ?: if (source.canExecute()) 0b111_101_101 else 0b110_100_100
                fs.upload(remote, { open(source) }, mode).size
            }
            return ToolResult("Uploaded ${SftpLimits.size(size)} to $host:$remote in ${SftpLimits.took(started)}.")
        }
        val files = mutableListOf<Pair<String, java.io.File>>()
        val tooLarge = mutableListOf<String>()
        fun walk(dir: java.io.File, prefix: String) {
            for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
                // Links aren't followed: one could point outside the workspace.
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                val rel = "$prefix${child.name}"
                when {
                    child.isDirectory -> walk(child, "$rel/")
                    child.isFile -> if (child.length() > SftpLimits.MAX_FILE) tooLarge += rel else files += rel to child
                }
                if (files.size > SftpLimits.MAX_FOLDER_FILES) throw ToolException("The folder has more than ${SftpLimits.MAX_FOLDER_FILES} files; pack it first (archive tool) or copy part of it.")
            }
        }
        walk(source, "")
        val total = files.sumOf { it.second.length() }
        if (total > SftpLimits.MAX_FOLDER_BYTES) throw ToolException("The folder holds ${SftpLimits.size(total)}, over the ${SftpLimits.size(SftpLimits.MAX_FOLDER_BYTES)} a folder copy takes.")
        val bytes = java.util.concurrent.atomic.AtomicLong()
        ssh.withSftp(host) { fs ->
            val folders = (listOf(remote) + files.map { "$remote/${it.first}".substringBeforeLast('/') }).distinct()
            SftpTransfer.forEachConcurrent(folders, SftpTransfer.PARALLEL_REQUESTS) { fs.mkdirs(it) }
            SftpTransfer.forEachConcurrent(files, SftpLimits.PARALLEL_FILES) { (rel, file) ->
                val mode = if (file.canExecute()) 0b111_101_101 else 0b110_100_100
                bytes.addAndGet(fs.upload("$remote/$rel", { open(file) }, mode).size)
            }
        }
        return ToolResult(
            buildString {
                append("Uploaded ${files.size} files (${SftpLimits.size(bytes.get())}) to $host:$remote in ${SftpLimits.took(started)}.")
                if (tooLarge.isNotEmpty()) append("\nNot copied, over 100 MB: ${tooLarge.joinToString()}.")
            },
        )
    }

    /** Opens [file] without following a link put in its place since the walk. */
    private fun open(file: java.io.File) = java.io.BufferedInputStream(
        java.nio.file.Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS),
        256 * 1024,
    )
}
