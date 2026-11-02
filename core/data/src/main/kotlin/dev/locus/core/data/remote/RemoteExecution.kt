package dev.locus.core.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.ChatScope
import dev.locus.core.agent.SecretScrubber
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.UntrustedContent
import dev.locus.core.agent.tools.FileChange
import dev.locus.core.agent.tools.RemoteBackend
import dev.locus.core.agent.tools.RemoteJobs
import dev.locus.core.agent.tools.RemoteRun
import dev.locus.core.agent.tools.RemoteTarget
import dev.locus.core.agent.tools.RemoteText
import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.ssh.SshService
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.ProjectRemote
import dev.locus.core.model.SshHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SshClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What a project's machine is doing for the chat's status chip. */
enum class RemoteActivity { SYNCING, RUNNING }

/** What linking a project to a machine would copy, shown before anything is copied. */
data class LinkPreview(
    val alias: String,
    /** The cleaned absolute folder on the machine. */
    val folder: String,
    val folderExists: Boolean,
    /** Files that would be copied to the machine, and their total size. */
    val upload: List<String>,
    val uploadBytes: Long,
    /** Files already on the machine that the phone doesn't have: they'd be copied to the phone. */
    val download: List<String>,
    /** Secret files (by name, or holding a vault value): never copied. */
    val secrets: List<String>,
    /** Folders that stay where they are (git, dependencies, builds, `.locusignore`). */
    val stays: List<String>,
    val tooLarge: List<String>,
)

/**
 * Remote execution for projects linked to one of the user's SSH machines (docs/design/remote-execution.md).
 * Owns the sync (SFTP, incremental, three-way against the last agreed state), the detached jobs
 * commands run as, following their logs across reconnects, and bringing changes back with change
 * tracking. Every connection goes through [SshService], so host keys are pinned and key, password and
 * keyless logins work the same as for ssh_exec.
 */
@Singleton
class RemoteExecution @Inject constructor(
    private val settings: SettingsRepository,
    private val ssh: SshService,
    private val workspace: Workspace,
    private val changes: dev.locus.core.data.repository.ChangeRepository,
    private val vault: SecretStore,
    @ApplicationContext context: Context,
) : RemoteBackend {
    private val stateDir = File(context.filesDir, "remote-sync")
    private val tmpRoot = File(context.cacheDir, "remote-sync")
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** How often a running job is checked. Tests shorten it. */
    internal var pollMs = 1_000L

    /** How long to wait before reconnecting after the connection dropped mid-wait. */
    internal var reconnectMs = 3_000L

    private val _activity = MutableStateFlow<Map<String, RemoteActivity>>(emptyMap())

    /** Per project id: syncing or running right now. Absent when idle. */
    val activity: StateFlow<Map<String, RemoteActivity>> = _activity.asStateFlow()

    // ---------------------------------------------------------------------------------------------
    // Linking (the project editor)
    // ---------------------------------------------------------------------------------------------

    /** The login user's home on [hostId], to offer and check the remote folder. */
    suspend fun home(hostId: String): String = ssh.withClient(hostId) { client, host -> homeOf(client, host) }

    /** What linking [projectFolder] to [rawFolder] on [hostId] would copy. Nothing is changed. */
    suspend fun preview(projectFolder: String, hostId: String, rawFolder: String): LinkPreview = withContext(Dispatchers.IO) {
        ssh.withClient(hostId) { client, host ->
            val fs = sftp(client, host)
            val home = homeOf(client, host)
            val folder = ProjectRemote.cleanFolder(rawFolder, home)
                ?: throw ToolException("Use an absolute folder on ${host.alias}, like ~/locus/app; not / or your home folder itself.")
            val sync = syncer(fs, projectFolder, folder)
            val local = sync.scanLocal()
            val secrets = vaultValues()
            val contentSecrets = local.files.filter { (_, f) -> f.size <= SECRET_SCAN_MAX && SyncRules.containsSecret(f.file.readBytes(), secrets) }.keys
            val upload = local.files.keys - contentSecrets
            val remote = sync.scanRemote().files
            LinkPreview(
                host.alias, folder, remote != null,
                upload.toList(), upload.sumOf { local.files.getValue(it).size },
                remote.orEmpty().keys.filter { it !in local.files }.toList(),
                local.secrets + contentSecrets, local.stays, local.tooLarge,
            )
        }
    }

    /**
     * Links the project to [folder] on [hostId] and does the first sync both ways: the user saw it in
     * [preview] and confirmed. [onProgress] gets files done and the total, for the sheet.
     */
    suspend fun link(projectId: String, projectFolder: String, hostId: String, folder: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): SyncReport = withContext(Dispatchers.IO) {
        val target = RemoteTarget(projectId, hostId, aliasOf(hostId), folder, projectFolder)
        stateFile(projectId).delete()
        val report = busy(projectId, RemoteActivity.SYNCING) {
            ssh.withClient(hostId) { client, host ->
                val fs = sftp(client, host)
                fs.mkdirs(folder)
                lock(projectId) { syncBoth(fs, target, owner = null, toolName = "", onProgress) }
            }
        }
        // Saved only once the first copy went through; sealed with the permissions.
        settings.setProjectRemote(projectId, ProjectRemote(hostId, folder, System.currentTimeMillis()))
        report
    }

    /** Commands run on the phone again. Files on the machine stay there. */
    suspend fun unlink(projectId: String) {
        settings.setProjectRemote(projectId, null)
        withContext(Dispatchers.IO) { stateFile(projectId).delete() }
    }

    // ---------------------------------------------------------------------------------------------
    // RemoteBackend (run_command, services, verification)
    // ---------------------------------------------------------------------------------------------

    override suspend fun target(): RemoteTarget? {
        val scope = ChatScope.current() ?: return null
        val s = settings.current()
        val remote = s.projectRemotes[scope.projectId] ?: return null
        val host = s.sshHosts.firstOrNull { it.id == remote.hostId }
            ?: throw ToolException(
                "This project is set to run commands on an SSH machine that has since been removed, so nothing ran. Ask the user to choose " +
                    "this phone or another machine in the project's settings (Where commands run).",
            )
        return RemoteTarget(scope.projectId, host.id, host.alias, remote.folder, scope.folder)
    }

    override suspend fun run(target: RemoteTarget, command: String, cwd: String, timeoutMs: Long, saveFullOutputTo: File?): RemoteRun = withContext(Dispatchers.IO) {
        val ctx = currentCoroutineContext()[ToolCallContext]
        val id = jobId(target, "rc", hash("$cwd\n$command").take(10))
        val started = System.nanoTime()
        val deadline = System.currentTimeMillis() + timeoutMs
        val log = LogSink(File(tmpRoot.apply { mkdirs() }, "$id.log"))
        try {
            // Sync up and start, or find the same command still running (or finished but not yet reported).
            val (upReport, startProbe) = busy(target.projectId, RemoteActivity.SYNCING) {
                ssh.withClient(target.hostId) { client, host ->
                    val fs = sftp(client, host)
                    val up = lock(target.projectId) { syncUp(fs, target) }
                    val before = probe(client, host, id)
                    if (before.running || (before.done && !before.seen)) return@withClient up to before
                    val reply = ssh.execOn(client, host, RemoteJobs.start(id, script(target, cwd, command)), 30_000)
                    if (!reply.startsWith("Exit code 0.")) throw ToolException("Couldn't start the command on ${host.alias}: ${reply.substringAfter('\n')}")
                    up to null
                }
            }
            val attached = startProbe != null
            val end = try {
                busy(target.projectId, RemoteActivity.RUNNING) { follow(target, id, deadline, log) }
            } catch (e: CancellationException) {
                // The user stopped the task: stop the command on the machine too.
                withContext(NonCancellable) { withTimeoutOrNull(15_000) { runCatching { ssh.exec(target.hostId, RemoteJobs.stop(id), 10_000) } } }
                throw e
            }
            val scrubber = SecretScrubber(vaultValues())
            if (!end.done) {
                return@withContext RemoteRun(null, scrubber.scrub(log.text()), stillRunning = true, jobId = id, durationMs = null, syncNote = upNote(upReport), attached = attached)
            }
            val down = busy(target.projectId, RemoteActivity.SYNCING) {
                ssh.withClient(target.hostId) { client, host ->
                    val report = lock(target.projectId) { syncDown(sftp(client, host), target, ctx, "run_command") }
                    ssh.execOn(client, host, RemoteJobs.markSeen(id), 30_000)
                    report
                }
            }
            val duration = when {
                !attached -> (System.nanoTime() - started) / 1_000_000
                startProbe?.running == true && end.startedAt > 0 -> (end.now - end.startedAt) * 1000
                else -> null
            }
            val saved = if (log.truncated && saveFullOutputTo != null) {
                saveFullOutputTo.parentFile?.mkdirs()
                saveFullOutputTo.writeText(scrubber.scrub(log.spooled()))
                saveFullOutputTo
            } else {
                null
            }
            RemoteRun(
                end.exitCode, scrubber.scrub(log.text()), stillRunning = false, jobId = id, durationMs = duration,
                syncNote = upNote(upReport) + " " + downNote(down, target), attached = attached,
                savedTo = saved, savedBytes = saved?.length() ?: 0, savedCapped = log.capped,
            )
        } finally {
            log.close()
        }
    }

    override suspend fun startService(target: RemoteTarget, name: String, command: String, cwd: String): RemoteText = withContext(Dispatchers.IO) {
        val id = serviceId(target, name)
        val (up, probe) = busy(target.projectId, RemoteActivity.SYNCING) {
            ssh.withClient(target.hostId) { client, host ->
                val up = lock(target.projectId) { syncUp(sftp(client, host), target) }
                // Starting a service with an existing name restarts it, as on the phone.
                if (probe(client, host, id).running) ssh.execOn(client, host, RemoteJobs.stop(id), 30_000)
                val reply = ssh.execOn(client, host, RemoteJobs.start(id, script(target, cwd, command)), 30_000)
                if (!reply.startsWith("Exit code 0.")) throw ToolException("Couldn't start “$name” on ${host.alias}: ${reply.substringAfter('\n')}")
                delay(SERVICE_SETTLE_MS)
                up to probe(client, host, id)
            }
        }
        val tail = tail(target, id, probe.home, 1_500)
        val note = "[On ${target.alias} in ${target.folder}, not on the phone. ${upNote(up)}]"
        if (probe.running) {
            RemoteText(
                "Service “$name” is running on ${target.alias} as job $id. It keeps running there until stopped, also while the phone sleeps or Locus is closed. " +
                    "It listens on ${target.alias}, not on the phone, so http://localhost in the browser won't reach it; check it with run_command (e.g. curl), " +
                    "and read its output with linux_service_log.\n$note\n" + untrusted(tail),
            )
        } else {
            RemoteText("Service “$name” exited right away on ${target.alias} (exit code ${probe.exitCode}).\n$note\n" + untrusted(tail), isError = true)
        }
    }

    override suspend fun serviceLog(target: RemoteTarget, name: String, lines: Int, filter: Regex?): RemoteText = withContext(Dispatchers.IO) {
        val id = resolveJob(target, name)
        val (probe, synced) = ssh.withClient(target.hostId) { client, host ->
            // SFTP first, in every connection: sshlib can't open it once an exec channel has closed.
            val fs = sftp(client, host)
            val p = probe(client, host, id)
            if (p.state == "none") return@withClient p to null
            val synced = if (p.done && !p.seen) {
                busy(target.projectId, RemoteActivity.SYNCING) {
                    lock(target.projectId) { syncDown(fs, target, currentCoroutineContext()[ToolCallContext], "linux_service_log") }
                }.also { ssh.execOn(client, host, RemoteJobs.markSeen(id), 30_000) }
            } else {
                null
            }
            p to synced
        }
        if (probe.state == "none") {
            val known = listJobs(target).joinToString { it.first }.ifEmpty { "none have been started" }
            return@withContext RemoteText("No service or job “$name” on ${target.alias}. Known: $known.", isError = true)
        }
        val text = tail(target, id, probe.home, LOG_WINDOW)
        val picked = lastLines(text, lines, filter)
        val state = when (probe.state) {
            "running" -> "running"
            "done" -> "finished, exit code ${probe.exitCode}"
            else -> "stopped without finishing (killed, or the machine restarted)"
        }
        val sync = synced?.let { " " + downNote(it, target) }.orEmpty()
        RemoteText("$name on ${target.alias} ($state).$sync\n" + untrusted(picked))
    }

    override suspend fun stopService(target: RemoteTarget, name: String): RemoteText = withContext(Dispatchers.IO) {
        val id = resolveJob(target, name)
        val (text, down) = ssh.withClient(target.hostId) { client, host ->
            val fs = sftp(client, host)
            val out = ssh.execOn(client, host, RemoteJobs.stop(id), 30_000).substringAfter('\n').trim()
            val down = busy(target.projectId, RemoteActivity.SYNCING) {
                lock(target.projectId) { syncDown(fs, target, currentCoroutineContext()[ToolCallContext], "linux_service_stop") }
            }
            out to down
        }
        when (text) {
            "stopped" -> RemoteText("Stopped “$name” on ${target.alias}. ${downNote(down, target)}")
            "already finished" -> RemoteText("“$name” had already finished on ${target.alias}. ${downNote(down, target)}")
            else -> RemoteText("No running service “$name” on ${target.alias}.", isError = true)
        }
    }

    override suspend fun listServices(target: RemoteTarget): RemoteText = withContext(Dispatchers.IO) {
        val jobs = listJobs(target)
        if (jobs.isEmpty()) return@withContext RemoteText("No background services or jobs on ${target.alias} for this project.")
        RemoteText("On ${target.alias} (this project runs its commands there):\n" + jobs.joinToString("\n") { (name, state) -> "$name ($state)" })
    }

    // ---------------------------------------------------------------------------------------------
    // Jobs
    // ---------------------------------------------------------------------------------------------

    /**
     * Follows job [id] until it ends or [deadline], reading new log bytes into [log] on each check. A
     * dropped connection (the phone slept, the network changed) is retried until the deadline: the job
     * itself keeps running on the machine. A changed host key is never retried.
     */
    private suspend fun follow(target: RemoteTarget, id: String, deadline: Long, log: LogSink): RemoteJobs.Probe {
        var lastError: Exception? = null
        while (true) {
            try {
                return ssh.withClient(target.hostId) { client, host ->
                    val fs = sftp(client, host)
                    while (true) {
                        val p = probe(client, host, id)
                        log.pull(fs, logPath(p.home, id))
                        if (!p.running || System.currentTimeMillis() >= deadline) return@withClient p
                        delay(pollMs)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.message.orEmpty().startsWith("SECURITY")) throw e
                lastError = e
                if (System.currentTimeMillis() + reconnectMs >= deadline) {
                    throw ToolException(
                        "Lost the connection to ${target.alias} while the command ran (${e.message}). It may still be running there as job $id; " +
                            "call run_command again with the same command to pick it up.",
                    )
                }
                delay(reconnectMs)
            }
        }
    }

    private suspend fun probe(client: SshClient, host: SshHost, id: String): RemoteJobs.Probe {
        val out = ssh.execOn(client, host, RemoteJobs.probe(id), 30_000)
        if (!out.startsWith("Exit code 0.")) throw ToolException("Couldn't check the job on ${host.alias}: ${out.substringAfter('\n')}")
        return RemoteJobs.Probe.parse(out.substringAfter('\n')) ?: throw ToolException("Unexpected answer from ${host.alias}: ${out.take(200)}")
    }

    private suspend fun homeOf(client: SshClient, host: SshHost): String {
        val out = ssh.execOn(client, host, RemoteJobs.portable("printf '%s\\n' \"\$HOME\""), 30_000)
        val home = out.substringAfter('\n').trim()
        if (!out.startsWith("Exit code 0.") || !home.startsWith("/")) throw ToolException("Couldn't find the home folder on ${host.alias}.")
        return home
    }

    /** The last [maxChars] of job [id]'s log. */
    private suspend fun tail(target: RemoteTarget, id: String, home: String, maxChars: Int): String =
        ssh.withClient(target.hostId) { client, host ->
            val fs = sftp(client, host)
            val path = logPath(home, id)
            val size = fs.stat(path)?.size ?: return@withClient ""
            val from = (size - maxChars * 2L).coerceAtLeast(0)
            String(fs.readFrom(path, from, (size - from).toInt()), Charsets.UTF_8).takeLast(maxChars)
        }.let { SecretScrubber(vaultValues()).scrub(it) }

    /** This project's jobs on the machine: (name the agent uses, state). */
    private suspend fun listJobs(target: RemoteTarget): List<Pair<String, String>> {
        val out = ssh.exec(target.hostId, RemoteJobs.list(), 30_000)
        val prefix = projectPrefix(target)
        return out.substringAfter('\n').lines().mapNotNull { line ->
            val id = line.substringBefore(':').trim()
            if (!id.startsWith("$prefix-")) return@mapNotNull null
            val rest = id.removePrefix("$prefix-")
            val name = if (rest.startsWith("s-")) rest.removePrefix("s-") else id
            name to line.substringAfter(':').trim()
        }
    }

    /** The job behind a service name, or a run_command job id the agent was given. */
    private fun resolveJob(target: RemoteTarget, name: String): String =
        if (name.startsWith(projectPrefix(target) + "-") && RemoteJobs.isValidId(name)) name else serviceId(target, name)

    /**
     * The script a job runs: the login profile (so the user's PATH and tools are there, as in their own
     * terminal), then the command in its folder.
     */
    private fun script(target: RemoteTarget, cwd: String, command: String): String {
        val dir = remoteCwd(target.folder, cwd)
        // A folder under ~ is given as "$HOME"/'rest', since a quoted ~ isn't expanded.
        val cdTo = if (dir == "~" || dir.startsWith("~/")) "\"\$HOME\"" + dir.removePrefix("~").let { if (it.isEmpty()) "" else RemoteJobs.quote(it) } else RemoteJobs.quote(dir)
        return "[ -f \"\$HOME/.profile\" ] && . \"\$HOME/.profile\" </dev/null >/dev/null 2>&1\n" +
            "cd $cdTo || { echo \"Locus: the folder $dir doesn't exist on ${target.alias}.\"; exit 97; }\n" +
            command
    }

    private fun logPath(home: String, id: String) = home.trimEnd('/') + "/.locus-jobs/$id.log"

    // ---------------------------------------------------------------------------------------------
    // Sync
    // ---------------------------------------------------------------------------------------------

    private suspend fun syncUp(fs: RemoteFs, target: RemoteTarget): SyncReport {
        val sync = syncer(fs, target.localFolder, target.folder)
        val (state, report) = sync.up(state(target))
        saveState(target, state)
        return report
    }

    private suspend fun syncDown(fs: RemoteFs, target: RemoteTarget, ctx: ToolCallContext?, toolName: String): SyncReport {
        val sync = syncer(fs, target.localFolder, target.folder)
        val root = localRoot(target.localFolder)
        val touched = mutableListOf<Triple<File, Boolean, String?>>()
        val (state, report) = sync.down(state(target)) { path, downloaded, kind ->
            val file = File(root, path)
            val existed = file.exists()
            val trashId = when {
                kind == PulledChange.Kind.DELETED -> workspace.trash(file, TrashEntry.Reason.DELETED).id
                existed -> workspace.backup(file, TrashEntry.Reason.OVERWRITTEN).id
                else -> null
            }
            if (downloaded != null) {
                file.parentFile?.mkdirs()
                downloaded.copyTo(file, overwrite = true)
            }
            touched += Triple(file, existed, trashId)
        }
        saveState(target, state)
        // Changes made on the machine show on the turn's changes card, with undo, like the file tools' own.
        val owner = ctx?.changeOwner
        if (owner != null) {
            touched.forEach { (file, existed, trashId) ->
                try {
                    changes.record(FileChange(owner, ctx.callId, toolName, workspace.stored(file), existed, trashId))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        return report
    }

    /** Both ways, for the first link (no chat, so nothing is tracked). */
    private suspend fun syncBoth(fs: RemoteFs, target: RemoteTarget, owner: ToolCallContext?, toolName: String, onProgress: (Int, Int) -> Unit): SyncReport {
        val sync = syncer(fs, target.localFolder, target.folder)
        val (afterUp, up) = sync.up(SyncState(target.hostId, target.folder), onProgress = onProgress)
        saveState(target, afterUp)
        val down = syncDown(fs, target, owner, toolName)
        return up.copy(pulled = down.pulled, conflicts = down.conflicts)
    }

    private suspend fun syncer(fs: RemoteFs, projectFolder: String, remoteFolder: String): RemoteSync {
        val root = localRoot(projectFolder)
        val ignore = File(root, SyncRules.IGNORE_FILE).takeIf { it.isFile }?.readText()
        return RemoteSync(fs, root, remoteFolder, SyncRules(ignore), vaultValues(), File(tmpRoot, "pull"))
    }

    private fun localRoot(projectFolder: String): File {
        val clean = dev.locus.core.model.ProjectPolicy.cleanFolder(projectFolder) ?: throw ToolException("This project's folder isn't valid.")
        return File(workspace.base, clean).apply { mkdirs() }
    }

    private fun stateFile(projectId: String) = File(stateDir, "${hash(projectId).take(24)}.json")

    /** The last agreed state, or a fresh one when the project was linked to another machine or folder since. */
    private fun state(target: RemoteTarget): SyncState {
        val saved = SyncState.read(stateFile(target.projectId))
        return if (saved != null && saved.hostId == target.hostId && saved.folder == target.folder) saved else SyncState(target.hostId, target.folder)
    }

    private fun saveState(target: RemoteTarget, state: SyncState) = SyncState.write(stateFile(target.projectId), state.copy(hostId = target.hostId, folder = target.folder))

    private suspend fun sftp(client: SshClient, host: SshHost): RemoteFs = when (val r = client.openSftp()) {
        is SftpResult.Success -> SftpFs(r.value)
        else -> throw ToolException("${host.alias} doesn't offer SFTP, which Locus needs to copy the project's files ($r).")
    }

    private suspend fun vaultValues(): Collection<String> = runCatching { vault.valuesForScrubbing() }.getOrDefault(emptyList())

    private suspend fun aliasOf(hostId: String) = settings.current().sshHosts.firstOrNull { it.id == hostId }?.alias ?: hostId

    private suspend fun <T> lock(projectId: String, block: suspend () -> T): T = locks.getOrPut(projectId) { Mutex() }.withLock { block() }

    private suspend fun <T> busy(projectId: String, what: RemoteActivity, block: suspend () -> T): T {
        val before = _activity.value[projectId]
        _activity.update { it + (projectId to what) }
        try {
            return block()
        } finally {
            _activity.update { if (before == null) it - projectId else it + (projectId to before) }
        }
    }

    private fun upNote(r: SyncReport): String = buildString {
        append(if (r.uploaded == 0 && r.removedRemote == 0) "Nothing new to copy there." else "Copied ${files(r.uploaded)} there" + (if (r.removedRemote > 0) ", deleted ${r.removedRemote} there" else "") + ".")
        if (r.skippedSecrets > 0) append(" ${files(r.skippedSecrets)} with secrets never leave the phone.")
    }

    private fun downNote(r: SyncReport, target: RemoteTarget): String = buildString {
        if (r.pulled.isEmpty()) {
            append("No files changed there.")
        } else {
            val shown = r.pulled.take(8).joinToString { "${it.path} (${it.kind.name.lowercase()})" }
            append("Brought back ${files(r.pulled.size)}: $shown" + (if (r.pulled.size > 8) " and ${r.pulled.size - 8} more" else "") + ".")
        }
        if (r.conflicts.isNotEmpty()) append(" Changed on both sides, ${target.alias}'s version kept (the phone's is in the workspace trash): ${r.conflicts.joinToString()}.")
        append(" Build outputs and dependency folders (build/, node_modules/, …) stay on ${target.alias}; copy one back with sftp_download.")
    }

    private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

    private fun untrusted(text: String) = UntrustedContent.wrap(text.ifBlank { "(no output yet)" }, UntrustedContent.note("command output"))

    /** The log of one followed job: its start and end in memory, all of it (up to a cap) in [spool]. */
    private class LogSink(private val spool: File) {
        private var offset = 0L
        private val head = StringBuilder()
        private val tail = StringBuilder()
        private val out = spool.outputStream().buffered()
        var truncated = false
            private set
        var capped = false
            private set

        suspend fun pull(fs: RemoteFs, path: String) {
            while (true) {
                val bytes = fs.readFrom(path, offset, 256 * 1024)
                if (bytes.isEmpty()) return
                offset += bytes.size
                if (offset <= MAX_SAVED) out.write(bytes) else capped = true
                append(String(bytes, Charsets.UTF_8))
            }
        }

        private fun append(s: String) {
            if (head.length < KEEP / 2) {
                val take = minOf(s.length, KEEP / 2 - head.length)
                head.append(s, 0, take)
                if (take < s.length) appendTail(s.substring(take))
            } else {
                appendTail(s)
            }
        }

        private fun appendTail(s: String) {
            tail.append(s)
            if (tail.length > KEEP / 2) {
                truncated = true
                tail.delete(0, tail.length - KEEP / 2)
            }
        }

        fun text(): String = if (truncated) "$head\n…[output truncated]…\n$tail" else "$head$tail"

        fun spooled(): String {
            out.flush()
            return spool.readText()
        }

        fun close() {
            runCatching { out.close() }
            spool.delete()
        }
    }

    companion object {
        private const val SERVICE_SETTLE_MS = 4_000L
        private const val LOG_WINDOW = 512 * 1024
        private const val KEEP = 30_000
        private const val MAX_SAVED = 8L * 1024 * 1024
        private const val SECRET_SCAN_MAX = 2L * 1024 * 1024

        private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Every job id of a project starts with this, so one machine can serve several projects. */
        internal fun projectPrefix(target: RemoteTarget) = "p" + hash(target.projectId).take(6)

        internal fun jobId(target: RemoteTarget, kind: String, suffix: String) = "${projectPrefix(target)}-$kind-$suffix"

        internal fun serviceId(target: RemoteTarget, name: String): String {
            val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).ifEmpty { "service" }
            return jobId(target, "s", slug)
        }

        /** A `/workspace` path (as in Linux on the phone) on the machine: under the synced folder. */
        internal fun remoteCwd(folder: String, cwd: String): String {
            val c = cwd.trim().ifEmpty { "/workspace" }
            return when {
                c == "/workspace" -> folder
                c.startsWith("/workspace/") -> folder + "/" + c.removePrefix("/workspace/").trimEnd('/')
                c.startsWith("/") || c.startsWith("~") -> c
                else -> "$folder/${c.trimEnd('/')}"
            }
        }

        /** The last [count] lines of [log] (those matching [filter]), as linux_service_log shows them. */
        internal fun lastLines(log: String, count: Int, filter: Regex?): String {
            if (log.isBlank()) return "(no output yet)"
            val lines = log.lines().dropLastWhile { it.isEmpty() }.let { all -> if (filter == null) all else all.filter { filter.containsMatchIn(it) } }
            if (lines.isEmpty()) return "(no lines match the filter)"
            val picked = lines.takeLast(count)
            val skipped = lines.size - picked.size
            return (if (skipped > 0) "[$skipped earlier lines not shown]\n" else "") + picked.joinToString("\n").takeLast(30_000)
        }
    }
}
