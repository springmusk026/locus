package dev.locus.core.data.updates

import android.content.pm.PackageInstaller
import androidx.work.NetworkType
import dev.locus.core.testing.FakeUpdatePlatform
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The update check end to end over a scripted GitHub: ETag, rate limit, skip, notify once, download, verify, install. */
class UpdatesTest {
    private val fixture = javaClass.getResource("/updates/latest-release.json")!!.readText()
    private val apkBytes = "pretend this is an APK".toByteArray()
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val now = 1_800_000_000_000L

    /** The fixture as version [version], with the arm64 APK's checksum [sha] listed in SHA256SUMS.txt (and no GitHub digest). */
    private fun releaseJson(version: String = "0.1.5", prerelease: Boolean = false) =
        fixture.replace("0.1.5", version).replace("\"prerelease\": false", "\"prerelease\": $prerelease")
            .replace("\"sha256:" + "a".repeat(64) + "\"", "null")

    private fun TestScope.graph() = TestGraph(backgroundScope, dispatcher = UnconfinedTestDispatcher(testScheduler))

    /** GitHub as [latest] describes it, the checksum file listing [sha] for the arm64 APK, and the APK itself. */
    private fun TestGraph.github(latest: () -> String = { releaseJson() }, etag: String = "\"e1\"", sha: String = apkSha) {
        http = { req ->
            val url = req.url.toString()
            when {
                url == ReleaseSource.LATEST_URL && req.headers[HttpHeaders.IfNoneMatch] == etag -> respond("", HttpStatusCode.NotModified)
                url == ReleaseSource.LATEST_URL -> respond(latest(), HttpStatusCode.OK, headersOf(HttpHeaders.ETag, etag))
                url.endsWith("SHA256SUMS.txt") -> respond("$sha  ${url.substringAfter("download/").substringBefore('/').let { "locus-$it-arm64-v8a.apk" }}\n", HttpStatusCode.OK)
                url.endsWith("-arm64-v8a.apk") -> respond(apkBytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, apkBytes.size.toString()))
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
    }

    // ---- Checking ----

    @Test fun `a newer release is offered, and the request carries nothing but GitHub's headers`() = runTest {
        val g = graph().apply { github() }
        val state = g.updates(now = { now }).check(manual = true)
        assertIs<UpdateState.Available>(state)
        assertEquals("0.1.5", state.release.version)
        assertFalse(state.skipped)
        val req = g.requests.single()
        assertEquals(ReleaseSource.LATEST_URL, req.url.toString())
        assertEquals("application/vnd.github+json", req.headers[HttpHeaders.Accept])
        assertEquals(ReleaseSource.USER_AGENT, req.headers[HttpHeaders.UserAgent])
        assertNull(req.headers[HttpHeaders.Authorization])
        assertNull(req.headers[HttpHeaders.Cookie])
        assertNull(req.headers[HttpHeaders.IfNoneMatch])
        assertEquals(now, g.updateCache.value.checkedAt)
    }

    @Test fun `the ETag is sent next time, and a 304 reuses the release already fetched`() = runTest {
        val g = graph().apply { github() }
        var t = now
        val updates = g.updates(now = { t })
        updates.check(manual = true)
        assertEquals("\"e1\"", g.updateCache.value.etag)
        t += 1000
        val again = updates.check(manual = true)
        assertEquals("\"e1\"", g.requests.last().headers[HttpHeaders.IfNoneMatch])
        assertIs<UpdateState.Available>(again)
        assertEquals("0.1.5", again.release.version)
        assertEquals(t, g.updateCache.value.checkedAt)
    }

    @Test fun `no ETag is sent when there's no release to fall back on`() = runTest {
        val g = graph().apply { github() }
        g.updateCache.update { it.copy(etag = "\"stale\"") }
        g.updates().check(manual = true)
        assertNull(g.requests.single().headers[HttpHeaders.IfNoneMatch])
    }

    @Test fun `the same version, an older one or none at all is up to date`() = runTest {
        val g = graph()
        var body = releaseJson("0.1.4")
        g.github(latest = { body })
        val updates = g.updates()
        assertEquals(UpdateState.UpToDate("0.1.4"), updates.check(manual = true))
        body = releaseJson("0.1.3")
        g.updateCache.update { it.copy(etag = null) }
        assertEquals(UpdateState.UpToDate("0.1.3"), updates.check(manual = true))
        g.http = { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }
        assertEquals(UpdateState.UpToDate(null), updates.check(manual = true))
    }

    @Test fun `a prerelease or draft is never offered`() = runTest {
        val g = graph().apply { github(latest = { releaseJson("0.2.0-beta.1", prerelease = true) }) }
        assertIs<UpdateState.UpToDate>(g.updates().check(manual = true))
        g.github(latest = { releaseJson("0.2.0").replace("\"draft\": false", "\"draft\": true") }, etag = "\"e2\"")
        g.updateCache.update { it.copy(etag = null) }
        assertIs<UpdateState.UpToDate>(g.updates().check(manual = true))
    }

    @Test fun `a used-up rate limit is respected until its reset, without asking again`() = runTest {
        val g = graph()
        val reset = now / 1000 + 600
        g.http = { respond("{}", HttpStatusCode.Forbidden, headersOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("$reset"))) }
        var t = now
        val updates = g.updates(now = { t })
        val first = updates.check(manual = true)
        assertEquals(UpdateState.Failed(UpdateProblem.RateLimited(reset * 1000), null), first)
        assertEquals(1, g.requests.size)
        t += 60_000
        assertIs<UpdateState.Failed>(updates.check(manual = true))
        assertEquals(1, g.requests.size) // not asked again before the reset
        g.github()
        t = reset * 1000 + 1
        assertIs<UpdateState.Available>(updates.check(manual = true))
        assertEquals(0, g.updateCache.value.rateLimitedUntil)
    }

    @Test fun `rate limit headers are read as GitHub documents them`() {
        val source = ReleaseSource(io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { respond("") }), now = { now })
        assertEquals(now + 30_000, source.rateLimit(429, "30", null, null))
        assertEquals(1_900_000_000_000, source.rateLimit(403, null, "0", "1900000000"))
        assertEquals(now + 60_000, source.rateLimit(429, null, null, null))
        assertNull(source.rateLimit(200, null, "59", "1900000000"))
        assertNull(source.rateLimit(403, null, "12", null)) // a 403 that isn't the rate limit
    }

    @Test fun `no network and server errors are failures to show, not crashes`() = runTest {
        val g = graph()
        g.http = { throw IOException("Unable to resolve host") }
        assertEquals(UpdateState.Failed(UpdateProblem.NoNetwork, null), g.updates().check(manual = true))
        g.http = { respond("oops", HttpStatusCode.BadGateway) }
        assertEquals(UpdateState.Failed(UpdateProblem.Server(502), null), g.updates().check(manual = true))
        g.http = { respond("<html>", HttpStatusCode.OK) }
        assertEquals(UpdateState.Failed(UpdateProblem.Server(0), null), g.updates().check(manual = true))
    }

    // ---- Consent and scheduling ----

    @Test fun `the daily check does nothing while the setting is off, but Check now works`() = runTest {
        val g = graph().apply { github() }
        val updates = g.updates()
        updates.checkInBackground()
        assertTrue(g.requests.isEmpty())
        assertTrue(g.updatePlatform.notified.isEmpty())
        assertIs<UpdateState.Available>(updates.check(manual = true))
    }

    @Test fun `turning it on schedules the daily check, off cancels it, and start follows the setting`() = runTest {
        val g = graph()
        val updates = g.updates()
        assertFalse(updates.enabled.first())
        updates.setEnabled(true)
        assertTrue(g.updateWork.scheduled)
        assertTrue(g.settingsRepository.current().updateCheck)
        assertTrue(updates.enabled.first())
        updates.setEnabled(false)
        assertFalse(g.updateWork.scheduled)
        g.settingsRepository.setUpdateCheck(true) // e.g. restored from a backup
        updates.syncSchedule()
        assertTrue(g.updateWork.scheduled)
    }

    @Test fun `the daily check runs about once a day, on unmetered networks only`() {
        val spec = UpdateSchedule.spec
        assertEquals(24, spec.intervalHours)
        assertEquals(NetworkType.UNMETERED, spec.network)
        assertTrue(spec.batteryNotLow)
        val c = UpdateSchedule.constraints()
        assertEquals(NetworkType.UNMETERED, c.requiredNetworkType)
        assertTrue(c.requiresBatteryNotLow())
        val request = UpdateSchedule.request()
        @Suppress("RestrictedApi")
        val work = request.workSpec
        assertEquals(TimeUnit.HOURS.toMillis(24), work.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(4), work.flexDuration)
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        assertEquals(UpdateCheckWorker::class.java.name, work.workerClassName)
    }

    @Test fun `builds for stores, without the flag, or installed by a store never schedule`() = runTest {
        val g = graph()
        val off = g.updates(build = FakeUpdatePlatform.RELEASE_BUILD.copy(enabled = false))
        assertEquals(UpdateAvailability.DisabledInBuild, off.availability)
        off.setEnabled(true)
        assertFalse(g.updateWork.scheduled)
        assertFalse(g.settingsRepository.current().updateCheck)
        assertEquals(off.check(manual = true), UpdateState.Idle)
        assertTrue(g.requests.isEmpty())

        g.updatePlatform.store = "F-Droid"
        val store = g.updates()
        assertEquals(UpdateAvailability.FromStore("F-Droid"), store.availability)
        g.settingsRepository.setUpdateCheck(true)
        store.syncSchedule()
        assertFalse(g.updateWork.scheduled)
        assertFalse(store.enabled.first())
    }

    @Test fun `a debug build can look but not download`() = runTest {
        val g = graph().apply { github() }
        val debug = g.updates(build = FakeUpdatePlatform.RELEASE_BUILD.copy(debug = true))
        assertEquals(UpdateAvailability.DebugBuild, debug.availability)
        assertFalse(debug.availability.canSchedule)
        assertIs<UpdateState.Available>(debug.check(manual = true))
        debug.download()
        assertIs<UpdateState.Available>(debug.state.value)
        assertEquals(1, g.requests.size)
    }

    // ---- Notify once, skip ----

    @Test fun `a new version notifies once, however often the check runs`() = runTest {
        val g = graph().apply { github() }
        val updates = g.updates()
        updates.setEnabled(true)
        updates.checkInBackground()
        updates.checkInBackground()
        g.updateCache.update { it.copy(etag = null) }
        updates.checkInBackground()
        assertEquals(listOf("0.1.5"), g.updatePlatform.notified)
        assertEquals("0.1.5", g.updateCache.value.notifiedVersion)
    }

    @Test fun `a skipped version neither notifies nor nags, and the next one does`() = runTest {
        val g = graph()
        var version = "0.1.5"
        g.http = { req -> if (req.url.toString() == ReleaseSource.LATEST_URL) respond(releaseJson(version), HttpStatusCode.OK) else respondError(HttpStatusCode.NotFound) }
        val updates = g.updates()
        updates.setEnabled(true)
        assertIs<UpdateState.Available>(updates.check(manual = true))
        updates.skip("0.1.5")
        assertEquals(UpdateState.Available(updates.state.value.let { (it as UpdateState.Available).release }, skipped = true), updates.state.value)
        assertTrue(g.updatePlatform.cleared > 0)
        updates.checkInBackground()
        assertTrue(g.updatePlatform.notified.isEmpty())
        assertTrue((updates.state.value as UpdateState.Available).skipped)

        version = "0.1.6"
        updates.checkInBackground()
        assertEquals(listOf("0.1.6"), g.updatePlatform.notified)
        assertFalse((updates.state.value as UpdateState.Available).skipped)
    }

    @Test fun `unskipping offers the version again`() = runTest {
        val g = graph().apply { github() }
        val updates = g.updates()
        updates.check(manual = true)
        updates.skip("0.1.5")
        updates.unskip()
        assertFalse((updates.state.value as UpdateState.Available).skipped)
        assertNull(g.updateCache.value.skippedVersion)
    }

    @Test fun `after a restart the last answer shows without asking GitHub`() = runTest {
        val g = graph().apply { github() }
        g.updates().check(manual = true)
        val requests = g.requests.size
        val restarted = g.updates()
        restarted.restore()
        assertIs<UpdateState.Available>(restarted.state.value)
        assertEquals(requests, g.requests.size)
    }

    // ---- Download, verify, install ----

    private suspend fun TestGraph.available(): Updates {
        val updates = updates()
        updates.check(manual = true)
        return updates
    }

    @Test fun `the phone's APK is downloaded, its checksum and signer checked, and handed to the installer`() = runTest {
        val g = graph().apply { github() }
        val updates = g.available()
        val release = (updates.state.value as UpdateState.Available).release
        updates.runDownload(release)
        assertEquals(UpdateState.ReadyToInstall(release), updates.state.value)
        assertTrue(g.requests.any { it.url.toString().endsWith("SHA256SUMS.txt") })
        assertTrue(g.requests.none { it.url.toString().endsWith("x86_64.apk") })
        assertTrue(updates.hasDownload(release))
        updates.install()
        assertEquals(UpdateState.Installing(release), updates.state.value)
        assertEquals("locus-v0.1.5-arm64-v8a.apk", g.updatePlatform.installs.single().name)
        updates.onInstallResult(PackageInstaller.STATUS_SUCCESS, null)
        assertEquals(UpdateState.UpToDate("0.1.5"), updates.state.value)
        assertFalse(updates.hasDownload(release))
    }

    @Test fun `a checksum mismatch deletes the file and says so`() = runTest {
        val g = graph().apply { github(sha = "0".repeat(64)) }
        val updates = g.available()
        val release = (updates.state.value as UpdateState.Available).release
        updates.runDownload(release)
        assertEquals(UpdateState.Failed(UpdateProblem.ChecksumMismatch, release), updates.state.value)
        assertTrue(g.updatePlatform.downloadDir.listFiles().orEmpty().isEmpty())
        assertTrue(g.updatePlatform.installs.isEmpty())
    }

    @Test fun `a release with no checksum for the APK isn't downloaded`() = runTest {
        val g = graph()
        val noSums = releaseJson().let { j -> ReleaseJson.parse(j)!!.let { r -> r.copy(assets = r.assets.filterNot { it.name == ReleaseFiles.CHECKSUMS }) } }
        g.http = { req -> if (req.url.toString() == ReleaseSource.LATEST_URL) respond(ReleaseJson.encode(noSums), HttpStatusCode.OK) else respond(apkBytes, HttpStatusCode.OK) }
        val updates = g.available()
        updates.runDownload(noSums)
        assertEquals(UpdateState.Failed(UpdateProblem.NoChecksum, noSums), updates.state.value)
        assertTrue(g.requests.none { it.url.toString().endsWith(".apk") })
    }

    @Test fun `an APK signed with another key is refused before Android sees it`() = runTest {
        val g = graph().apply { github() }
        g.updatePlatform.archiveFacts = { ApkCheck.Facts("dev.locus", 63, setOf("someone else")) }
        val updates = g.available()
        val release = (updates.state.value as UpdateState.Available).release
        updates.runDownload(release)
        assertEquals(UpdateState.Failed(UpdateProblem.SignatureMismatch, release), updates.state.value)
        assertFalse(updates.hasDownload(release))
    }

    @Test fun `a phone with no matching ABI is told so`() = runTest {
        val g = graph().apply { github() }
        g.updatePlatform.supportedAbis = listOf("armeabi-v7a")
        val updates = g.available()
        val release = (updates.state.value as UpdateState.Available).release
        updates.runDownload(release)
        assertEquals(UpdateState.Failed(UpdateProblem.NoApkForDevice(listOf("armeabi-v7a")), release), updates.state.value)
    }

    @Test fun `a dropped download is a network failure and leaves nothing behind`() = runTest {
        val g = graph().apply { github() }
        val updates = g.available()
        val release = (updates.state.value as UpdateState.Available).release
        g.http = { req -> if (req.url.toString().endsWith(".txt")) respond("$apkSha  locus-v0.1.5-arm64-v8a.apk", HttpStatusCode.OK) else throw IOException("Connection reset") }
        updates.runDownload(release)
        assertEquals(UpdateState.Failed(UpdateProblem.NoNetwork, release), updates.state.value)
        assertTrue(g.updatePlatform.downloadDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun `without Install unknown apps, Locus asks for it, and carries on once it's allowed`() = runTest {
        val g = graph().apply { github() }
        val updates = g.available()
        val release = (updates.state.value as UpdateState.Available).release
        updates.runDownload(release)
        g.updatePlatform.installAllowed = false
        updates.install()
        assertEquals(UpdateState.Failed(UpdateProblem.InstallNotAllowed, release), updates.state.value)
        assertTrue(g.updatePlatform.installs.isEmpty())
        g.updatePlatform.installAllowed = true
        updates.onReturnFromSettings()
        assertEquals(UpdateState.ReadyToInstall(release), updates.state.value)
    }

    @Test fun `the installer's refusals map to clear problems`() = runTest {
        val g = graph().apply { github() }
        val updates = g.available()
        val release = (updates.state.value as UpdateState.Available).release
        updates.runDownload(release)
        updates.install()
        updates.onInstallResult(PackageInstaller.STATUS_FAILURE_CONFLICT, "signatures do not match")
        assertEquals(UpdateState.Failed(UpdateProblem.SignatureMismatch, release), updates.state.value)
        updates.onInstallResult(PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertEquals(UpdateState.Failed(UpdateProblem.InstallCancelled, release), updates.state.value)
        updates.onInstallResult(PackageInstaller.STATUS_FAILURE_STORAGE, null)
        assertEquals(UpdateState.Failed(UpdateProblem.Storage, release), updates.state.value)
        updates.onInstallResult(PackageInstaller.STATUS_FAILURE_INVALID, "bad apk")
        assertEquals(UpdateState.Failed(UpdateProblem.InstallFailed("bad apk"), release), updates.state.value)
        // Dismissing goes back to the downloaded file, ready to try again.
        updates.dismissProblem()
        assertEquals(UpdateState.ReadyToInstall(release), updates.state.value)
    }

    @Test fun `cancelling a download goes back to the offer`() = runTest {
        val g = graph().apply { github() }
        val updates = g.available()
        val release = (updates.state.value as UpdateState.Available).release
        updates.cancelDownload()
        assertEquals(UpdateState.Available(release, skipped = false), updates.state.value)
    }
}
