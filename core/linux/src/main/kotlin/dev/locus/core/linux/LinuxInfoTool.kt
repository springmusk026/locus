package dev.locus.core.linux

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.str
import dev.locus.core.agent.tools.RemoteBackend
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * `linux_info`: the health screen's report, compact, for the agent (docs/design/linux-health.md). It
 * only reads: apk's database, sizes, memory, services and one `echo` through proot. It never reaches
 * the network, so it can't say what's upgradable; `apk version` in run_command does that.
 */
internal class LinuxInfo(
    private val status: () -> LinuxStatus,
    private val report: suspend () -> LinuxReport?,
    private val remote: RemoteBackend? = null,
) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        NAME,
        "Check Locus's Linux system (Alpine on the phone): whether it works and what's wrong, the Alpine version, storage and memory left, " +
            "running services, and installed packages. Use it before installing software (to skip what's already there, or find space is short) " +
            "and when commands fail in ways that look like the environment's fault. Read-only and offline.",
        ObjectSchema()
            .string("check", "Comma-separated package names to look up, e.g. \"python3,git,nodejs\": each is reported installed (with version) or not.", required = false)
            .string("search", "Show installed packages whose name or description contains this text.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Check Linux"

    override suspend fun execute(args: JsonObject): ToolResult {
        val routed = remote?.let { runCatching { it.target() }.getOrNull() }
        val note = routed?.let { "\n(Note: this project's commands run on ${it.alias}, not in this Linux; this describes the phone's Linux only.)" }.orEmpty()
        linuxNotReadyMessage(status())?.let { return ToolResult.error(it.replace("this command didn't run", "there's nothing to check") + note) }
        val r = report() ?: return ToolResult.error("Linux's state can't be read right now (it may have just been removed or be reinstalling).$note")
        val text = buildString {
            append(LinuxHealth.summary(r))
            val pkgs = r.packages
            val check = args.str("check")?.split(',', ' ')?.filter { it.isNotBlank() }.orEmpty()
            if (check.isNotEmpty()) {
                appendLine().appendLine()
                append(if (pkgs == null) "Can't read the package database." else LinuxHealth.packageLines(pkgs, check))
            }
            args.str("search")?.takeIf { it.isNotBlank() }?.let { q ->
                appendLine().appendLine()
                if (pkgs == null) {
                    append("Can't read the package database.")
                } else {
                    val hits = LinuxInventory.search(pkgs, q)
                    append(if (hits.isEmpty()) "No installed package matches “$q”." else "Installed matching “$q” (${hits.size}):\n" + hits.take(MAX_LISTED).joinToString("\n") { "${it.name} ${it.version}" })
                    if (hits.size > MAX_LISTED) append("\n… and ${hits.size - MAX_LISTED} more; search more narrowly.")
                }
            }
            append(note)
        }
        return ToolResult(text)
    }

    companion object {
        const val NAME = "linux_info"
        private const val MAX_LISTED = 40
    }
}
