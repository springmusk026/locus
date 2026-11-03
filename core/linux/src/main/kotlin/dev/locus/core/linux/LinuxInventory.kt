package dev.locus.core.linux

/**
 * The parsers behind the Linux health and packages screens (docs/design/linux-health.md). They read
 * text Alpine and Linux already write (apk's installed database, `/etc/apk/world`, `/proc/meminfo`,
 * `/proc/<pid>/status`, `apk list -u`), kept free of Android so they're tested against fixtures.
 */
object LinuxInventory {

    /** One package from apk's installed database. [installedBytes] is apk's own `I:` figure. */
    data class Package(
        val name: String,
        val version: String,
        val description: String = "",
        val installedBytes: Long = 0,
        val arch: String = "",
        /** Named in `/etc/apk/world`: the user (or the agent) asked for it, rather than it coming as a dependency. */
        val explicit: Boolean = false,
    ) {
        /**
         * In world, but not one of the packages Alpine's own image puts there ([BASE_SYSTEM]): what the
         * user or the agent installed. Only these are offered for uninstalling.
         */
        val userInstalled: Boolean get() = explicit && name !in BASE_SYSTEM
    }

    /** `/etc/apk/world` of Alpine 3.24's minirootfs: the system itself, never listed as "you installed" nor removable here. */
    val BASE_SYSTEM = setOf("alpine-baselayout", "alpine-keys", "alpine-release", "apk-tools", "busybox", "musl-utils")

    /**
     * The packages screen's list: [search] for [query], limited to what the user installed unless
     * [all]. Name matches first, as in [search].
     */
    fun filter(packages: List<Package>, query: String, all: Boolean): List<Package> =
        search(packages, query).let { hits -> if (all) hits else hits.filter { it.userInstalled } }

    /**
     * A name `apk del` can be given safely: apk's own package-name characters only, so it can't be read
     * as an option or a second word by the shell.
     */
    fun isValidName(name: String): Boolean = Regex("""[a-z0-9][a-z0-9._+-]*""").matches(name)

    /**
     * Parses `/lib/apk/db/installed`: blank-line separated records of `X:value` lines (the same format
     * in apk-tools 2 and 3; Alpine 3.24 ships apk-tools 3). Only the fields shown are kept; file lists
     * (`F:`, `R:`, `Z:`…) are skipped. Records without a name or version are dropped, so a half-written
     * database gives the packages it can rather than nothing.
     */
    fun parseInstalledDb(text: String, world: Set<String> = emptySet()): List<Package> {
        val out = ArrayList<Package>()
        var name: String? = null
        var version: String? = null
        var description = ""
        var size = 0L
        var arch = ""
        fun flush() {
            val n = name
            val v = version
            if (n != null && v != null) out += Package(n, v, description, size, arch, explicit = n in world)
            name = null; version = null; description = ""; size = 0L; arch = ""
        }
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) { flush(); return@forEach }
            if (line.length < 2 || line[1] != ':') return@forEach
            val value = line.substring(2)
            when (line[0]) {
                'P' -> name = value
                'V' -> version = value
                'T' -> description = value
                'I' -> size = value.toLongOrNull() ?: 0L
                'A' -> arch = value
            }
        }
        flush()
        return out.sortedBy { it.name }
    }

    /**
     * Names in `/etc/apk/world`, one dependency per line or space separated, with any version
     * constraint (`python3>=3.12`, `foo=1.0`, `bar~2`) or repository tag (`baz@edge`) removed.
     */
    fun parseWorld(text: String): Set<String> = text.split(Regex("\\s+"))
        .map { it.trim().removePrefix("!") }
        .filter { it.isNotEmpty() }
        .map { it.split('<', '>', '=', '~', '@').first() }
        .filter { it.isNotEmpty() }
        .toSet()

    /** One package that has a newer version in the repositories. */
    data class Upgrade(val name: String, val installed: String, val available: String)

    /**
     * Parses `apk list -u` (`--upgradable`), whose lines read
     * `busybox-1.37.0-r32 aarch64 {busybox} (GPL-2.0-only) [upgradable from: busybox-1.37.0-r31]`, and also
     * the older `apk version -l '<'` form (`busybox-1.37.0-r31   < 1.37.0-r32`), so it works across apk-tools versions.
     */
    fun parseUpgrades(text: String): List<Upgrade> = text.lineSequence().mapNotNull { line ->
        val l = line.trim()
        LIST_UPGRADABLE.find(l)?.let { m ->
            val newer = m.groupValues[1]
            val from = m.groupValues[2]
            val (name, available) = splitNameVersion(newer) ?: return@mapNotNull null
            val installed = splitNameVersion(from)?.second ?: from
            return@mapNotNull Upgrade(name, installed, available)
        }
        VERSION_LESS.find(l)?.let { m ->
            val (name, installed) = splitNameVersion(m.groupValues[1]) ?: return@mapNotNull null
            return@mapNotNull Upgrade(name, installed, m.groupValues[2])
        }
        null
    }.distinctBy { it.name }.sortedBy { it.name }.toList()

    private val LIST_UPGRADABLE = Regex("""^(\S+)\s.*\[upgradable from:\s*(\S+)\s*]""")
    private val VERSION_LESS = Regex("""^(\S+)\s+<\s+(\S+)$""")

    /**
     * `py3-pip-25.1-r0` → (`py3-pip`, `25.1-r0`). An apk version starts with a digit after the last
     * hyphen that's followed by one, and always ends in `-r<N>`.
     */
    fun splitNameVersion(nameVersion: String): Pair<String, String>? {
        val m = NAME_VERSION.matchEntire(nameVersion) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    private val NAME_VERSION = Regex("""^(.+?)-(\d[^-]*-r\d+)$""")

    /** What `/proc/meminfo` says, in bytes. */
    data class MemInfo(val totalBytes: Long, val availableBytes: Long, val swapTotalBytes: Long = 0, val swapFreeBytes: Long = 0)

    /**
     * Parses `/proc/meminfo` (`MemTotal:  7812345 kB`). Kernels before 3.14 have no MemAvailable; then
     * free + buffers + cached is the usual estimate. Null when MemTotal is missing.
     */
    fun parseMeminfo(text: String): MemInfo? {
        val kb = text.lineSequence().mapNotNull { line ->
            val key = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
            if (key.isEmpty() || value == null) null else key to value
        }.toMap()
        val total = kb["MemTotal"] ?: return null
        val available = kb["MemAvailable"] ?: ((kb["MemFree"] ?: 0) + (kb["Buffers"] ?: 0) + (kb["Cached"] ?: 0))
        return MemInfo(total * 1024, available * 1024, (kb["SwapTotal"] ?: 0) * 1024, (kb["SwapFree"] ?: 0) * 1024)
    }

    /** The resident set size from `/proc/<pid>/status` (`VmRSS:   12345 kB`), in bytes; null for a kernel thread or a gone process. */
    fun parseRss(status: String): Long? = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") }
        ?.removePrefix("VmRSS:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.times(1024)

    /**
     * A readable name for a process from its NUL-separated `/proc/<pid>/cmdline`: proot's own processes
     * are "proot"; a program run through an interpreter is named by its script (`python3 app.py`).
     */
    fun processName(cmdline: String): String {
        val args = cmdline.split('\u0000').filter { it.isNotEmpty() }
        val first = args.firstOrNull() ?: return "?"
        val base = first.substringAfterLast('/')
        if (base.startsWith("libproot")) return "proot"
        val interpreters = setOf("sh", "bash", "ash", "python", "python3", "node", "ruby", "perl", "php", "java")
        val script = args.drop(1).firstOrNull { !it.startsWith("-") }?.substringAfterLast('/')
        return if (base in interpreters && script != null) "$base $script" else base
    }

    /** `VERSION_ID=3.24.3` from `/etc/os-release`, or the first line of `/etc/alpine-release`. */
    fun parseOsVersion(osRelease: String?, alpineRelease: String?): String? =
        osRelease?.lineSequence()?.firstOrNull { it.startsWith("VERSION_ID=") }?.substringAfter('=')?.trim('"', ' ')?.takeIf { it.isNotEmpty() }
            ?: alpineRelease?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** The first version-like number in proot's `--version` banner (`5.4.0`), or null. */
    fun parseProotVersion(output: String): String? = Regex("""\b(v?\d+\.\d+(?:\.\d+)?)\b""").find(output)?.groupValues?.get(1)?.removePrefix("v")

    /** Whether resolv.conf names at least one nameserver. */
    fun hasNameserver(resolvConf: String?): Boolean =
        resolvConf?.lineSequence()?.any { it.trim().startsWith("nameserver ") && it.trim().length > "nameserver ".length } == true

    /** Packages whose name or description contains [query] (case-insensitive), names that match first. */
    fun search(packages: List<Package>, query: String): List<Package> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return packages
        return packages.filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
            .sortedWith(compareBy<Package>({ !it.name.lowercase().startsWith(q) }, { !it.name.lowercase().contains(q) }, { it.name }))
    }
}
