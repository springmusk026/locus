package dev.locus.feature.workspace.linux.health

import dev.locus.core.linux.HealthCheck
import dev.locus.core.linux.HealthLevel
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.linux.LinuxInventory
import dev.locus.core.linux.LinuxReport

/**
 * What the health page shows, as pure rules (docs/design/linux-health.md, "The health page"). It's a
 * summary read at a glance: problems first, each with its fix; passed checks folded into one row;
 * memory as a warning only when it's low; packages as one row; any other list cut to its top
 * [TOP_FEW] until "Show all".
 */
internal object HealthSummary {
    /** How many folders or processes show before "Show all". */
    const val TOP_FEW = 3

    /** Checks that aren't OK, worst first. Memory is left out: its own section warns about it. */
    fun problems(checks: List<HealthCheck>): List<HealthCheck> =
        checks.filter { it.level != HealthLevel.Ok && it.id != MEMORY }.sortedByDescending { it.level }

    /** Checks that passed, folded into one row on the page. */
    fun passed(checks: List<HealthCheck>): List<HealthCheck> = checks.filter { it.level == HealthLevel.Ok && it.id != MEMORY }

    /** [items] as shown: all of them when [expanded], else the top few. */
    fun <T> visible(items: List<T>, expanded: Boolean): List<T> = if (expanded) items else items.take(TOP_FEW)

    /** Whether [items] needs a "Show all" toggle. */
    fun hasMore(items: List<*>): Boolean = items.size > TOP_FEW

    /** "42 you installed · 214 total": the one Packages row; the list itself is its own screen. */
    fun packagesLine(packages: List<LinuxInventory.Package>?): String =
        packages?.let { all -> "${all.count { it.userInstalled }} you installed · ${all.size} total" } ?: "Can't read apk's package database"

    /** The low-memory warning, or null when memory is fine (then there's no memory dashboard, just one line). */
    fun memoryWarning(memory: LinuxReport.Memory?): String? =
        memory?.takeIf(LinuxHealth::isLowMemory)?.let {
            "${LinuxHealth.bytes(it.availableBytes)} of ${LinuxHealth.bytes(it.totalBytes)} available. " +
                "Android may stop Linux programs, especially while Locus is in the background."
        }

    /** "2 running · 84 MB": what Linux's programs hold. */
    fun processesLine(processes: List<LinuxReport.GuestProcess>): String =
        if (processes.isEmpty()) "None running" else "${processes.size} running · ${LinuxHealth.bytes(processes.sumOf { it.rssBytes })}"

    private const val MEMORY = "memory"
}
