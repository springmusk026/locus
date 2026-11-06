package dev.locus.feature.workspace

import dev.locus.core.markdown.files.PreviewKind
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.locus.core.linux.HealthLevel
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxInventory
import dev.locus.core.linux.LinuxReport
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.terminal.TerminalSessions
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.readyWithoutShell
import dev.locus.feature.workspace.linux.LinuxViewModel
import dev.locus.feature.workspace.linux.health.LinuxHealthRoute
import dev.locus.feature.workspace.linux.health.LinuxHealthSource
import dev.locus.feature.workspace.linux.health.LinuxHealthUiState
import dev.locus.feature.workspace.linux.health.LinuxHealthViewModel
import dev.locus.feature.workspace.linux.health.LinuxPackagesRoute
import dev.locus.feature.workspace.linux.health.LinuxPackagesSource
import dev.locus.feature.workspace.linux.health.LinuxPackagesUiState
import dev.locus.feature.workspace.linux.health.LinuxPackagesViewModel
import dev.locus.feature.workspace.preview.FileInfoCard
import dev.locus.feature.workspace.preview.OpenFile
import dev.locus.feature.workspace.terminal.TerminalRoute
import dev.locus.feature.workspace.terminal.TerminalViewModel
import java.io.File
import java.io.IOException
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The workspace, its file previews, the terminal's chrome and Linux health and packages (docs/design/design-system.md, "Review gate"). */
class WorkspaceScreenshots : ScreenshotTest() {

    @get:Rule
    val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val zone: TimeZone = TimeZone.getDefault()

    /** File info shows an absolute date: the same one on every machine. */
    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun tearDown() {
        scope.cancel()
        TimeZone.setDefault(zone)
    }

    private val hour = 3_600_000L
    private val mb = 1024L * 1024
    private val gb = 1024 * mb

    private fun <T> StateFlow<T>.await(until: (T) -> Boolean): T = runBlocking { withTimeout(10_000) { first(until) } }

    // ---- Fixtures

    /** A workspace after a few weeks: two folders and the files of a few tasks, modified hours apart. */
    private fun files(g: TestGraph): File {
        val root = g.workspace.resolve(".")
        fun put(path: String, text: String) = File(root, path).apply { parentFile?.mkdirs(); writeText(text) }
        put("projects/garden-app/README.md", "# Garden app\n")
        put("documents/invoices/2026-10/northwind-0042.pdf", "%PDF-1.4\n")
        put("trip-lisbon.md", TRIP_MD)
        put("budget-2026.csv", BUDGET_CSV)
        put("scrape_prices.py", SCRAPE_PY)
        put("package.json", PACKAGE_JSON)
        File(root, "site-backup.tar.gz").writeBytes(ByteArray(48_213) { (it * 31 % 251).toByte() })
        photo(File(root, "raised-beds.png"))
        root.listFiles()!!.sortedBy { it.name }.forEachIndexed { i, f -> f.setLastModified(System.currentTimeMillis() - (1 + 2 * i) * hour) }
        return root
    }

    /** A small PNG drawn here: three raised beds on grass. */
    private fun photo(file: File) {
        val bitmap = Bitmap.createBitmap(640, 420, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF7FA650.toInt())
        val soil = Paint().apply { color = 0xFF5B3A23.toInt() }
        val wood = Paint().apply { color = 0xFFB98A5A.toInt() }
        for (i in 0 until 3) {
            val left = 40f + i * 200f
            canvas.drawRect(left, 80f, left + 160f, 340f, wood)
            canvas.drawRect(left + 12f, 92f, left + 148f, 328f, soil)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun workspace(g: TestGraph): WorkspaceViewModel {
        val vm = WorkspaceViewModel(g.workspaceRepository, g.workspace, g.io)
        vm.uiState.await { !it.loading }
        return vm
    }

    private fun WorkspaceViewModel.openFile(name: String) {
        val entry = uiState.value.entries.first { it.name == name }
        open(entry)
        uiState.await { it.openFile?.name == name }
    }

    private fun linuxCard(g: TestGraph) = LinuxViewModel(g.linuxSetup, g.linux) {}

    private fun screen(vm: WorkspaceViewModel, linux: LinuxViewModel): @androidx.compose.runtime.Composable () -> Unit = {
        WithViewModels(linux) { Settled { WorkspaceRoute(onBack = {}, onOpenHealth = {}, viewModel = vm) } }
    }

    // ---- Workspace

    @Test
    fun root() {
        val g = TestGraph(scope)
        files(g)
        snap("workspace", heightDp = 1500, content = screen(workspace(g), linuxCard(g)))
    }

    @Test
    fun rootEmpty() {
        val g = TestGraph(scope)
        snap("workspace-empty", heightDp = 1200, content = screen(workspace(g), linuxCard(g)))
    }

    @Test
    fun rootLinuxInstalled() {
        val g = TestGraph(scope)
        files(g)
        // Linux counts as installed; its commands answer nothing, as on a fresh install.
        g.linux.runOnHost { _, _ -> ProcessBuilder("/bin/sh", "-c", "exit 0") }
        val linux = linuxCard(g)
        linux.uiState.await { it.panel is dev.locus.core.data.linux.LinuxPanel.Installed }
        snap("workspace-linux-installed", heightDp = 1500, content = screen(workspace(g), linux))
    }

    /** Home's Recent files: the Workspace opened on one file, in the same viewer a tap on its row opens. */
    @Test
    fun openedFromHome() {
        val g = TestGraph(scope)
        files(g)
        val vm = workspace(g)
        vm.openFromOutside("trip-lisbon.md")
        vm.uiState.await { it.openFile?.path == "trip-lisbon.md" }
        snap("workspace-opened-from-home", content = {
            WithViewModels(linuxCard(g)) { Settled { WorkspaceRoute(onBack = {}, onOpenHealth = {}, openPath = "trip-lisbon.md", viewModel = vm) } }
        })
    }

    /**
     * Open on a page a chat sent: the Workspace's viewer on it. The page loads its styles from a CDN, which
     * this viewer never fetches, so a notice says so and points to Open with… in the menu.
     */
    @Test
    fun openedFromChatWebPage() {
        val g = TestGraph(scope)
        val root = files(g)
        File(root, "out/create-invoice.html").apply {
            parentFile?.mkdirs()
            writeText("<!doctype html><link rel=\"stylesheet\" href=\"https://cdn.example.com/invoice.css\"><h1>INVOICE</h1><p>Northwind · \$1,440.00</p>")
        }
        val vm = workspace(g)
        vm.openFromOutside("out/create-invoice.html")
        vm.uiState.await { it.openFile?.path == "out/create-invoice.html" }
        snap("workspace-opened-from-chat-web", content = {
            WithViewModels(linuxCard(g)) { Settled { WorkspaceRoute(onBack = {}, onOpenHealth = {}, openPath = "out/create-invoice.html", viewModel = vm) } }
        })
    }

    /** A recent file that was moved or deleted since Home listed it: the list, and a message saying so. */
    @Test
    fun openedFromHomeGone() {
        val g = TestGraph(scope)
        files(g)
        // A view model per variant: the message is shown once, then cleared, as in the app.
        for (variant in dev.locus.core.screenshot.Variant.entries) {
            val vm = workspace(g)
            snap("workspace-opened-from-home-gone", heightDp = 1500, variants = listOf(variant), content = {
                WithViewModels(linuxCard(g)) { Settled { WorkspaceRoute(onBack = {}, onOpenHealth = {}, openPath = "old/report.pdf", viewModel = vm) } }
            })
        }
    }

    @Test
    fun folder() {
        val g = TestGraph(scope)
        files(g)
        val vm = workspace(g)
        vm.open(vm.uiState.value.entries.first { it.name == "documents" })
        vm.uiState.await { it.folder == "documents" && !it.loading && it.entries.isNotEmpty() }
        snap("workspace-folder", content = screen(vm, linuxCard(g)))
    }

    @Test
    fun folderEmpty() {
        val g = TestGraph(scope)
        File(g.workspace.resolve("."), "exports").mkdirs()
        val vm = workspace(g)
        vm.open(vm.uiState.value.entries.first { it.name == "exports" })
        vm.uiState.await { it.folder == "exports" && !it.loading }
        snap("workspace-folder-empty", content = screen(vm, linuxCard(g)))
    }

    @Test
    fun trash() {
        val g = TestGraph(scope)
        files(g)
        val vm = workspace(g)
        runBlocking {
            vm.delete("scrape_prices.py").join()
            vm.delete("budget-2026.csv").join()
        }
        vm.messageShown()
        vm.showTrash(true)
        vm.uiState.await { it.trash.size == 2 && it.message == null }
        snap("workspace-trash", content = screen(vm, linuxCard(g)))
    }

    @Test
    fun trashEmpty() {
        val g = TestGraph(scope)
        val vm = workspace(g)
        vm.showTrash(true)
        snap("workspace-trash-empty", content = screen(vm, linuxCard(g)))
    }

    // ---- File previews

    @Test
    fun previewMarkdown() {
        val g = TestGraph(scope)
        files(g)
        val vm = workspace(g)
        vm.openFile("trip-lisbon.md")
        snap("workspace-preview-markdown", heightDp = 1200, content = screen(vm, linuxCard(g)))
    }

    @Test
    fun previewCode() {
        val g = TestGraph(scope)
        files(g)
        val vm = workspace(g)
        vm.openFile("scrape_prices.py")
        snap("workspace-preview-code", content = screen(vm, linuxCard(g)))
    }

    @Test
    fun previewJson() {
        val g = TestGraph(scope)
        files(g)
        val vm = workspace(g)
        vm.openFile("package.json")
        snap("workspace-preview-json", content = screen(vm, linuxCard(g)))
    }

    @Test
    fun previewTable() {
        val g = TestGraph(scope)
        files(g)
        val vm = workspace(g)
        vm.openFile("budget-2026.csv")
        snap("workspace-preview-table", content = screen(vm, linuxCard(g)))
    }

    @Test
    fun previewImage() {
        val g = TestGraph(scope)
        files(g)
        val vm = workspace(g)
        vm.openFile("raised-beds.png")
        snap("workspace-preview-image", content = screen(vm, linuxCard(g)))
    }

    @Test
    fun previewBinary() = snap("workspace-preview-file-info") {
        val file = File("site-backup.tar.gz")
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FileInfoCard(OpenFile("site-backup.tar.gz", file, size = 48_213_907, modified = 1_793_000_000_000L, kind = PreviewKind.Binary), onExport = {})
        }
    }

    // ---- Terminal (its tabs hold a native terminal view, which only draws on a device)

    @Test
    fun terminalNoLinux() {
        val g = TestGraph(scope)
        val vm = TerminalViewModel(TerminalSessions(androidx.test.core.app.ApplicationProvider.getApplicationContext(), g.linux))
        snap("terminal-no-linux") { TerminalRoute(onBack = {}, onAsk = {}, onInstallLinux = {}, viewModel = vm) }
    }

    // ---- Linux health

    private class FakeHealth(var report: LinuxReport?, var failWith: Exception? = null, val hang: Boolean = false) : LinuxHealthSource {
        override suspend fun report(): LinuxReport? {
            if (hang) awaitCancellation()
            failWith?.let { throw it }
            return report
        }
        override suspend fun network() = LinuxReport.Network(dnsOk = true, httpsOk = true, millis = 180)
        override suspend fun cleanCaches(): Long = 0
    }

    private val packages = listOf(
        LinuxInventory.Package("alpine-baselayout", "3.6.8-r1", "Alpine base dir structure and init scripts", 12_288, "aarch64", explicit = true),
        LinuxInventory.Package("busybox", "1.37.0-r31", "Size optimized toolbox of many common UNIX utilities", 819_200, "aarch64", explicit = true),
        LinuxInventory.Package("python3", "3.12.12-r0", "High-level scripting language", 52_428_800, "aarch64", explicit = true),
        LinuxInventory.Package("py3-pip", "24.3.1-r0", "Tool for installing and managing Python packages", 11_534_336, "aarch64", explicit = true),
        LinuxInventory.Package("git", "2.49.1-r0", "Distributed version control system", 16_777_216, "aarch64", explicit = true),
        LinuxInventory.Package("nodejs", "22.16.0-r2", "JavaScript runtime built on V8 engine - LTS version", 44_040_192, "aarch64", explicit = true),
        LinuxInventory.Package("npm", "11.3.0-r1", "The package manager for JavaScript", 9_437_184, "aarch64", explicit = true),
        LinuxInventory.Package("poppler-utils", "25.04.0-r0", "PDF rendering library (utilities)", 1_048_576, "aarch64", explicit = true),
        LinuxInventory.Package("libgcc", "14.2.0-r6", "GNU C compiler runtime libraries", 155_648, "aarch64"),
        LinuxInventory.Package("openssl", "3.5.4-r0", "Toolkit for Transport Layer Security (TLS)", 1_769_472, "aarch64"),
    )

    private fun service(name: String, command: String, running: Boolean, exit: Int? = null, autostart: Boolean = false) =
        LinuxEnvironment.ServiceStatus(name, command, running, lostOnRestart = false, startedAt = System.currentTimeMillis() - 2 * hour, exitCode = exit, autostart = autostart)

    private fun healthy() = LinuxReport(
        takenAt = System.currentTimeMillis() - 3 * 60_000,
        alpineVersion = "3.22.2",
        arch = "aarch64",
        runtime = LinuxReport.Runtime(prootVersion = "5.4.0"),
        roundTrip = LinuxReport.RoundTrip(ok = true, millis = 140),
        packages = packages,
        storage = LinuxReport.Storage(
            linuxBytes = 612 * mb,
            phoneFreeBytes = 41 * gb,
            phoneTotalBytes = 128 * gb,
            biggest = listOf(LinuxReport.Usage("/usr/lib/node_modules", 188 * mb), LinuxReport.Usage("/usr/lib/python3.12", 96 * mb), LinuxReport.Usage("/root/.cache/pip", 22 * mb)),
            caches = listOf(LinuxReport.Usage("/root/.cache/pip", 22 * mb), LinuxReport.Usage("/var/cache/apk", 9 * mb)),
        ),
        memory = LinuxReport.Memory(totalBytes = 8 * gb, availableBytes = 3 * gb),
        processes = listOf(LinuxReport.GuestProcess(4123, "node", 96 * mb), LinuxReport.GuestProcess(4188, "python3", 41 * mb), LinuxReport.GuestProcess(3990, "sh", 2 * mb)),
        services = listOf(service("docs-site", "npx serve -l 8080 site/", running = true, autostart = true)),
    )

    private fun troubled() = healthy().copy(
        roundTrip = LinuxReport.RoundTrip(ok = true, millis = 4_800),
        storage = healthy().storage?.copy(phoneFreeBytes = 1_400 * mb, caches = listOf(LinuxReport.Usage("/root/.cache/pip", 640 * mb), LinuxReport.Usage("/var/cache/apk", 210 * mb))),
        memory = LinuxReport.Memory(totalBytes = 6 * gb, availableBytes = 310 * mb, lowMemory = true, thresholdBytes = 400 * mb),
        services = listOf(
            service("docs-site", "npx serve -l 8080 site/", running = true, autostart = true),
            service("price-bot", "python3 scrape_prices.py --every 1h", running = false, exit = 1),
        ),
        phantom = LinuxReport.Phantom(applies = true, monitorOff = false, developerOptionsOn = true),
    )

    private fun health(g: TestGraph, source: LinuxHealthSource, until: (LinuxHealthUiState) -> Boolean): LinuxHealthViewModel {
        val vm = LinuxHealthViewModel(g.linux, g.linuxSetup, source) {}
        vm.uiState.await(until)
        return vm
    }

    private fun healthScreen(vm: LinuxHealthViewModel): @androidx.compose.runtime.Composable () -> Unit = {
        LinuxHealthRoute(onBack = {}, onOpenTerminal = {}, onSetUpLinux = {}, onOpenPackages = {}, viewModel = vm)
    }

    @Test
    fun healthHealthy() {
        val g = TestGraph(scope)
        g.linux.readyWithoutShell()
        val vm = health(g, FakeHealth(healthy())) { it.report != null && !it.loading }
        snap("linux-health", heightDp = 2000, content = healthScreen(vm))
    }

    @Test
    fun healthProblems() {
        val g = TestGraph(scope)
        g.linux.readyWithoutShell()
        val vm = health(g, FakeHealth(troubled())) { s -> s.report != null && !s.loading && s.checks.any { it.level == HealthLevel.Problem } }
        snap("linux-health-problems", heightDp = 2400, content = healthScreen(vm))
    }

    @Test
    fun healthBroken() {
        val g = TestGraph(scope)
        g.linux.readyWithoutShell()
        val broken = healthy().copy(
            roundTrip = LinuxReport.RoundTrip(ok = false, millis = 30_000, timedOut = true),
            integrity = LinuxReport.Integrity(missing = listOf("/bin/sh", "/etc/apk/world", "/lib/ld-musl-aarch64.so.1"), apkDbReadable = false),
        )
        val vm = health(g, FakeHealth(broken)) { it.report != null && !it.loading }
        snap("linux-health-broken", heightDp = 2000, content = healthScreen(vm))
    }

    @Test
    fun healthLoading() {
        val g = TestGraph(scope)
        g.linux.readyWithoutShell()
        val vm = health(g, FakeHealth(null, hang = true)) { it.loading }
        snap("linux-health-loading", content = healthScreen(vm))
    }

    @Test
    fun healthError() {
        val g = TestGraph(scope)
        g.linux.readyWithoutShell()
        val vm = health(g, FakeHealth(null, failWith = IOException("proot exited with code 255 before the probe finished"))) { it.error != null && !it.loading }
        snap("linux-health-error", content = healthScreen(vm))
    }

    @Test
    fun healthNotInstalled() {
        val g = TestGraph(scope)
        val vm = health(g, FakeHealth(healthy())) { true }
        snap("linux-health-not-installed", content = healthScreen(vm))
    }

    @Test
    fun phantomHelp() = snap("linux-sheet-phantom-help", heightDp = 900) {
        CallPrivate(HEALTH, "PhantomHelpSheet", LinuxReport.Phantom(applies = true, monitorOff = false, developerOptionsOn = false), {})
    }

    @Test
    fun phantomHelpUnknown() = snap("linux-sheet-phantom-help-unknown", heightDp = 900) {
        CallPrivate(HEALTH, "PhantomHelpSheet", null, {})
    }

    // ---- Linux packages

    private class FakePackages(val packages: List<LinuxInventory.Package>?, val upgrades: List<LinuxInventory.Upgrade> = emptyList()) : LinuxPackagesSource {
        override suspend fun packages() = packages
        override suspend fun upgrades() = upgrades
        override suspend fun uninstall(name: String) = Unit
    }

    private fun packagesVm(g: TestGraph, source: LinuxPackagesSource, until: (LinuxPackagesUiState) -> Boolean): LinuxPackagesViewModel {
        val vm = LinuxPackagesViewModel(g.linux, source, filtering = Dispatchers.Unconfined)
        vm.uiState.await(until)
        return vm
    }

    private fun packagesScreen(vm: LinuxPackagesViewModel): @androidx.compose.runtime.Composable () -> Unit = {
        LinuxPackagesRoute(onBack = {}, onOpenTerminal = {}, onSetUpLinux = {}, viewModel = vm)
    }

    @Test
    fun packagesMine() {
        val g = TestGraph(scope)
        g.linux.readyWithoutShell()
        val vm = packagesVm(g, FakePackages(packages)) { it.packages != null && !it.loading }
        snap("linux-packages", heightDp = 1200, content = packagesScreen(vm))
    }

    @Test
    fun packagesAllWithUpdates() {
        val g = TestGraph(scope)
        g.linux.readyWithoutShell()
        val upgrades = listOf(
            LinuxInventory.Upgrade("nodejs", "22.16.0-r2", "22.18.0-r0"),
            LinuxInventory.Upgrade("openssl", "3.5.4-r0", "3.5.5-r0"),
        )
        val vm = packagesVm(g, FakePackages(packages, upgrades)) { it.packages != null && !it.loading }
        vm.showAll(true)
        vm.checkUpgrades()
        vm.uiState.await { it.all && it.upgrades != null && !it.checkingUpgrades }
        snap("linux-packages-all-updates", heightDp = 1500, content = packagesScreen(vm))
    }

    @Test
    fun packagesSearchNoMatch() {
        val g = TestGraph(scope)
        g.linux.readyWithoutShell()
        val vm = packagesVm(g, FakePackages(packages)) { it.packages != null && !it.loading }
        vm.setQuery("postgresql")
        vm.uiState.await { it.query == "postgresql" }
        snap("linux-packages-no-match", content = packagesScreen(vm))
    }

    @Test
    fun packagesNotInstalled() {
        val g = TestGraph(scope)
        val vm = packagesVm(g, FakePackages(null)) { true }
        snap("linux-packages-not-installed", content = packagesScreen(vm))
    }

    private companion object {
        const val HEALTH = "dev.locus.feature.workspace.linux.health.LinuxHealthScreenKt"

        val TRIP_MD = """
            # Lisbon, 12–19 March

            ## Getting there
            - **Out:** TP 1351, KTM → LIS via DOH, lands 14:05
            - **Back:** TP 1352, 19 March, leaves 16:40

            ## Where to stay
            | Area | Price / night | Why |
            |---|---|---|
            | Alfama | €120 | Walkable, close to the castle |
            | Príncipe Real | €145 | Quieter, good cafés |

            ## To book
            1. Belém tower tickets (timed entry)
            2. Fado dinner in Mouraria, Friday
            3. Day trip to Sintra: train from Rossio, 40 min

            > Pack a light rain jacket: March averages 9 rainy days.
        """.trimIndent()

        val BUDGET_CSV = """
            Month,Rent,Groceries,Transport,Utilities,Savings
            January,1200,410,95,140,600
            February,1200,385,90,155,600
            March,1200,402,110,138,650
            April,1200,398,88,120,650
            May,1250,415,92,110,700
            June,1250,430,105,98,700
        """.trimIndent()

        val SCRAPE_PY = """
            import json
            import sys
            from urllib.request import Request, urlopen

            STORES = {
                "north": "https://shop.example-north.com/api/products/",
                "harbour": "https://harbour-market.example.com/p/",
            }


            def price(store: str, sku: str) -> float:
                req = Request(STORES[store] + sku, headers={"User-Agent": "locus-price-watch/1.0"})
                with urlopen(req, timeout=10) as res:
                    return float(json.load(res)["price"])


            if __name__ == "__main__":
                sku = sys.argv[1]
                for store in STORES:
                    print(f"{store:>8}: {price(store, sku):8.2f}")
        """.trimIndent()

        val PACKAGE_JSON = """{"name":"portfolio-site","version":"1.4.2","private":true,"scripts":{"dev":"vite","build":"vite build","preview":"vite preview --port 4173"},"dependencies":{"@fontsource/inter":"5.2.5"},"devDependencies":{"vite":"7.1.4"}}"""
    }
}
