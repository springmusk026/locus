package dev.locus.core.linux

import java.util.concurrent.ConcurrentHashMap

/**
 * Secret values handed to programs running in Linux (a local MCP server's environment), kept so
 * command output shown to the agent can be scrubbed of them. A process in Linux can read another's
 * environment through /proc, so `run_command` output passes through [scrub]. This is a safety net
 * for accidental echoes (`env`, `cat /proc/<pid>/environ`), not a sandbox: a command that encodes
 * the value first gets around it.
 */
class OutputRedactor {
    private val byOwner = ConcurrentHashMap<String, Set<String>>()

    /** Values shorter than [MIN_LENGTH] aren't scrubbed: they'd mangle ordinary output. */
    fun register(owner: String, values: Collection<String>) {
        val kept = values.filter { it.length >= MIN_LENGTH }.toSet()
        if (kept.isEmpty()) byOwner.remove(owner) else byOwner[owner] = kept
    }

    fun unregister(owner: String) {
        byOwner.remove(owner)
    }

    fun scrub(text: String): String {
        val values = byOwner.values.flatten()
        if (values.isEmpty()) return text
        // Longest first, so a value containing another is replaced whole.
        return values.distinct().sortedByDescending { it.length }.fold(text) { acc, v -> acc.replace(v, "[secret]") }
    }

    companion object {
        const val MIN_LENGTH = 6
    }
}
