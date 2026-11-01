package dev.locus.core.linux

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.LINUX_INSTALL_PATH
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject

fun linuxTools(linux: LinuxEnvironment): List<Tool> = listOf(RunCommand(linux), ServiceStart(linux), ServiceStop(linux), ServiceList(linux))

/**
 * Why a Linux tool can't run yet, worded for the agent, or null when Linux is ready. The agent must
 * not install Linux itself: the download is third-party software from alpinelinux.org that
 * F-Droid hasn't checked, so only the user can opt in.
 */
internal fun linuxNotReadyMessage(status: LinuxStatus): String? = when (status) {
    LinuxStatus.Ready -> null
    is LinuxStatus.Unavailable -> "Linux isn't available on this phone: ${status.reason} Use other tools, or tell the user this task needs Linux."
    is LinuxStatus.Installing -> "Linux is being installed right now (${status.step}). Tell the user, and try again once they say it's finished."
    LinuxStatus.NotInstalled, is LinuxStatus.Failed ->
        "Linux isn't installed, so this command didn't run. Locus never installs it on its own: installing downloads Alpine Linux, " +
            "third-party programs from alpinelinux.org that F-Droid hasn't checked, so the user has to choose it. " +
            "Ask the user to install it from $LINUX_INSTALL_PATH (\"Download and install Linux\"), " +
            "then try again once they say it's installed. Don't retry this tool before that."
}

private class RunCommand(private val linux: LinuxEnvironment) : Tool {
    override val risk = ToolRisk.EXECUTE
    override val spec = ToolSpec(
        "run_command",
        "Run a shell command in Locus's Linux system (Alpine Linux, runs on the phone, no root). " +
            "The workspace is mounted at /workspace and is the default directory, so files you create there are the same ones the file tools and the user see. " +
            "Install software with `apk add <package>` (e.g. python3, git, curl, nodejs, ffmpeg). Commands are non-interactive; output from both streams is returned. " +
            "For servers and anything long-running (e.g. a dev server), use linux_service_start instead.",
        ObjectSchema()
            .string("command", "Shell command for /bin/sh -c.")
            .string("cwd", "Working directory inside Linux. Defaults to /workspace.", required = false)
            .integer("timeout_seconds", "Stop the command after this long. Defaults to 120, max 1800.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Run: ${args.str("command")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        linuxNotReadyMessage(linux.status.value)?.let { return ToolResult.error(it) }
        val timeout = (args.int("timeout_seconds") ?: 120).coerceIn(1, 1800)
        val result = linux.run(
            command = args.str("command").orEmpty(),
            cwd = args.str("cwd") ?: "/workspace",
            timeoutMs = timeout * 1000L,
        )
        val status = when {
            result.timedOut -> "Stopped after ${timeout}s (timeout)."
            result.exitCode == 0 -> "Exit code 0."
            else -> "Exit code ${result.exitCode}."
        }
        val body = result.output.ifBlank { "(no output)" }
        return ToolResult("$status\n$body", isError = result.timedOut || result.exitCode != 0)
    }
}

private class ServiceStart(private val linux: LinuxEnvironment) : Tool {
    override val risk = ToolRisk.EXECUTE
    override val spec = ToolSpec(
        "linux_service_start",
        "Start a long-running command in Linux in the background, e.g. a web server (`npx serve -l 3000`, `python3 -m http.server 8000`, `npm run dev`). " +
            "It keeps running until stopped or until Locus closes. Servers listen on the phone itself, so open them with browser_open at http://localhost:PORT to check them and let the user see them. " +
            "Starting a service with an existing name restarts it. Returns the first output after a few seconds.",
        ObjectSchema()
            .string("name", "Short name, e.g. \"web\".")
            .string("command", "Shell command.")
            .string("cwd", "Working directory. Defaults to /workspace.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Start “${args.str("name")}”: ${args.str("command")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        linuxNotReadyMessage(linux.status.value)?.let { return ToolResult.error(it) }
        val name = args.str("name").orEmpty().ifBlank { "service" }
        val service = linux.startService(name, args.str("command").orEmpty(), args.str("cwd") ?: "/workspace")
        kotlinx.coroutines.delay(4_000)
        val log = linux.serviceLog(name).orEmpty().ifBlank { "(no output yet)" }
        return if (linux.isAlive(service)) ToolResult("Service “$name” is running.\n$log")
        else ToolResult.error("Service “$name” exited right away.\n$log")
    }
}

private class ServiceStop(private val linux: LinuxEnvironment) : Tool {
    override val risk = ToolRisk.EXECUTE
    override val defaultPolicy = dev.locus.core.model.ToolPolicy.ALLOW
    override val spec = ToolSpec("linux_service_stop", "Stop a background service started with linux_service_start.", ObjectSchema().string("name", "Service name.").build())
    override fun describe(args: JsonObject) = "Stop “${args.str("name")}”"
    override suspend fun execute(args: JsonObject) =
        if (linux.stopService(args.str("name").orEmpty())) ToolResult("Stopped.") else ToolResult.error("No service “${args.str("name")}”.")
}

/** One line per service for the agent: running, exited, or ended by a restart of Locus (with what to do). */
internal fun describeService(s: LinuxEnvironment.ServiceStatus): String = when {
    s.running -> "${s.name} (running): ${s.command}"
    s.lostOnRestart -> "${s.name} (stopped: Locus restarted since it was started, which ended it; start it again with linux_service_start, " +
        "or linux_service_stop to forget it): ${s.command}"
    else -> "${s.name} (stopped): ${s.command}"
}

private class ServiceList(private val linux: LinuxEnvironment) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "linux_service_list",
        "List background services and the end of each one's output (to check a server's logs or errors).",
        ObjectSchema().build(),
    )
    override fun describe(args: JsonObject) = "List services"
    override suspend fun execute(args: JsonObject): ToolResult {
        val services = linux.serviceStatuses()
        if (services.isEmpty()) return ToolResult("No background services.")
        return ToolResult(services.joinToString("\n\n") { s -> describeService(s) + "\n" + linux.serviceLog(s.name, 1_500).orEmpty().ifBlank { "(no output)" } })
    }
}
