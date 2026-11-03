package dev.locus.core.linux

import java.io.File

/**
 * Keeps Linux programs away from Locus's own processes (docs/design/linux-isolation.md).
 *
 * Linux runs as the app's own uid, so the kernel lets it open `/proc/<pid>/fd/<n>` of Locus's main
 * process, which reaches the files Locus has open (the database, settings) whatever proot's path
 * translation says: proot leaves `/proc/<pid>/fd/<n>` unresolved on purpose (`readlink_proc` in its
 * `src/path/proc.c` returns `DONT_CANONICALIZE`) and hands it to the kernel. Two layers stop that:
 *
 * 1. [makeNotDumpable], in every Locus process at startup. The kernel then refuses ptrace-mode
 *    access from same-uid processes without CAP_SYS_PTRACE: `fd`, `cwd`, `root`, `exe`, `environ`,
 *    `mem`, `maps` and `map_files` under `/proc/<pid>` and `/proc/<pid>/task/<tid>`, plus `ptrace`
 *    and `process_vm_readv`, for every thread, including ones started later.
 * 2. [ProotArgs.build] binds an empty directory over `/proc/<pid>` of each Locus process and thread
 *    that exists when a command starts ([AppProcesses.find]), so even path lookups never reach the
 *    kernel; and over the binder devices, so Linux programs can't call Android services (Keystore
 *    among them) as Locus.
 */
object ProcIsolation {
    /** `PR_SET_DUMPABLE` from `<linux/prctl.h>`; also android.system.OsConstants.PR_SET_DUMPABLE. */
    private const val PR_SET_DUMPABLE = 4

    /**
     * Makes this process non-dumpable. Children Locus starts (proot) are dumpable again after
     * `execve`, so proot can still trace the programs it runs. Native crash reports still work:
     * Android's crash handler sets the flag back while it dumps. Returns false if the call failed.
     */
    fun makeNotDumpable(): Boolean = runCatching { android.system.Os.prctl(PR_SET_DUMPABLE, 0, 0, 0, 0) == 0 }.getOrDefault(false)

    /**
     * Binder device nodes. Linux programs have no use for them, and through them a program running
     * as Locus's uid could ask Android services, the Keystore included, for anything Locus may ask.
     */
    internal val BINDER_DEVICES = listOf("/dev/binder", "/dev/hwbinder", "/dev/vndbinder", "/dev/binderfs")
}

/** Finds Locus's own processes and their threads, the ones Linux programs must not see. */
internal object AppProcesses {
    /**
     * Process and thread ids under [procRoot] (normally `/proc`) owned by [uid] whose command line
     * is [packageName] or `<packageName>:<name>` (Android names app processes so), plus [self] and
     * its threads. Proot and the programs it runs have other command lines, so they stay visible.
     * A thread id opens `/proc/<tid>` as well as a process id does, so threads are included.
     */
    fun find(procRoot: File, uid: Int, packageName: String, self: Int): Set<Int> {
        val leaders = procRoot.listFiles().orEmpty()
            .mapNotNull { it.name.toIntOrNull() }
            .filter { pid -> pid == self || (uidOf(procRoot, pid) == uid && isAppProcess(procRoot, pid, packageName)) }
            .toSet() + self
        return leaders.flatMapTo(sortedSetOf()) { pid -> threadsOf(procRoot, pid) + pid }
    }

    private fun uidOf(procRoot: File, pid: Int): Int? = runCatching {
        File(procRoot, "$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
        }
    }.getOrNull()

    private fun isAppProcess(procRoot: File, pid: Int, packageName: String): Boolean {
        val name = runCatching { File(procRoot, "$pid/cmdline").readBytes() }.getOrNull()
            ?.let { bytes -> String(bytes, Charsets.UTF_8).substringBefore('\u0000') } ?: return false
        return name == packageName || name.startsWith("$packageName:")
    }

    private fun threadsOf(procRoot: File, pid: Int): List<Int> =
        File(procRoot, "$pid/task").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }
}

/**
 * Finds the processes that hold a local MCP server's secrets, to hide them from every other Linux
 * launch the way [AppProcesses] hides Locus's own (docs/design/linux-isolation.md, "Local MCP
 * server secrets"). A server gets its secrets as environment variables, which any same-uid process
 * can read through `/proc/<pid>/environ`; with `/proc/<pid>` shadowed, a later command can't name it.
 */
internal object SecretProcesses {
    /**
     * [roots] (the proot processes Locus started with secrets) and every process below them, by the
     * parent ids in `/proc/<pid>/stat`, with their threads. proot passes the environment on to the
     * program it starts, and `npx`, `uvx` and shells pass it on to theirs, so the whole tree has it.
     */
    fun find(procRoot: File, roots: Collection<Int>): Set<Int> {
        if (roots.isEmpty()) return emptySet()
        val children = procRoot.listFiles().orEmpty()
            .mapNotNull { it.name.toIntOrNull() }
            .mapNotNull { pid -> parentOf(procRoot, pid)?.let { pid to it } }
            .groupBy({ it.second }, { it.first })
        val tree = mutableSetOf<Int>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (tree.add(pid)) children[pid]?.let(queue::addAll)
        }
        return tree.flatMapTo(sortedSetOf()) { pid -> threadsOf(procRoot, pid) + pid }
    }

    /** The parent id: the field after the state in `stat`, read after the last `)` since the name may hold spaces and brackets. */
    internal fun parentOf(procRoot: File, pid: Int): Int? = runCatching {
        File(procRoot, "$pid/stat").readText().substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()
    }.getOrNull()

    private fun threadsOf(procRoot: File, pid: Int): List<Int> =
        File(procRoot, "$pid/task").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }
}

/** The proot command line, kept free of Android so it can be unit tested. */
internal object ProotArgs {
    class Paths(
        val proot: String,
        val rootfs: String,
        val tmp: String,
        val workspace: String,
        /** Directory holding the fake `/proc` files ([fakeProc]). */
        val procFakes: String,
        /** An empty directory bound over everything hidden. */
        val shadow: String,
    )

    /**
     * proot binds the deepest matching guest path (`insort_binding` in its `src/path/binding.c`), so
     * `/proc/<pid>` bound to [Paths.shadow] wins over `/proc` for that process and everything below
     * it (`fd`, `task/<tid>/fd`, `mem`, …), whatever order the options come in.
     *
     * [hiddenPids] are the processes and threads to hide; [hiddenDevices] are `/dev` entries to hide
     * (only ones that exist on the host: binding a missing one would make proot invent it).
     */
    fun build(paths: Paths, cwd: String, fakeProc: List<String>, hiddenPids: Collection<Int>, hiddenDevices: Collection<String>): List<String> = buildList {
        add(paths.proot)
        add("--kill-on-exit")
        add("-0")
        add("--link2symlink")
        add("-L")
        add("--sysvipc")
        add("-r"); add(paths.rootfs)
        listOf("/dev", "/proc", "/sys").forEach { add("-b"); add(it) }
        add("-b"); add("/dev/urandom:/dev/random")
        add("-b"); add("/proc/self/fd:/dev/fd")
        fakeProc.forEach { name -> add("-b"); add("${paths.procFakes}/$name:/proc/$name") }
        hiddenPids.filter { it > 0 }.distinct().sorted().forEach { pid -> add("-b"); add("${paths.shadow}:/proc/$pid") }
        hiddenDevices.distinct().forEach { dev -> add("-b"); add("${paths.shadow}:$dev") }
        add("-b"); add("${paths.tmp}:/tmp")
        add("-b"); add("${paths.workspace}:/workspace")
        add("-w"); add(cwd)
    }
}
