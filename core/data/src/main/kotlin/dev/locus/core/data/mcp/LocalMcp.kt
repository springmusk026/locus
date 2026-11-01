package dev.locus.core.data.mcp

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxNotInstalledException
import dev.locus.core.linux.OutputRedactor
import dev.locus.core.model.LINUX_INSTALL_PATH
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A started program's standard streams, as the local MCP supervisor sees it. */
interface StdioProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    val isAlive: Boolean

    /** Null while it runs. */
    val exitCode: Int?

    /** Closes stdin, waits briefly, then forces it to stop. */
    suspend fun stop()
}

/** Starts programs for local MCP servers; inside Linux in the app, a fake in tests. */
fun interface StdioLauncher {
    suspend fun launch(launch: LocalLaunch): StdioProcess
}

/**
 * What to run for a local MCP server: one shell command line and the environment to add. [env]
 * holds secret values, so [toString] shows only the variable names.
 */
class LocalLaunch(val command: String, val env: Map<String, String>) {
    override fun toString() = "LocalLaunch(command=$command, env=${env.keys.sorted()})"

    companion object {
        /** [Connector.Local.command] as typed, then each arg as a single quoted shell word. */
        fun commandLine(local: Connector.Local): String =
            (listOf(local.command.trim()) + local.args.map(::shellQuote)).joinToString(" ")

        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        /**
         * The launch for [connector], with each env var's value read from the vault. A missing
         * secret is refused by name (never by value) before anything starts.
         */
        suspend fun build(connector: Connector, secrets: SecretResolver): LocalLaunch {
            val local = connector.local ?: throw ToolException("“${connector.id}” isn't a local MCP server.")
            val missing = mutableListOf<String>()
            val env = local.env.associate { e ->
                val value = secrets.resolve(e.secret)
                if (value.isNullOrEmpty()) missing += e.secret
                e.name to value.orEmpty()
            }
            if (missing.isNotEmpty()) {
                throw ToolException(
                    "“${connector.id}” needs ${missing.joinToString { "“$it”" }} before it can start. Ask the user to add ${if (missing.size > 1) "them" else "it"} in Library → Connectors.",
                )
            }
            return LocalLaunch(commandLine(local), env)
        }
    }
}

/** How a local MCP server is doing, for Library → Connectors. */
sealed interface LocalMcpHealth {
    /** Not running; [reason] says why when it was running before (idle, stopped by the user). */
    data class Stopped(val reason: String? = null) : LocalMcpHealth
    data object Starting : LocalMcpHealth
    data class Running(val since: Long) : LocalMcpHealth
    /** It couldn't start or it crashed; [stderrTail] is the end of what it printed to stderr. */
    data class Failed(val reason: String, val stderrTail: String) : LocalMcpHealth
}

/**
 * Runs local MCP servers (stdio) on demand and keeps them alive while they're used.
 *
 * - A server starts when a chat first lists or calls its tools ([use]); a failed start is retried
 *   once, then reported as [LocalMcpHealth.Failed] with the end of its stderr.
 * - It stops after [idleTimeoutMs] with no calls in flight, when its connector is removed or
 *   changed ([stop]), when Linux is removed, and with Locus itself (its process tree ends then).
 * - If it dies during a call, the error says so with its stderr. Only calls marked idempotent
 *   (listing tools, read-only tools) are retried, once, on a fresh process: re-running a write the
 *   server may already have done could do it twice.
 * - Its secrets are registered with [redactor] so they're scrubbed from Linux command output.
 *
 * Health lives in memory: after Locus restarts every server shows as stopped until it's used again,
 * which is the truth (the processes ended with the app).
 */
class LocalMcpServers(
    private val launcher: StdioLauncher,
    private val secrets: SecretResolver,
    private val scope: CoroutineScope,
    private val redactor: OutputRedactor? = null,
    private val idleTimeoutMs: Long = 10 * 60_000L,
    private val idleCheckMs: Long = 30_000L,
    /** First runs of `npx -y …` download the package, which can take a while on a phone. */
    private val startTimeout: Duration = 3.minutes,
    private val requestTimeout: Duration = 5.minutes,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Live(
        val id: String,
        val config: Connector.Local,
        val process: StdioProcess,
        val client: Client,
        val stderr: StderrTail,
    ) {
        val inUse = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var lastUsed = 0L
        @Volatile var stopping = false
        @Volatile var discarded = false
        var watcher: Job? = null
    }

    private class StartFailure(val reason: String, val stderrTail: String) : Exception(reason)

    private val live = ConcurrentHashMap<String, Live>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _health = MutableStateFlow<Map<String, LocalMcpHealth>>(emptyMap())
    val health: StateFlow<Map<String, LocalMcpHealth>> = _health.asStateFlow()
    private var reaper: Job? = null

    fun health(id: String): LocalMcpHealth = _health.value[id] ?: LocalMcpHealth.Stopped()

    /**
     * Runs [block] with a connected client for the local server [connector], starting it if needed.
     * Throws [ToolException] with a readable reason (and the server's stderr) when it can't.
     */
    suspend fun <T> use(connector: Connector, idempotent: Boolean = false, block: suspend (Client) -> T): T {
        var retried = false
        while (true) {
            val server = acquire(connector)
            try {
                return block(server.client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!diedAfter(server)) throw e
                val message = crashed(connector.id, server)
                if (idempotent && !retried) {
                    retried = true
                    continue
                }
                throw ToolException(message + if (idempotent) "" else " The call may or may not have taken effect; it wasn't repeated.")
            } finally {
                release(server)
            }
        }
    }

    /** Stops [id]'s server if it runs. */
    suspend fun stop(id: String, reason: String = "Stopped") {
        lockFor(id).withLock {
            live.remove(id)?.let { discard(it) }
            if (_health.value[id] != null) setHealth(id, LocalMcpHealth.Stopped(reason))
        }
    }

    /** Starts [connector]'s server now (if it isn't running), e.g. from Library → Connectors. */
    suspend fun start(connector: Connector) {
        val server = acquire(connector)
        release(server)
    }

    private fun lockFor(id: String) = locks.getOrPut(id) { Mutex() }

    private suspend fun acquire(connector: Connector): Live = lockFor(connector.id).withLock {
        val id = connector.id
        val local = connector.local ?: throw ToolException("“$id” isn't a local MCP server.")
        live[id]?.let { s ->
            if (s.process.isAlive && s.config == local && !s.stopping) {
                s.inUse.incrementAndGet()
                return@withLock s
            }
            live.remove(id)
            // Died while nobody was using it: record that before starting a fresh one.
            if (!s.process.isAlive && !s.stopping) markFailed(id, s)
            discard(s)
        }
        val launch = LocalLaunch.build(connector, secrets)
        setHealth(id, LocalMcpHealth.Starting)
        val server = try {
            try {
                startOnce(id, local, launch)
            } catch (e: StartFailure) {
                // One retry: a first `npx -y` can fail on a flaky download and work the second time.
                startOnce(id, local, launch)
            }
        } catch (e: StartFailure) {
            setHealth(id, LocalMcpHealth.Failed(e.reason, e.stderrTail))
            throw ToolException("Local MCP server “$id” didn't start (tried twice): ${e.reason}." + tailNote(e.stderrTail))
        } catch (e: LinuxNotInstalledException) {
            val reason = e.unavailableReason ?: "Linux isn't installed"
            setHealth(id, LocalMcpHealth.Failed(reason, ""))
            throw ToolException(
                "“$id” is a local MCP server that runs in Locus's Linux system, and $reason. " +
                    "Ask the user to install Linux ($LINUX_INSTALL_PATH), then try again.",
            )
        } catch (e: CancellationException) {
            setHealth(id, LocalMcpHealth.Stopped("Start cancelled"))
            throw e
        }
        server.inUse.incrementAndGet()
        live[id] = server
        setHealth(id, LocalMcpHealth.Running(clock()))
        server.watcher = scope.launch { watch(id, server) }
        ensureReaper()
        server
    }

    private suspend fun startOnce(id: String, local: Connector.Local, launch: LocalLaunch): Live {
        val process = try {
            launcher.launch(launch)
        } catch (e: LinuxNotInstalledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StartFailure("couldn't launch: ${scrub(e.message ?: e.toString(), launch)}", "")
        }
        redactor?.register(id, launch.env.values)
        val stderr = StderrTail(process.stderr, scope) { scrub(it, launch) }
        val transport = StdioClientTransport(process.stdout.asSource().buffered(), process.stdin.asSink().buffered())
        val client = Client(Implementation(name = "locus", version = "0.1.0"), ClientOptions().apply { timeout = requestTimeout })
        try {
            withTimeout(startTimeout) { client.connect(transport) }
        } catch (e: Throwable) {
            val exited = withTimeoutOrNull(500) { while (process.isAlive) delay(25); true } == true
            val tail = withContext(NonCancellable) {
                runCatching { client.close() }
                runCatching { process.stop() }
                stderr.drain()
                stderr.close()
                redactor?.unregister(id)
                stderr.text()
            }
            if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            val reason = when {
                exited -> "it exited with code ${process.exitCode}"
                e is kotlinx.coroutines.TimeoutCancellationException -> "it didn't answer the MCP handshake within ${startTimeout.inWholeSeconds}s"
                else -> "the MCP handshake failed (${scrub(e.message ?: e::class.simpleName.orEmpty(), launch)})"
            }
            throw StartFailure(reason, tail)
        }
        return Live(id, local, process, client, stderr)
    }

    private fun release(server: Live) {
        // Stamp first: the reaper must never see "not in use" with the previous timestamp.
        server.lastUsed = clock()
        server.inUse.decrementAndGet()
    }

    /** True if the server's process ended (a call failing because it crashed). */
    private suspend fun diedAfter(server: Live): Boolean {
        if (server.stopping) return false
        withTimeoutOrNull(500) { while (server.process.isAlive) delay(25) }
        return !server.process.isAlive
    }

    private suspend fun crashed(id: String, server: Live): String = lockFor(id).withLock {
        if (live[id] === server) live.remove(id)
        val failure = failure(server)
        // Unless a fresh server already replaced it (and has its own health).
        if (live[id] == null) setHealth(id, failure)
        discard(server)
        "Local MCP server “$id” stopped unexpectedly (${failure.reason.lowercase()})." + tailNote(failure.stderrTail)
    }

    private suspend fun markFailed(id: String, server: Live) = setHealth(id, failure(server))

    private suspend fun failure(server: Live): LocalMcpHealth.Failed {
        server.stderr.drain()
        return LocalMcpHealth.Failed("Exited with code ${server.process.exitCode}", server.stderr.text())
    }

    /** Stops [server] and frees what it holds. The caller has removed it from [live] and sets health. */
    private suspend fun discard(server: Live) {
        if (server.discarded) return
        server.discarded = true
        server.stopping = true
        server.watcher?.cancel()
        withContext(NonCancellable) {
            runCatching { server.client.close() }
            runCatching { server.process.stop() }
        }
        server.stderr.close()
        // A fresh server for the same connector may already run with its own registration.
        if (live[server.id] == null) redactor?.unregister(server.id)
    }

    /** Notices a server that dies between calls, so Library shows Failed without waiting for a call. */
    private suspend fun watch(id: String, server: Live) {
        while (server.process.isAlive) delay(500)
        if (server.stopping) return
        lockFor(id).withLock {
            if (live[id] !== server || server.stopping) return@withLock
            live.remove(id)
            markFailed(id, server)
            server.watcher = null // this coroutine: don't cancel ourselves mid-cleanup
            discard(server)
        }
    }

    /** One idle check for all servers, started with the first; it only wakes every [idleCheckMs]. */
    @Synchronized
    private fun ensureReaper() {
        if (reaper?.isActive == true) return
        reaper = scope.launch {
            while (isActive) {
                delay(idleCheckMs)
                val now = clock()
                live.entries.filter { (_, s) -> s.inUse.get() == 0 && now - s.lastUsed >= idleTimeoutMs }.forEach { (id, s) ->
                    lockFor(id).withLock {
                        // Re-check under the lock: a call may have started meanwhile.
                        if (live[id] === s && s.inUse.get() == 0 && clock() - s.lastUsed >= idleTimeoutMs) {
                            live.remove(id)
                            discard(s)
                            setHealth(id, LocalMcpHealth.Stopped("Stopped after ${idleTimeoutMs / 60_000} min idle"))
                        }
                    }
                }
            }
        }
    }

    private fun setHealth(id: String, health: LocalMcpHealth) = _health.update { it + (id to health) }

    private fun tailNote(tail: String) = if (tail.isBlank()) " It printed nothing to stderr." else "\nLast stderr output:\n${tail.takeLast(1_500)}"

    private fun scrub(text: String, launch: LocalLaunch): String =
        launch.env.values.filter { it.length >= 4 }.sortedByDescending { it.length }.fold(text) { acc, v -> acc.replace(v, "[secret]") }
}

/** Drains a process's stderr (so a chatty server never blocks on a full pipe) and keeps its end. */
internal class StderrTail(stream: InputStream, scope: CoroutineScope, private val max: Int = 4_000, private val scrub: (String) -> String) {
    private val buffer = StringBuilder()
    private val job = scope.launch(Dispatchers.IO) {
        val chunk = ByteArray(4_096)
        runCatching {
            stream.use { input ->
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    append(String(chunk, 0, n, Charsets.UTF_8))
                }
            }
        }
    }

    @Synchronized
    private fun append(s: String) {
        buffer.append(s)
        if (buffer.length > max) buffer.delete(0, buffer.length - max)
    }

    @Synchronized
    fun text(): String = scrub(buffer.toString()).trim()

    /** After the process exited: gives the reader a moment to take in its last words. */
    suspend fun drain() {
        withTimeoutOrNull(500) { job.join() }
    }

    fun close() {
        job.cancel()
    }
}

/** Starts local MCP servers inside Locus's Linux system (proot), which must already be installed. */
class LinuxStdioLauncher(private val linux: LinuxEnvironment) : StdioLauncher {
    override suspend fun launch(launch: LocalLaunch): StdioProcess {
        val process = linux.startPiped(launch.command, launch.env)
        return object : StdioProcess {
            override val stdin: OutputStream = process.outputStream
            override val stdout: InputStream = process.inputStream
            override val stderr: InputStream = process.errorStream
            override val isAlive: Boolean get() = runCatching { process.exitValue(); false }.getOrDefault(true)
            override val exitCode: Int? get() = runCatching { process.exitValue() }.getOrNull()
            override suspend fun stop() = linux.stopPiped(process)
        }
    }
}
