package dev.locus.core.linux

import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcIsolationTest {
    private val dir: File = Files.createTempDirectory("proc").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val paths = ProotArgs.Paths(
        proot = "/app/lib/libproot.so",
        rootfs = "/data/linux/alpine",
        tmp = "/data/linux/tmp",
        workspace = "/data/workspace",
        procFakes = "/data/linux/proc-fakes",
        shadow = "/data/linux/proc-shadow",
    )

    /** The `-b` values, in order. */
    private fun bindings(args: List<String>) = args.zipWithNext().filter { it.first == "-b" }.map { it.second }

    @Test
    fun `every app process and thread is shadowed by the empty directory`() {
        val args = ProotArgs.build(paths, "/workspace", listOf("stat"), hiddenPids = setOf(4321, 77, 4322), hiddenDevices = emptyList())
        val b = bindings(args)
        assertTrue("/proc" in b, "host /proc is still bound for the guest's own processes")
        assertEquals(
            listOf("/data/linux/proc-shadow:/proc/77", "/data/linux/proc-shadow:/proc/4321", "/data/linux/proc-shadow:/proc/4322"),
            b.filter { it.startsWith(paths.shadow) },
        )
    }

    @Test
    fun `invalid and repeated ids are dropped`() {
        val args = ProotArgs.build(paths, "/workspace", emptyList(), hiddenPids = listOf(0, -1, 9, 9), hiddenDevices = emptyList())
        assertEquals(listOf("${paths.shadow}:/proc/9"), bindings(args).filter { it.startsWith(paths.shadow) })
    }

    @Test
    fun `binder devices are hidden and the rest of the command line is unchanged`() {
        val args = ProotArgs.build(paths, "/workspace/site", listOf("stat", "uptime"), hiddenPids = setOf(10), hiddenDevices = listOf("/dev/binder", "/dev/binderfs"))
        assertEquals(
            listOf(
                "/app/lib/libproot.so", "--kill-on-exit", "-0", "--link2symlink", "-L", "--sysvipc",
                "-r", "/data/linux/alpine",
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "/dev/urandom:/dev/random",
                "-b", "/proc/self/fd:/dev/fd",
                "-b", "/data/linux/proc-fakes/stat:/proc/stat",
                "-b", "/data/linux/proc-fakes/uptime:/proc/uptime",
                "-b", "/data/linux/proc-shadow:/proc/10",
                "-b", "/data/linux/proc-shadow:/dev/binder",
                "-b", "/data/linux/proc-shadow:/dev/binderfs",
                "-b", "/data/linux/tmp:/tmp",
                "-b", "/data/workspace:/workspace",
                "-w", "/workspace/site",
            ),
            args,
        )
    }

    @Test
    fun `the binder list covers the binder nodes and binderfs`() {
        assertEquals(setOf("/dev/binder", "/dev/hwbinder", "/dev/vndbinder", "/dev/binderfs"), ProcIsolation.BINDER_DEVICES.toSet())
    }

    private fun process(pid: Int, uid: Int, cmdline: String, threads: List<Int> = listOf(pid)) {
        val p = File(dir, "$pid").apply { mkdirs() }
        File(p, "status").writeText("Name:\tx\nState:\tS (sleeping)\nUid:\t$uid\t$uid\t$uid\t$uid\nGid:\t$uid\t$uid\t$uid\t$uid\n")
        File(p, "cmdline").writeBytes((cmdline.split(' ').joinToString("\u0000") + "\u0000").toByteArray())
        threads.forEach { File(p, "task/$it").mkdirs() }
    }

    @Test
    fun `finds the app's processes and threads but not proot, its guests or other apps`() {
        process(100, 10234, "dev.locus", threads = listOf(100, 101, 102))
        process(200, 10234, "dev.locus:remote", threads = listOf(200, 201))
        process(300, 10234, "/data/app/lib/arm64/libproot.so --kill-on-exit -0 -r /data/linux/alpine")
        process(301, 10234, "/bin/sh -c ls")
        process(400, 10500, "dev.locus.other")
        process(401, 10500, "dev.locus")
        process(500, 10234, "dev.locusx")
        File(dir, "self").mkdirs()
        File(dir, "cpuinfo").writeText("")

        val found = AppProcesses.find(dir, uid = 10234, packageName = "dev.locus", self = 100)
        assertEquals(setOf(100, 101, 102, 200, 201), found)
    }

    @Test
    fun `the app itself is hidden even when its own entry can't be read`() {
        process(300, 10234, "/bin/sh")
        val found = AppProcesses.find(dir, uid = 10234, packageName = "dev.locus", self = 42)
        assertEquals(setOf(42), found)
        assertFalse(300 in found)
    }
}
