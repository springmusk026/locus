package dev.locus.feature.chat

import dev.locus.core.agent.RunFailure
import dev.locus.core.llm.LlmFailure
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The error banner's Details sheet: every field named, and Copy holds only the failure. */
class FailureDetailsTest {
    private val failure = RunFailure(
        provider = "ChatGPT", model = "gpt-6.1-sol", httpStatus = null, errorCode = null, requestId = "req_42", responseId = "resp_1",
        at = 0, rawBody = """{"type":"response.failed","response":{"id":"resp_1","status":"failed","error":null}}""", kind = LlmFailure.TRANSIENT, occurrences = 2,
    )

    @Test fun everyFieldIsShownWithWhatItsAbsenceMeans() {
        val rows = failureRows(failure) { "T" }.toMap()
        assertEquals("ChatGPT", rows["Provider"])
        assertEquals("gpt-6.1-sol", rows["Model"])
        assertEquals("None (the stream had started)", rows["HTTP status"])
        assertEquals("None given", rows["Error code"])
        assertEquals("req_42", rows["Request id"])
        assertEquals("resp_1", rows["Response id"])
        assertEquals("T", rows["Time"])
        assertEquals("2", rows["Times in a row"])
    }

    @Test fun aCloudflareRayIsLabelledAsTheGatewaysId() {
        val rows = failureRows(failure.copy(requestId = "8f2a1c9e4b7d03a1-FRA")) { "T" }.toMap()
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

    @Test fun jsonPayloadIsIndented() {
        assertTrue("\n" in prettyFailureBody("""{"a":{"b":1}}"""))
        assertEquals("<html>", prettyFailureBody("<html>"))
    }
}
