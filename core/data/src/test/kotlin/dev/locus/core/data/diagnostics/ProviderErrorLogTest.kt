package dev.locus.core.data.diagnostics

import dev.locus.core.datastore.DiagnosticsLog
import dev.locus.core.llm.LlmException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderErrorLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val logcat = mutableListOf<String>()

    private fun errors(log: DiagnosticsLog) = ProviderErrorLog(log, { now }, { logcat += it })

    @Test
    fun `a hidden body goes to diagnostics and logcat, scrubbed, once per quiet period`() {
        val log = DiagnosticsLog(File(tmp.root, "d"))
        val body = "<html><head><title>504 Gateway Time-out</title></head><body>Authorization: Bearer sk-abcdefghijklmnopqrstuvwx</body></html>"
        val e = LlmException("OpenAI's gateway timed out (HTTP 504). This is usually temporary. Try again.", 504, retryable = true, rawBody = body)
        val recorder = errors(log)
        recorder.record("OpenAI", "gemini-3.8-flash-high", e)
        recorder.record("OpenAI", "gemini-3.8-flash-high", e) // a retry of the same failure
        val entry = log.entries().single()
        assertEquals("Model provider error: OpenAI HTTP 504", entry.title)
        assertTrue("504 Gateway Time-out" in entry.text)
        assertTrue("Shown in the chat: OpenAI's gateway timed out" in entry.text)
        assertFalse("sk-abcdefghijklmnopqrstuvwx" in entry.text)
        assertEquals(1, logcat.size)
        assertFalse("sk-abcdefghijklmnopqrstuvwx" in logcat.single())

        now += ProviderErrorLog.QUIET_MS
        recorder.record("OpenAI", "gemini-3.8-flash-high", e)
        assertEquals(2, log.entries().size)
    }

    @Test
    fun `nothing is recorded when the chat already shows the whole body, or there is none`() {
        val log = DiagnosticsLog(File(tmp.root, "d"))
        val recorder = errors(log)
        recorder.record("Groq", "m", LlmException("Groq returned HTTP 500: model is loading", 500, rawBody = "model is loading"))
        recorder.record("Groq", "m", LlmException("Groq had an internal error (HTTP 500).", 500, rawBody = null))
        assertTrue(log.entries().isEmpty())
        assertTrue(logcat.isEmpty())
    }
}
