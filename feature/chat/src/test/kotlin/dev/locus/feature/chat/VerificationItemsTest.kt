package dev.locus.feature.chat

import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Build and test runs become verification cards in the transcript; everything else stays a step. */
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
        assertEquals("3 tests completed, 1 failed", verificationDetail(card.outcome))
        // The raw output stays with the card.
        assertTrue("FooTest" in card.call.result!!.content)
        assertEquals(listOf("ls", "npm test"), items.filterIsInstance<ChatItem.ToolCall>().map { it.argumentsJson.substringAfter(":\"").substringBefore("\"") })
        assertIs<ChatItem.Assistant>(items.last())
    }

    @Test
    fun `durations read well`() {
        assertEquals("1.2 s", durationLabel(1_234))
        assertEquals("42 s", durationLabel(42_400))
        assertEquals("3 min 5 s", durationLabel(185_000))
    }
}
