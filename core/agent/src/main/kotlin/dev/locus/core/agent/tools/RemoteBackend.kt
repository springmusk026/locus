package dev.locus.core.agent.tools

import java.io.File

/**
 * Where a project's commands run when the user linked it to one of their SSH machines
 * (docs/design/remote-execution.md). run_command, the Linux service tools and verification ask it
 * first; with no target they run in Linux on the phone as before. It's a routing decision inside the
 * existing tools: the tool list and the system prompt never change, and every routed result says
 * where it ran.
 */
interface RemoteBackend {
    /**
     * The machine the current call's project runs on (read from [dev.locus.core.agent.ChatScope] and the
     * project's setting at call time), or null to run on the phone. Throws a
     * [dev.locus.core.agent.ToolException] when the project is linked to a machine that no longer exists.
     */
    suspend fun target(): RemoteTarget?

    /**
     * Syncs the project up, runs [command] in [cwd] (a `/workspace` path, mapped to the remote folder) as a
     * detached job, follows its log until it ends or [timeoutMs] passes, then syncs back. If the same
     * command is still running from an earlier call (the phone slept, the wait ran out, Locus restarted),
     * it attaches to that job instead of starting it again.
     */
    suspend fun run(target: RemoteTarget, command: String, cwd: String, timeoutMs: Long, saveFullOutputTo: File?): RemoteRun

    /** Starts [command] as a long-running service on the machine, after syncing up. Returns the agent's text. */
    suspend fun startService(target: RemoteTarget, name: String, command: String, cwd: String): RemoteText

    /** A service's (or a long run_command's) output, the last [lines] lines matching [filter]; syncs back once it has finished. */
    suspend fun serviceLog(target: RemoteTarget, name: String, lines: Int, filter: Regex?): RemoteText

    suspend fun stopService(target: RemoteTarget, name: String): RemoteText

    suspend fun listServices(target: RemoteTarget): RemoteText
}

/** A project's machine: its [alias] (what results and the chat show), the synced [folder] there, and the project's [localFolder] (workspace-relative). */
data class RemoteTarget(val projectId: String, val hostId: String, val alias: String, val folder: String, val localFolder: String)

/** Text for the agent and whether it reports a failure. */
data class RemoteText(val text: String, val isError: Boolean = false)

/**
 * One remote run_command. [exitCode] is null while the job is [stillRunning] (the wait ran out; it keeps
 * going on the machine as [jobId]). [output] is the start and end of the log, [savedTo] the whole of it
 * when it was longer. [syncNote] says what was copied each way.
 */
data class RemoteRun(
    val exitCode: Int?,
    val output: String,
    val stillRunning: Boolean,
    val jobId: String,
    val durationMs: Long?,
    val syncNote: String,
    val attached: Boolean = false,
    val savedTo: File? = null,
    val savedBytes: Long = 0,
    val savedCapped: Boolean = false,
)
