package dev.locus.feature.chat

import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Build and test runs become verification cards in the transcript; everything else stays a step. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VerificationItemsTest {
    private var t = 0L
    private fun msg(role: Role, vararg parts: Part) = Message("m${t++}", "c", role, parts.toList(), t)
    private fun run(id: String, command: String) = Part.ToolCall(id, "run_command", """{"command":${kotlinx.serialization.json.JsonPrimitive(command)}}""")

    @Test
    fun `a test run is a card, other commands and refused runs stay steps, and the reply is still the reply`() {
        val items = transcriptItems(
            listOf(
                msg(Role.USER, Part.Text("fix the failing test")),
                msg(Role.ASSISTANT, run("k1", "ls"), run("k2", "cd app && ./gradlew test"), run("k3", "npm test")),
                msg(
                    Role.TOOL,
                    Part.ToolResult("k1", "run_command", "Exit code 0 (0.1 s).\nsrc", decision = ApprovalDecision.AUTO_ALLOWED),
                    Part.ToolResult(
                        "k2", "run_command",
                        "Exit code 1 (61.0 s).\ncom.a.FooTest > bar FAILED\n3 tests completed, 1 failed",
                        isError = true, decision = ApprovalDecision.USER_APPROVED,
                    ),
                    Part.ToolResult("k3", "run_command", "Not allowed: the user has turned this tool off.", isError = true, decision = ApprovalDecision.POLICY_DENIED),
                ),
                msg(Role.ASSISTANT, Part.Text("Fixed: the test passes now.")),
            ),
        )
        val card = items.filterIsInstance<ChatItem.Verification>().single()
        assertEquals("Tests failed", dev.locus.core.model.Verification.title(card.outcome))
        assertEquals(listOf("com.a.FooTest > bar"), card.outcome.failures)
        assertEquals(61_000L, card.outcome.durationMs)
        assertEquals("3 tests completed, 1 failed", verificationDetail(testResources, card.outcome))
        // The raw output stays with the card.
        assertTrue("FooTest" in card.call.result!!.content)
        assertEquals(listOf("ls", "npm test"), items.filterIsInstance<ChatItem.ToolCall>().map { it.argumentsJson.substringAfter(":\"").substringBefore("\"") })
        assertIs<ChatItem.Assistant>(items.last())
    }

    @Test
    fun `durations read well`() {
        assertEquals("1.2 s", durationLabel(testResources, 1_234))
        assertEquals("42 s", durationLabel(testResources, 42_400))
        assertEquals("3 min 5 s", durationLabel(testResources, 185_000))
    }

    @Test
    fun `the line under a check says the runner's tally, the failing tests or the exit code`() {
        val command = dev.locus.core.model.Verification.Command(dev.locus.core.model.Verification.Kind.TEST, "make")
        fun outcome(summary: String? = null, failureCount: Int = 0, exitCode: Int? = null, passed: Boolean? = false) =
            dev.locus.core.model.Verification.Outcome(command, passed, exitCode, 1_000, summary, emptyList(), failureCount, emptyList())
        // The runner's own words come first, as it printed them.
        assertEquals("12 passed, 1 failed", verificationDetail(testResources, outcome(summary = "12 passed, 1 failed", failureCount = 1)))
        assertEquals("1 failing test", verificationDetail(testResources, outcome(failureCount = 1)))
        assertEquals("3 failing tests", verificationDetail(testResources, outcome(failureCount = 3)))
        assertEquals("Exit code 2", verificationDetail(testResources, outcome(exitCode = 2)))
        assertNull(verificationDetail(testResources, outcome(passed = true, exitCode = 0)))
    }
}
