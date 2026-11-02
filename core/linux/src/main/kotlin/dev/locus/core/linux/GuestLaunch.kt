package dev.locus.core.linux

/**
 * Every way Locus starts a program in Linux, built from one proot command line, so the agent's
 * commands, local MCP servers and the interactive terminal get exactly the same isolation flags
 * ([ProotArgs.build]: `/proc` hiding and the binder shadows from docs/design/linux-isolation.md).
 * Kept free of Android so the parity can be unit tested.
 */
internal class GuestLaunch(
    private val paths: ProotArgs.Paths,
    private val fakeProc: List<String>,
    private val hiddenPids: Collection<Int>,
    private val hiddenDevices: Collection<String>,
) {
    /** proot's options for a program starting in [cwd] (a path inside Linux). */
    fun proot(cwd: String): List<String> = ProotArgs.build(paths, cwd, fakeProc, hiddenPids, hiddenDevices)

    /** `/bin/sh -c` [command]: run_command, services, git tools and local MCP servers. */
    fun command(command: String, cwd: String): List<String> = proot(cwd) + listOf("/bin/sh", "-c", command)

    /**
     * The terminal's login shell in [cwd]: bash when the user installed it (`apk add bash`), otherwise
     * BusyBox's sh. `-l` reads /etc/profile, so PS1 and the user's ~/.profile apply.
     */
    fun loginShell(cwd: String, hasBash: Boolean): List<String> =
        proot(cwd) + if (hasBash) listOf("/bin/bash", "-l") else listOf("/bin/sh", "-l")

    companion object {
        /**
         * The terminal's environment: the agent's (proot's loader and library paths, HOME, PATH, TZ…)
         * with a terminal type that full-screen programs understand. Nothing is added that agent
         * commands don't get, apart from the two terminal variables.
         */
        fun terminalEnvironment(base: Map<String, String>): Map<String, String> =
            base + mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor")
    }
}

/** How to start the terminal's shell: [argv] with argv[0] = [executable], in host directory [hostCwd]. */
data class TerminalLaunch(
    val executable: String,
    val argv: List<String>,
    val environment: Map<String, String>,
    val hostCwd: String,
)
