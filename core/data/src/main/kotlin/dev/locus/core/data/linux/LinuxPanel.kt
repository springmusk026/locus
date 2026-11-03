package dev.locus.core.data.linux

import dev.locus.core.linux.AndroidKillNotice
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import kotlin.math.ceil
import kotlin.math.max

/** Roughly what an install downloads and takes on the phone, and how long it takes. */
data class InstallEstimate(val downloadMb: Int, val diskMb: Int, val minutes: Int) {
    /** "About 15 MB to download, 44 MB on the phone. Usually about 2 minutes on Wi-Fi." */
    val summary: String
        get() = "About $downloadMb MB to download, $diskMb MB on the phone. " +
            if (minutes <= 1) "Usually under a minute on Wi-Fi." else "Usually about $minutes minutes on Wi-Fi."

    companion object {
        /** The Alpine minirootfs: 4.0 MB from dl-cdn.alpinelinux.org, 8.5 MB unpacked (3.24.3, aarch64). */
        const val BASE_DOWNLOAD_MB = 4
        const val BASE_DISK_MB = 9

        /**
         * Installing Linux (unless [linuxInstalled]) and [packages]. The time assumes about 1 MB/s
         * and adds what unpacking and apk take under proot, which is slower than a normal system:
         * 20 seconds for the base and 30 per package, plus 15 for apk update when there are packages.
         */
        fun of(packages: Collection<LinuxPackage>, linuxInstalled: Boolean = false): InstallEstimate {
            val download = (if (linuxInstalled) 0 else BASE_DOWNLOAD_MB) + packages.sumOf { it.downloadMb }
            val disk = (if (linuxInstalled) 0 else BASE_DISK_MB) + packages.sumOf { it.diskMb }
            val seconds = download + (if (linuxInstalled) 0 else 20) + packages.size * 30 + (if (packages.isEmpty()) 0 else 15)
            return InstallEstimate(download, disk, max(1, ceil(seconds / 60.0).toInt()))
        }
    }
}

/**
 * What the Workspace's Linux card shows: one state at a time, derived from Linux's status, the setup
 * run and a few facts. Pure, so every state and transition is tested on the JVM ([linuxPanel]).
 */
sealed interface LinuxPanel {
    /** This phone can't run it (no proot for its CPU). Nothing to install. */
    data class Unavailable(val reason: String) : LinuxPanel

    /** Not installed: offer Install with what it costs. */
    data class NotInstalled(val estimate: InstallEstimate) : LinuxPanel

    /**
     * An operation is running: [step] and [fraction] (null when unknown) for a [Meter], and whether
     * it can be cancelled (installs can; repair and remove are quick and can't).
     */
    data class Working(
        val operation: LinuxOperation,
        val step: String,
        val fraction: Float?,
        val cancellable: Boolean,
    ) : LinuxPanel

    /** Installed and usable. [killed] is set when Android killed one of its processes in the background. */
    data class Installed(
        val version: String,
        val diskBytes: Long?,
        val services: List<LinuxEnvironment.ServiceStatus>,
        val killed: AndroidKillNotice?,
    ) : LinuxPanel {
        val running: List<LinuxEnvironment.ServiceStatus> get() = services.filter { it.running }
    }

    /** The install failed, with why; Try again installs again. */
    data class Failed(val reason: String) : LinuxPanel
}

/** Facts about an installed system, read on IO by the screen. */
data class LinuxFacts(
    val version: String = "",
    val diskBytes: Long? = null,
    val services: List<LinuxEnvironment.ServiceStatus> = emptyList(),
    val killed: AndroidKillNotice? = null,
)

/**
 * The card's state. A running operation wins over the status (a reinstall passes through "not
 * installed"); an unavailable phone wins over everything, since nothing can run there.
 */
fun linuxPanel(
    status: LinuxStatus,
    setup: LinuxSetupState,
    facts: LinuxFacts,
    selected: Collection<LinuxPackage>,
): LinuxPanel = when {
    status is LinuxStatus.Unavailable -> LinuxPanel.Unavailable(status.reason)
    setup.running || status is LinuxStatus.Installing -> {
        val operation = setup.operation ?: LinuxOperation.Install
        val installing = status as? LinuxStatus.Installing
        LinuxPanel.Working(
            operation = operation,
            step = installing?.step ?: setup.phase ?: "Working…",
            fraction = installing?.fraction ?: setup.progress,
            cancellable = setup.cancellable,
        )
    }
    status == LinuxStatus.Ready -> LinuxPanel.Installed(facts.version, facts.diskBytes, facts.services, facts.killed)
    status is LinuxStatus.Failed -> LinuxPanel.Failed(status.message)
    // A run that failed before Linux reported anything (an unsupported CPU, a crash in setup).
    setup.error != null && !setup.cancelled -> LinuxPanel.Failed(setup.error)
    else -> LinuxPanel.NotInstalled(InstallEstimate.of(selected))
}
