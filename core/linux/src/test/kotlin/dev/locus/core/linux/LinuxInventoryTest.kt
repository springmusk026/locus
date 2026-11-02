package dev.locus.core.linux

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxInventoryTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("linux-health/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `apk's installed database from Alpine 3_24's minirootfs`() {
        val world = LinuxInventory.parseWorld(fixture("apk-world-alpine-3.24.3"))
        assertEquals(setOf("alpine-baselayout", "alpine-keys", "alpine-release", "apk-tools", "busybox", "musl-utils"), world)
        val pkgs = LinuxInventory.parseInstalledDb(fixture("apk-installed-alpine-3.24.3"), world)
        assertEquals(16, pkgs.size)
        val apk = pkgs.single { it.name == "apk-tools" }
        assertEquals("3.0.8-r0", apk.version)
        assertEquals("aarch64", apk.arch)
        assertTrue(apk.explicit)
        assertFalse(pkgs.single { it.name == "libapk" }.explicit, "a dependency isn't marked as asked for")
        assertEquals(6552, pkgs.single { it.name == "alpine-baselayout" }.installedBytes)
        assertEquals("Alpine base dir structure and init scripts", pkgs.single { it.name == "alpine-baselayout" }.description)
        assertEquals(pkgs.map { it.name }.sorted(), pkgs.map { it.name })
    }

    @Test
    fun `a truncated database still gives its complete records`() {
        val text = "P:musl\nV:1.2.6-r3\nI:100\n\nP:zlib\nV:1.3.2-r1\n\nP:half-written\n"
        assertEquals(listOf("musl", "zlib"), LinuxInventory.parseInstalledDb(text).map { it.name })
        assertEquals(emptyList(), LinuxInventory.parseInstalledDb(""))
    }

    @Test
    fun `world entries lose their constraints and tags`() {
        assertEquals(setOf("python3", "foo", "bar", "baz", "nodejs"), LinuxInventory.parseWorld("python3>=3.12 foo=1.0\nbar~2 baz@edge\n!nodejs\n"))
    }

    @Test
    fun `upgradable packages from apk list -u and apk version`() {
        val list = LinuxInventory.parseUpgrades(fixture("apk-list-upgradable"))
        assertEquals(listOf("busybox", "busybox-binsh", "libcrypto3", "py3-pip"), list.map { it.name })
        assertEquals(LinuxInventory.Upgrade("py3-pip", "25.1-r0", "25.1.1-r0"), list.single { it.name == "py3-pip" })
        val old = LinuxInventory.parseUpgrades(fixture("apk-version-less"))
        assertEquals(listOf(LinuxInventory.Upgrade("busybox", "1.37.0-r31", "1.37.0-r32"), LinuxInventory.Upgrade("ssl_client", "1.37.0-r31", "1.37.0-r32")), old)
        assertEquals(emptyList(), LinuxInventory.parseUpgrades("OK: 16 packages\n"))
    }

    @Test
    fun `name and version split at the version's first digit`() {
        assertEquals("busybox-binsh" to "1.37.0-r32", LinuxInventory.splitNameVersion("busybox-binsh-1.37.0-r32"))
        assertEquals("libcrypto3" to "3.5.10-r0", LinuxInventory.splitNameVersion("libcrypto3-3.5.10-r0"))
        assertNull(LinuxInventory.splitNameVersion("not-a-package"))
    }

    @Test
    fun `meminfo from a phone`() {
        val m = requireNotNull(LinuxInventory.parseMeminfo(fixture("meminfo-pixel")))
        assertEquals(7812344L * 1024, m.totalBytes)
        assertEquals(2967520L * 1024, m.availableBytes)
        assertEquals(6291452L * 1024, m.swapTotalBytes)
        assertEquals(4980732L * 1024, m.swapFreeBytes)
        // An old kernel without MemAvailable: free + buffers + cached.
        assertEquals((100L + 20 + 300) * 1024, LinuxInventory.parseMeminfo("MemTotal: 1000 kB\nMemFree: 100 kB\nBuffers: 20 kB\nCached: 300 kB\n")?.availableBytes)
        assertNull(LinuxInventory.parseMeminfo("garbage"))
    }

    @Test
    fun `process status and names`() {
        assertEquals(23180L * 1024, LinuxInventory.parseRss(fixture("proc-status-python")))
        assertNull(LinuxInventory.parseRss("Name:\tkthreadd\n"))
        assertEquals("proot", LinuxInventory.processName("/data/app/~~x/dev.locus-1/lib/arm64/libproot.so\u0000--kill-on-exit\u0000"))
        assertEquals("python3 app.py", LinuxInventory.processName("/usr/bin/python3\u0000-u\u0000/workspace/app.py\u0000"))
        assertEquals("node", LinuxInventory.processName("node\u0000"))
        assertEquals("?", LinuxInventory.processName(""))
    }

    @Test
    fun `os version, proot version and resolv conf`() {
        assertEquals("3.24.3", LinuxInventory.parseOsVersion(fixture("os-release-alpine-3.24.3"), null))
        assertEquals("3.24.3", LinuxInventory.parseOsVersion(null, "3.24.3\n"))
        assertNull(LinuxInventory.parseOsVersion(null, null))
        assertEquals("5.4.0", LinuxInventory.parseProotVersion(" _____ _____              ___\n|  __ \\  __ \\_____  _____|   |_\n\nproot v5.4.0: chroot, mount --bind, and binfmt_misc"))
        assertNull(LinuxInventory.parseProotVersion("no version here"))
        assertTrue(LinuxInventory.hasNameserver(LinuxEnvironment.DEFAULT_RESOLV_CONF))
        assertFalse(LinuxInventory.hasNameserver("# empty\nsearch lan\n"))
        assertFalse(LinuxInventory.hasNameserver(null))
    }

    @Test
    fun `package search puts name matches first`() {
        val pkgs = listOf(
            LinuxInventory.Package("python3", "3.12", "A high-level scripting language"),
            LinuxInventory.Package("py3-pip", "25", "Tool for installing python packages"),
            LinuxInventory.Package("git", "2.49", "Distributed version control"),
        )
        assertEquals(listOf("python3", "py3-pip"), LinuxInventory.search(pkgs, "python").map { it.name })
        assertEquals(3, LinuxInventory.search(pkgs, " ").size)
    }

    @Test
    fun `you installed means world without Alpine's own base, and all means everything`() {
        val world = LinuxInventory.parseWorld(fixture("apk-world-alpine-3.24.3")) + setOf("python3", "git")
        val pkgs = LinuxInventory.parseInstalledDb(fixture("apk-installed-alpine-3.24.3"), world) + listOf(
            LinuxInventory.Package("python3", "3.12.8-r1", "A high-level scripting language", explicit = true),
            LinuxInventory.Package("py3-pip", "25.1-r0", "Tool for installing python packages"),
            LinuxInventory.Package("git", "2.49.0-r0", "Distributed version control system", explicit = true),
        )
        // A fresh install: nothing the user installed, though world names the base system.
        val fresh = LinuxInventory.parseInstalledDb(fixture("apk-installed-alpine-3.24.3"), LinuxInventory.parseWorld(fixture("apk-world-alpine-3.24.3")))
        assertEquals(emptyList(), LinuxInventory.filter(fresh, "", all = false))
        assertEquals(16, LinuxInventory.filter(fresh, "", all = true).size)
        assertFalse(fresh.single { it.name == "busybox" }.userInstalled, "busybox is Alpine's, not the user's")

        assertEquals(listOf("git", "python3"), LinuxInventory.filter(pkgs, "", all = false).map { it.name }.sorted())
        assertEquals(19, LinuxInventory.filter(pkgs, "", all = true).size)
        // Search applies within the scope: a dependency matches only under All.
        assertEquals(listOf("python3"), LinuxInventory.filter(pkgs, "python", all = false).map { it.name })
        assertEquals(listOf("python3", "py3-pip"), LinuxInventory.filter(pkgs, "python", all = true).map { it.name })
        assertEquals(listOf("musl", "musl-utils"), LinuxInventory.filter(pkgs, "MUSL", all = true).map { it.name }.take(2))
        assertEquals(emptyList(), LinuxInventory.filter(pkgs, "zzz", all = true))
    }

    @Test
    fun `search over 500 packages is quick and ranked`() {
        val many = (0 until 600).map { LinuxInventory.Package("pkg-$it", "1.0-r0", if (it % 2 == 0) "even python helper" else "odd") }
        val started = System.nanoTime()
        val hits = LinuxInventory.filter(many, "python", all = true)
        assertEquals(300, hits.size)
        assertEquals(listOf("pkg-12"), LinuxInventory.filter(many, "pkg-12", all = true).map { it.name }.take(1))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500, "filtering is cheap enough to run per keystroke")
    }

    @Test
    fun `only plain package names reach apk del`() {
        assertTrue(LinuxInventory.isValidName("py3-pip"))
        assertTrue(LinuxInventory.isValidName("g++"))
        assertFalse(LinuxInventory.isValidName("-f"))
        assertFalse(LinuxInventory.isValidName("git; rm -rf /"))
        assertFalse(LinuxInventory.isValidName("Git"))
        assertFalse(LinuxInventory.isValidName(""))
    }

    @Test
    fun `network check output`() {
        assertEquals(LinuxReport.Network(dnsOk = true, httpsOk = false, millis = 9), LinuxProbe.parseNetwork("dns=0\nhttps=1\n", 9))
        assertEquals(LinuxReport.Network(dnsOk = false, httpsOk = false, millis = 1), LinuxProbe.parseNetwork("", 1))
    }

    @Test
    fun `disk usage walks without following links and cleans only real caches`() = runBlocking {
        val root = Files.createTempDirectory("rootfs").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            File(root, "usr/lib/big").apply { parentFile.mkdirs(); writeBytes(ByteArray(5_000)) }
            File(root, "var/cache/apk/APKINDEX.tar.gz").apply { parentFile.mkdirs(); writeBytes(ByteArray(2_000)) }
            File(root, "root/.npm/_cacache/x/y").apply { parentFile.mkdirs(); writeBytes(ByteArray(1_000)) }
            File(root, "etc/hostname").apply { parentFile.mkdirs(); writeText("x") }
            File(outside, "precious").writeBytes(ByteArray(9_000))
            // root/.cache/pip is a symlink out of the distro: neither counted nor cleaned.
            File(root, "root/.cache").mkdirs()
            Files.createSymbolicLink(File(root, "root/.cache/pip").toPath(), outside.toPath())

            val usage = DiskUsage.measure(root, LinuxProbe.CACHE_DIRS)
            assertEquals(8_001, usage.total)
            assertEquals(LinuxReport.Usage("/usr/lib", 5_000), usage.biggest.first())
            assertEquals(listOf(LinuxReport.Usage("/var/cache/apk", 2_000), LinuxReport.Usage("/root/.npm/_cacache", 1_000)), usage.caches)

            assertEquals(3_000, DiskUsage.clean(root, LinuxProbe.CACHE_DIRS))
            assertTrue(File(root, "var/cache/apk").isDirectory, "the cache folder itself stays")
            assertFalse(File(root, "var/cache/apk/APKINDEX.tar.gz").exists())
            assertTrue(File(outside, "precious").exists())
            assertTrue(File(root, "usr/lib/big").exists())
            assertEquals(5_001, DiskUsage.measure(root, LinuxProbe.CACHE_DIRS).total)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `guest processes are the app uid's non-app processes`() {
        val proc = Files.createTempDirectory("proc").toFile()
        try {
            fun process(pid: Int, uid: Int, cmdline: String, rssKb: Int) = File(proc, "$pid").apply {
                mkdirs()
                File(this, "status").writeText("Name:\tx\nUid:\t$uid\t$uid\t$uid\t$uid\nVmRSS:\t$rssKb kB\n")
                File(this, "cmdline").writeText(cmdline)
            }
            process(100, 10234, "dev.locus\u0000", 300_000)
            process(101, 10234, "dev.locus:remote\u0000", 50_000)
            process(200, 10234, "/data/app/x/lib/arm64/libproot.so\u0000-r\u0000", 4_000)
            process(201, 10234, "/usr/bin/node\u0000server.js\u0000", 80_000)
            process(300, 10999, "/usr/bin/other\u0000", 1_000)
            File(proc, "self").mkdirs()
            val list = GuestProcesses.list(proc, 10234, "dev.locus")
            assertEquals(listOf(201, 200), list.map { it.pid })
            assertEquals("node server.js", list.first().name)
            assertEquals(80_000L * 1024, list.first().rssBytes)
        } finally {
            proc.deleteRecursively()
        }
    }
}
