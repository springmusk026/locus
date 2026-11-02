package dev.locus.bench

import dev.locus.core.linux.LinuxEnvironment
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Linux tools' backend on the JVM. On a phone, run_command and the git tools run in Alpine under
 * proot; proot can't run here, so commands run in the host's `/bin/sh` instead (a documented deviation,
 * see docs/benchmarks/README.md):
 *
 * - `/workspace` (the working directory, and the path written in commands) maps to the trial's workspace;
 * - the environment is rebuilt from scratch: HOME and TMPDIR inside the trial folder, a fixed PATH, and
 *   nothing else from the harness's environment, so the provider key never reaches a command;
 * - `apk add` is a shim that succeeds for tools the host already has (python3, git, node) and fails
 *   otherwise, since nothing can be installed.
 *
 * It is not a security sandbox: a command can reach anything the user running the benchmark can. Run
 * real-model benchmarks in a throwaway VM or container (the GitHub workflow does, on an ephemeral runner).
 */
class HostShell(private val workspace: File, private val trialDir: File) : LinuxEnvironment.CommandHost {
    private val home = File(trialDir, "home").apply { mkdirs() }
    private val tmp = File(trialDir, "tmp").apply { mkdirs() }
    private val shims = File(trialDir, "shims").apply {
        mkdirs()
        File(this, "apk").apply {
            writeText(APK_SHIM)
            setExecutable(true)
        }
    }

    /** The environment every command gets. */
    val environment: Map<String, String> = mapOf(
        "HOME" to home.path,
        "TMPDIR" to tmp.path,
        "PATH" to (listOf(shims.path) + hostPath()).joinToString(File.pathSeparator),
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "TERM" to "dumb",
        "USER" to "root",
        "GIT_CONFIG_NOSYSTEM" to "1",
        "PYTHONDONTWRITEBYTECODE" to "1",
        "NO_COLOR" to "1",
    )

    override fun process(command: String, cwd: String): ProcessBuilder =
        ProcessBuilder("/bin/sh", "-c", mapPaths(command))
            .directory(resolveCwd(cwd))
            .apply { environment().apply { clear(); putAll(this@HostShell.environment) } }

    /** Runs [command] in [dir] (the workspace by default) with the same environment, for setup and graders. */
    fun run(command: String, dir: File = workspace, timeoutSeconds: Long = 120): Result {
        val process = ProcessBuilder("/bin/sh", "-c", command)
            .directory(dir)
            .redirectErrorStream(true)
            .apply { environment().apply { clear(); putAll(this@HostShell.environment) } }
            .start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().use { r -> r.forEachLine { synchronized(output) { if (output.length < MAX_OUTPUT) output.appendLine(it) } } } }
        reader.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(5_000)
        return Result(if (finished) process.exitValue() else null, synchronized(output) { output.toString() })
    }

    data class Result(val exitCode: Int?, val output: String)

    /** `/workspace/x` → the trial's workspace; anything else (a host path) is used as given. */
    internal fun resolveCwd(cwd: String): File {
        val mapped = mapPaths(cwd.trim().ifEmpty { "/workspace" })
        val file = File(mapped)
        return if (file.isDirectory) file else workspace
    }

    /** Rewrites the guest path `/workspace` (as a whole path segment) to the trial's workspace. */
    internal fun mapPaths(text: String): String = WORKSPACE.replace(text) { workspace.path }

    companion object {
        private val WORKSPACE = Regex("""(?<![\w.~/-])/workspace(?=$|[/\s'"`;:)&|<>])""")
        private const val MAX_OUTPUT = 200_000

        /** The host's PATH, without the user's own folders that a benchmark shouldn't depend on (they're kept: tools like node live there). */
        private fun hostPath(): List<String> =
            (System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/usr/local/bin", "/usr/bin", "/bin"))
                .filter { it.isNotBlank() }.distinct()

        private val APK_SHIM = """
            #!/bin/sh
            # Benchmark host: there is no Alpine here. Tools the host already has count as installed.
            case "${'$'}1" in
              update|upgrade) exit 0 ;;
              add) shift ;;
              *) echo "apk: not available on the benchmark host (only 'apk add' of preinstalled tools)" >&2; exit 1 ;;
            esac
            missing=""
            for p in "${'$'}@"; do
              case "${'$'}p" in -*) continue ;; esac
              case "${'$'}p" in
                nodejs|npm|nodejs-current) command -v node >/dev/null 2>&1 || missing="${'$'}missing ${'$'}p" ;;
                python3|python3-dev|py3-pip) command -v python3 >/dev/null 2>&1 || missing="${'$'}missing ${'$'}p" ;;
                py3-*) python3 -c "import ${'$'}{p#py3-}" >/dev/null 2>&1 || missing="${'$'}missing ${'$'}p" ;;
                *) command -v "${'$'}p" >/dev/null 2>&1 || missing="${'$'}missing ${'$'}p" ;;
              esac
            done
            if [ -z "${'$'}missing" ]; then echo "OK: already installed on the benchmark host"; exit 0; fi
            echo "ERROR: unable to select packages:${'$'}missing (not available on the benchmark host)"
            exit 1
        """.trimIndent() + "\n"
    }
}
