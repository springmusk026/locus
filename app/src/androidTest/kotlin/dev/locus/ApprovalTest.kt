package dev.locus

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import dagger.hilt.android.testing.HiltAndroidTest
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.ToolPolicy
import dev.locus.testing.FakeModel
import dev.locus.testing.LocusTest
import dev.locus.testing.awaitText
import dev.locus.testing.send
import dev.locus.testing.tap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

/**
 * A tool set to Ask: the approval card shows in the chat; Allow runs the call, Deny records it. In
 * the background the same request is a notification whose Allow action answers it.
 */
@HiltAndroidTest
class ApprovalTest : LocusTest() {
    @get:Rule(order = 3)
    val compose = createEmptyComposeRule()

    override val toolPolicies = mapOf("write_file" to ToolPolicy.ASK)

    /** One write_file call that needs approval, then a reply. */
    private fun scriptOneWrite() = FakeModel.script { r ->
        if (r.toolResults == 0) FakeModel.toolCall("call_1", "write_file", """{"path":"approved.txt","content":"yes\n"}""")
        else FakeModel.text("Finished.")
    }

    @Test
    fun allowRunsTheCall() {
        val chat = newChat()
        scriptOneWrite()
        launchChat(chat)
        compose.send("Write approved.txt")
        compose.awaitText("Allow this?")
        assertFalse("Nothing runs before the answer", workspace.resolve("approved.txt").exists())
        compose.tap("Allow")
        compose.awaitText("Finished.")
        awaitRunEnd(chat)
        assertEquals("yes\n", workspace.resolve("approved.txt").readText())
        assertEquals(ApprovalDecision.USER_APPROVED, decisionFor(chat))
    }

    @Test
    fun denyIsRecorded() {
        val chat = newChat()
        scriptOneWrite()
        launchChat(chat)
        compose.send("Write approved.txt")
        compose.awaitText("Allow this?")
        compose.tap("Deny")
        compose.awaitText("Finished.")
        awaitRunEnd(chat)
        assertFalse(workspace.resolve("approved.txt").exists())
        assertEquals(ApprovalDecision.USER_DENIED, decisionFor(chat))
        // The model is told it was denied: the call's result is an error.
        val result = runBlocking { conversations.history(chat) }.flatMap { it.toolResults }.single()
        assertTrue(result.isError)
        assertTrue(FakeModel.requests.last().messages.any { it["role"].toString().contains("tool") })
    }

    /**
     * In the background the request is a notification (AgentService's approval notification, or the
     * Live Update on Android 16+) with Deny and Allow; tapping Allow in the shade runs the call.
     * Drives SystemUI with UiAutomator, so shade layouts differ by phone; see docs/TESTING.md.
     */
    @Test
    fun allowFromTheNotification() {
        val chat = newChat()
        scriptOneWrite()
        launchChat(chat)
        compose.send("Write approved.txt")
        compose.awaitText("Allow this?")

        device.pressHome()
        waitUntil("Locus to go to the background") { !appVisible() }
        waitUntil("the approval notification") { approvalNotification() != null }
        val actions = approvalNotification()!!.notification.actions.orEmpty().map { it.title.toString() }
        assertEquals(listOf("Deny", "Allow"), actions)

        device.openNotification()
        try {
            val allow = Pattern.compile("Allow", Pattern.CASE_INSENSITIVE)
            var button = device.wait(Until.findObject(By.text(allow).clickable(true)), 10_000)
            if (button == null) {
                // Collapsed: expand the notification, then look again.
                device.findObject(By.textContains("approved.txt"))?.swipe(Direction.DOWN, 1f)
                button = device.wait(Until.findObject(By.text(allow).clickable(true)), 5_000)
            }
            assertNotNull("No Allow action in the notification shade", button)
            button!!.click()
            awaitRunEnd(chat)
        } finally {
            device.pressBack()
        }
        assertEquals("yes\n", workspace.resolve("approved.txt").readText())
        assertEquals(ApprovalDecision.USER_APPROVED, decisionFor(chat))
    }

    /** The approval notification, or the status notification while it carries the approval's actions. */
    private fun approvalNotification() = activeNotifications().firstOrNull { n ->
        n.notification.actions.orEmpty().any { it.title.toString() == "Allow" }
    }

    private fun decisionFor(chat: String): ApprovalDecision = runBlocking {
        conversations.observeAudit().first { list -> list.any { it.conversationId == chat && it.toolName == "write_file" } }
            .first { it.conversationId == chat && it.toolName == "write_file" }.decision
    }
}
