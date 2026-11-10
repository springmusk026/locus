package dev.locus.feature.chat

import dev.locus.core.agent.RunFailure
import dev.locus.core.llm.LlmFailure
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The error banner's Details sheet: every field named, and Copy holds only the failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FailureDetailsTest {
    private val failure = RunFailure(
        provider = "ChatGPT", model = "gpt-6.1-sol", httpStatus = null, errorCode = null, requestId = "req_42", responseId = "resp_1",
        at = 0, rawBody = """{"type":"response.failed","response":{"id":"resp_1","status":"failed","error":null}}""", kind = LlmFailure.TRANSIENT, occurrences = 2,
    )

    private fun reportRows(f: RunFailure, forReport: Boolean = false) = failureRows(f, forReport) { "T" }.map { it.inReport() }

    @Test fun everyFieldIsShownWithWhatItsAbsenceMeans() {
        val rows = reportRows(failure).toMap()
        assertEquals("ChatGPT", rows["Provider"])
        assertEquals("gpt-6.1-sol", rows["Model"])
        assertEquals("None (the stream had started)", rows["HTTP status"])
        assertEquals("None given", rows["Error code"])
        assertEquals("req_42", rows["Request id"])
        assertEquals("resp_1", rows["Response id"])
        assertEquals("T", rows["Time"])
        assertEquals("2", rows["Times in a row"])
    }

    @Test fun aGgufModelIsNamedWithItsIdBesideIt() {
        val phone = failure.copy(provider = "On this phone", model = "gguf:qwen3.5-0.8b-q4_k_m")
        val rows = reportRows(phone)
        assertEquals("Qwen3.5 0.8B", rows.toMap()["Model"])
        // The sheet never shows a gguf: key; Copy keeps it for a bug report.
        assertEquals(null, rows.toMap()["Model id"])
        assertTrue("Model id: gguf:qwen3.5-0.8b-q4_k_m" in failureReport(phone, "m") { "T" })
        // A hosted id that shortens differently keeps its exact id on screen.
        val hosted = reportRows(failure.copy(model = "anthropic/claude-sonnet-5-5-20260301"))
        assertEquals(listOf("Provider", "Model", "Model id"), hosted.take(3).map { it.first })
        assertEquals("anthropic/claude-sonnet-5-5-20260301", hosted.toMap()["Model id"])
        // A model whose id is already its name gets no second row.
        assertEquals(null, reportRows(failure).toMap()["Model id"])
    }

    @Test fun aCloudflareRayIsLabelledAsTheGatewaysId() {
        val rows = reportRows(failure.copy(requestId = "8f2a1c9e4b7d03a1-FRA")).toMap()
        assertEquals("8f2a1c9e4b7d03a1-FRA", rows["Gateway id"])
        assertEquals(null, rows["Request id"])
    }

    @Test fun copyHoldsTheMessageRowsAndPayloadOnly() {
        val report = failureReport(failure, "ChatGPT ended the response with an error and gave no reason (request id req_42).") { "T" }
        assertTrue(report.startsWith("Locus model error\nChatGPT ended the response"))
        assertTrue("Request id: req_42" in report)
        assertTrue("Provider's response:\n{\"type\":\"response.failed\"" in report)
        assertEquals(failureReport(failure.copy(rawBody = null), "m") { "T" }.contains("Provider's response"), false)
    }

    @Test fun theSheetShowsTheSameRowsInTheScreensLanguageWhileCopyKeepsEnglish() {
        val shown = failureRows(failure) { "T" }.map { it.shown(testResources) }
        // English here; the labels and the absence texts are resources, so other languages translate them.
        assertEquals(reportRows(failure), shown)
        assertEquals("None (the stream had started)", shown.toMap()["HTTP status"])
        assertEquals(
            listOf(FailureField.PROVIDER, FailureField.MODEL, FailureField.HTTP_STATUS, FailureField.ERROR_CODE, FailureField.REQUEST_ID, FailureField.RESPONSE_ID, FailureField.TIME, FailureField.TIMES),
            failureRows(failure) { "T" }.map { it.field },
        )
    }

    @Test fun aDroppedConnectionIsSaidInWordsAndIdsLeaveTheSentence() {
        assertEquals(
            "The connection dropped before the reply finished.",
            failureMessageInWords(testResources, dev.locus.core.agent.AgentLoop.CONNECTION_DROPPED + " (Software caused connection abort)"),
        )
        assertEquals("Rate limited.", failureMessageInWords(testResources, "Rate limited. (request id req_42)"))
    }

    @Test fun jsonPayloadIsIndented() {
        assertTrue("\n" in prettyFailureBody("""{"a":{"b":1}}"""))
        assertEquals("<html>", prettyFailureBody("<html>"))
    }
}
