package dev.locus.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The errors Locus words carry their message as data, and the data says exactly the message. */
class LlmErrorTextTest {
    @Test
    fun httpErrorsCarryTheirWords() {
        val bodies = listOf(
            429 to """{"error":{"message":"Spend cap","type":"rate_limit_error","details":{"error_code":"enforced_spend_limit_reached"}}}""",
            402 to "",
            429 to """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProject"}]}]}}""",
            502 to "<html><head><title>502 Bad Gateway</title></head></html>",
            404 to "",
            500 to """{"error":{"message":"boom"}}""",
            418 to "<html><head><title>teapot</title></head></html>",
            521 to "",
            599 to "",
        )
        bodies.forEach { (status, body) ->
            val e = httpError("Acme", status, body)
            val text = assertNotNull(e.text, "$status")
            assertEquals(e.message, text.english, "$status")
        }
    }

    @Test
    fun chatGptErrorsCarryTheirWords() {
        val codes = listOf(
            "usage_limit_reached", "usage_not_included", "subscription_sharing_usage_unavailable", "subscription_sharing_user_not_eligible",
            "subscription_sharing_unsupported_capability", "subscription_sharing_invalid_user", "subscription_sharing_route_not_supported",
            "server_is_overloaded", "server_error", "rate_limit_exceeded", "insufficient_quota", "something_new",
        )
        codes.forEach { code ->
            val e = ChatGptErrors.fromCode(code, null, resetsAt = 1_900_000_000)
            assertEquals(e.message, assertNotNull(e.text, code).english, code)
        }
        // The provider's own words are kept as they are, with no data to translate.
        assertNull(ChatGptErrors.fromCode("server_error", "Busy").text)
        listOf(401 to "", 401 to """{"detail":"expired"}""", 403 to "", 503 to "nope", 429 to "", 500 to "").forEach { (status, body) ->
            val e = ChatGptErrors.fromHttp(status, body)
            assertEquals(e.message, assertNotNull(e.text, "$status").english, "$status")
        }
        val withId = ChatGptErrors.fromCode(null, null, requestId = "req_9")
        assertEquals("ChatGPT ended the response with an error and gave no reason (request id req_9).", withId.text?.english)
    }

    @Test
    fun theWordsSurviveANewMessage() {
        val e = httpError("Acme", 503, "")
        assertEquals(e.text, e.withMessage(e.message + "\nmore").text)
        assertEquals(e.text, e.withIds("req").text)
    }
}
