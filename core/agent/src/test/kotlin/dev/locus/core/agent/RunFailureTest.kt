package dev.locus.core.agent

import dev.locus.core.llm.ChatGptErrors
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.LlmFailure
import dev.locus.core.llm.llmFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** How a failure that comes back on Try again is read, and what the banner then offers. */
class RunFailureTest {
    private val unexplained = ChatGptErrors.fromCode(null, null, requestId = "req_1")

    private fun of(error: LlmException?, previous: RunFailure? = null, mark: String? = "m9", context: Int? = 120_000, message: String = error?.message.orEmpty()) =
        RunFailure.of(error, message, "ChatGPT", "gpt-6.1-sol", 1_000, previous, mark, rawBody = error?.rawBody, contextTokens = context, contextWindow = 1_050_000)

    @Test
    fun `a code-less failure seen once is transient and offers only details`() {
        val first = of(unexplained)
        assertEquals(1, first.occurrences)
        assertEquals(LikelyCause.TRANSIENT, first.cause)
        assertFalse(first.repeated)
        assertFalse(first.suggestCompact)
        assertFalse(first.suggestSwitchModel)
        assertNull(first.advice)
    }

    @Test
    fun `the same code-less failure at the same point again is put down to the request`() {
        val second = of(unexplained, previous = of(unexplained))
        assertEquals(2, second.occurrences)
        assertEquals(LikelyCause.REQUEST, second.cause)
        // About 120k tokens is large: summarising and another model are both offered.
        assertTrue(second.suggestCompact)
        assertTrue(second.suggestSwitchModel)
        assertTrue(second.advice!!.startsWith("This failed the same way twice"), second.advice)
        assertTrue("about 120k tokens" in second.advice!!)
        val third = of(unexplained, previous = second)
        assertEquals(3, third.occurrences)
        assertTrue(third.advice!!.startsWith("This failed the same way 3 times"))
    }

    @Test
    fun `a small chat that keeps failing is offered another model but not a summary`() {
        val again = of(unexplained, previous = of(unexplained, context = 3_000), context = 3_000)
        assertEquals(LikelyCause.REQUEST, again.cause)
        assertFalse(again.suggestCompact)
        assertTrue(again.suggestSwitchModel)
    }

    @Test
    fun `a failure after progress, or a different one, starts the count over`() {
        val first = of(unexplained)
        assertEquals(1, of(unexplained, previous = first, mark = "m10").occurrences)
        assertEquals(1, of(LlmException("x", 500, retryable = true, errorCode = "server_error"), previous = first).occurrences)
    }

    @Test
    fun `rate limits and overload stay the provider's, a dropped connection the network's`() {
        val limited = LlmException("slow down", 429, retryable = true, kind = LlmFailure.RATE_LIMIT, errorCode = "rate_limit_exceeded")
        assertEquals(LikelyCause.TRANSIENT, of(limited, previous = of(limited)).cause)
        val dropped = LlmException("${AgentLoop.CONNECTION_DROPPED}: reset", retryable = true)
        assertEquals(LikelyCause.TRANSIENT, of(dropped, previous = of(dropped)).cause)
    }

    @Test
    fun `quota, auth and overflow are named from the first time`() {
        val limit = ChatGptErrors.fromCode("usage_limit_reached", null)
        val f = of(limit)
        assertEquals(LikelyCause.USAGE_LIMIT, f.cause)
        assertTrue(f.suggestSwitchModel)
        assertEquals("Another model can carry on with this chat while the limit lasts.", f.advice)
        assertEquals(LikelyCause.AUTH, of(LlmException("no", 401, kind = LlmFailure.AUTH)).cause)
        val overflow = of(LlmException("too long", 400, kind = LlmFailure.CONTEXT_OVERFLOW))
        assertEquals(LikelyCause.CONTEXT, overflow.cause)
        assertTrue(overflow.suggestCompact)
    }

    @Test
    fun `an error the provider called invalid comes back as the request's fault`() {
        val bad = LlmException("ChatGPT: the response failed with “invalid_prompt”.", errorCode = "invalid_prompt")
        assertEquals(LikelyCause.OTHER, of(bad).cause)
        assertEquals(LikelyCause.REQUEST, of(bad, previous = of(bad)).cause)
    }

    @Test
    fun `wrappers keep the failure with its ids findable`() {
        val inner = LlmException("boom", 500, retryable = true, rawBody = "{}", requestId = "req_x", responseId = "resp_y")
        val wrapped = inner.withMessage("boom\nBackup models skipped: none.", cause = inner)
        assertEquals("req_x", wrapped.requestId)
        assertEquals("resp_y", wrapped.responseId)
        assertSame(wrapped, RuntimeException("outer", wrapped).llmFailure())
        assertEquals("req_2", LlmException("x").withIds("req_2", "resp_2").requestId)
        assertEquals("req_x", inner.withIds("req_other").requestId)
    }
}
