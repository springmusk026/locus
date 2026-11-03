package dev.locus.core.data.mcp

import dev.locus.core.testing.TestGraph
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A remote MCP call is retried after a refused sign-in only on an HTTP 401, never because of words in an error. */
class McpAuthRetryTest {
    @Test
    fun `only an HTTP 401 from the transport counts as a refused sign-in`() = runTest {
        val mcp = TestGraph(backgroundScope).mcp
        assertTrue(mcp.isAuthFailure(StreamableHttpError(401, "Unauthorized")))
        assertTrue(mcp.isAuthFailure(IllegalStateException("send failed", StreamableHttpError(401, "Unauthorized"))))
        // Text a server or a tool chose, after it may already have done the work.
        assertFalse(mcp.isAuthFailure(RuntimeException("step 401 done")))
        assertFalse(mcp.isAuthFailure(RuntimeException("Unauthorized: invalid_token")))
        assertFalse(mcp.isAuthFailure(StreamableHttpError(500, "401 Unauthorized")))
        assertFalse(mcp.isAuthFailure(StreamableHttpError(null, "401")))
    }
}
