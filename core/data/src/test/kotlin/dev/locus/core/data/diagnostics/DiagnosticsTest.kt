package dev.locus.core.data.diagnostics

import android.app.ApplicationExitInfo
import dev.locus.core.datastore.DiagnosticsLog
import dev.locus.core.datastore.SecretStore
import dev.locus.core.testing.InMemoryDataStore
import dev.locus.core.testing.TestContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The local crash and exit log: scrubbed reports, the handler chain, exit filtering, the app scope's handler. */
class DiagnosticsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun log() = DiagnosticsLog(File(tmp.root, "diagnostics"))

    private val apiKey = "sk-proj-abcdefghijklmnopqrstuvwx"

    @After
    fun resetWriteScrubber() {
        DiagnosticsLog.writeScrubber = { it }
    }

    @Test
    fun `a crash is written scrubbed, then passed to the previous handler`() {
        val log = log()
        var passedOn: Throwable? = null
        val recorder = CrashRecorder(log, { _, e -> passedOn = e }, "Locus test")
        val error = IllegalStateException(
            "request failed: Authorization: Bearer abcdefghijklmnop123 key=$apiKey password=hunter2hunter2",
            IOException("x-api-key: ant-secret-value-12345"),
        )

        recorder.uncaughtException(Thread.currentThread(), error)

        assertSame(error, passedOn)
        val entry = log.entries().single()
        assertEquals(DiagnosticsLog.Kind.CRASH, entry.kind)
        assertEquals("Crash: IllegalStateException", entry.title)
        val text = entry.text
        listOf("abcdefghijklmnop123", apiKey, "hunter2hunter2", "ant-secret-value-12345").forEach { assertFalse(it in text, "$it leaked: $text") }
        assertTrue("[secret]" in text)
        assertTrue("Locus test" in text)
        assertTrue("Caused by: java.io.IOException" in text)
        assertTrue("DiagnosticsTest" in text, "the stack trace is kept")
    }

    @Test
    fun `the previous handler still runs when the log can't be written`() {
        val blocked = File(tmp.root, "file-not-dir").apply { writeText("") }
        var passedOn = false
        CrashRecorder(DiagnosticsLog(blocked), { _, _ -> passedOn = true }, "").uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertTrue(passedOn)
    }

    @Test
    fun `Diagnostics hides the vault's values too, before showing or sharing`() = runTest {
        val log = log()
        // An exit description isn't run through the vault at write time; a saved key that isn't shaped like one.
        log.record(DiagnosticsLog.Kind.EXIT, "Native crash", "Android says: abort with token plainvaultvalue42 in the URL")
        val secrets = SecretStore(InMemoryDataStore(mapOf(SecretStore.connectorSecret("x") to "plainvaultvalue42")))
        val repo = DiagnosticsRepository(TestContext(tmp.newFolder("ctx")), log, secrets)

        repo.refresh()
        assertFalse("plainvaultvalue42" in repo.entries.value.single().text)
        val shared = repo.shareText()
        assertFalse("plainvaultvalue42" in shared)
        assertTrue("Native crash" in shared)

        repo.clear()
        assertTrue(log.entries().isEmpty())
    }

    @Test
    fun `only exits since the last check, and only the kinds worth showing`() {
        fun exit(at: Long, reason: Int) = ExitRecord(at, reason, "d", 100, null)
        val all = listOf(
            exit(50, ApplicationExitInfo.REASON_ANR),
            exit(150, ApplicationExitInfo.REASON_ANR),
            exit(160, ApplicationExitInfo.REASON_USER_REQUESTED),
            exit(170, ApplicationExitInfo.REASON_CRASH),
            exit(180, ApplicationExitInfo.REASON_CRASH_NATIVE),
            exit(190, ApplicationExitInfo.REASON_LOW_MEMORY),
        )
        assertEquals(listOf(150L, 180L, 190L), exitsToRecord(all, since = 100).map { it.timestamp })
        assertEquals("Not responding (ANR)", exitTitle(ApplicationExitInfo.REASON_ANR))
        assertEquals("Native crash", exitTitle(ApplicationExitInfo.REASON_CRASH_NATIVE))
    }

    @Test
    fun `the app scope records an uncaught error instead of ending the process`() = runTest {
        val recorded = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + dev.locus.core.data.di.appScopeErrors { recorded += it })
        scope.launch { error("recovery failed") }
        scope.launch { }
        advanceUntilIdle()
        assertEquals("recovery failed", recorded.single().message)
        // The scope is still usable.
        var ran = false
        scope.launch { ran = true }
        advanceUntilIdle()
        assertTrue(ran)
    }

    @Test
    fun `the log keeps the newest entries and its notices until dismissed`() {
        var now = 0L
        val log = DiagnosticsLog(File(tmp.root, "d2")) { ++now }
        repeat(35) { log.record(DiagnosticsLog.Kind.ERROR, "e$it", "x") }
        val entries = log.entries()
        assertEquals(30, entries.size)
        assertEquals("e34", entries.first().title)
        log.addNotice("keys reset")
        log.addNotice("keys reset")
        assertEquals(listOf("keys reset"), log.notices())
        log.clear()
        assertEquals(listOf("keys reset"), log.notices())
        log.dismissNotices()
        assertTrue(log.notices().isEmpty())
    }

    @Test
    fun `a crash report also loses private keys, JWTs, AWS ids and Telegram tokens`() {
        val log = log()
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----"
        val jwt = "eyJhbGciOiJIUzI1NiJ9" + ".eyJzdWIiOiIxMjM0NTY3ODkwIn0" + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV"
        val aws = "AKIA" + "IOSFODNN7EXAMPLE"
        val telegram = "123456789" + ":AAH" + "k3j4h5g6f7d8s9a0qwertyuiopasdfghjk"
        CrashRecorder(log, null, "Locus test").uncaughtException(Thread.currentThread(), IllegalStateException("failed: $pem $jwt $aws bot$telegram"))
        val text = log.entries().single().text
        listOf("MIIEvQIBADANBgkq", jwt, aws, telegram).forEach { assertFalse(it in text, "$it leaked: $text") }
    }

    @Test
    fun `a vault value never reaches the log file`() = runTest {
        val log = log()
        val secrets = SecretStore(InMemoryDataStore(mapOf(SecretStore.connectorSecret("x") to "plainvaultvalue42")))
        val repo = DiagnosticsRepository(TestContext(tmp.newFolder("ctx")), log, secrets)
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { repo.keepLogScrubbed() }
        // The collector installs the scrubber once the vault's values have come through its flows.
        repeat(100) {
            advanceUntilIdle()
            if (DiagnosticsLog.writeScrubber("plainvaultvalue42") != "plainvaultvalue42") return@repeat
            yield()
        }
        assertEquals("[secret]", DiagnosticsLog.writeScrubber("plainvaultvalue42"))

        // Every writer: a crash, a background error, an exit text, a provider's body.
        CrashRecorder(log, null, "env").uncaughtException(Thread.currentThread(), IllegalStateException("token plainvaultvalue42 failed"))
        repo.recordError(IllegalStateException("bad plainvaultvalue42"))
        log.record(DiagnosticsLog.Kind.EXIT, "Native crash plainvaultvalue42", "Android says: plainvaultvalue42")

        val files = File(tmp.root, "diagnostics").listFiles().orEmpty()
        assertEquals(3, files.size)
        files.forEach { assertFalse("plainvaultvalue42" in it.readText(), "${it.name} holds the vault value: ${it.readText()}") }
    }
}
