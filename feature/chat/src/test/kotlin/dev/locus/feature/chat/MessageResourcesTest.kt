package dev.locus.feature.chat

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The counted messages the chat screens read straight from resources (no helper builds them): the
 * English wording, and that a count of 1 reads as a singular.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageResourcesTest {
    private fun plural(id: Int, n: Int, vararg args: Any) = testResources.getQuantityString(id, n, *(if (args.isEmpty()) arrayOf<Any>(n) else args))

    @Test fun `memory reads, queued messages and helper tokens agree with their count`() {
        assertEquals("Read 1 memory note", plural(R.plurals.chat_memory_read, 1))
        assertEquals("Read 3 memory notes", plural(R.plurals.chat_memory_read, 3))
        assertEquals("1 queued message wasn't sent.", plural(R.plurals.chat_screen_queued_not_sent, 1))
        assertEquals("3 queued messages weren't sent.", plural(R.plurals.chat_screen_queued_not_sent, 3))
    }

    @Test fun `a queued message and the side question card say what happens to them`() {
        assertEquals("Locus reads this after the current step", testResources.getString(R.string.chat_screen_queued_running))
        assertEquals("Not sent yet", testResources.getString(R.string.chat_screen_queued_idle))
        assertEquals("Not added to the chat. The task keeps going.", testResources.getString(R.string.chat_side_caption_running))
        assertEquals("Not added to the chat.", testResources.getString(R.string.chat_side_caption))
    }

    @Test fun `a provider's models and the lines added agree with their count`() {
        assertEquals("Default: x · 1 model", plural(R.plurals.chat_modelsheet_status_models, 1, "Default: x", 1))
        assertEquals("1 line added", plural(R.plurals.chat_changes_lines_added, 1, "1"))
        assertEquals("1,284 lines added", plural(R.plurals.chat_changes_lines_added, 1_284, "1,284"))
    }

    @Test fun `a checkpoint and a file copy agree with their count`() {
        assertEquals("Summarised 1 earlier message", plural(R.plurals.chat_screen_summarised, 1))
        assertEquals("Summarised 12 earlier messages", plural(R.plurals.chat_screen_summarised, 12))
        assertEquals("1 of 1 file", plural(R.plurals.chat_remote_progress, 1, 1, 1))
        assertEquals("128 of 311 files", plural(R.plurals.chat_remote_progress, 311, 128, 311))
    }

    @Test fun `the approvals a receipt counts read as a count`() {
        assertEquals("1 asked", plural(R.plurals.chat_receipt_approvals_asked, 1))
        assertEquals("2 you approved", plural(R.plurals.chat_receipt_approvals_approved, 2))
        assertEquals("1 you declined", plural(R.plurals.chat_receipt_approvals_declined, 1))
        assertEquals("1 blocked", plural(R.plurals.chat_receipt_approvals_blocked, 1))
    }

    @Test fun `sentences join the way English writes them`() {
        assertEquals("Locus stopped. Nothing was sent.", testResources.joinSentences(listOf("Locus stopped.", "Nothing was sent.")))
        assertEquals("", testResources.joinSentences(emptyList()))
    }

    @Test fun `sizes are written in bytes, KB and MB`() {
        assertEquals("512 B", sizeLabel(testResources, 512))
        assertEquals("48 KB", sizeLabel(testResources, 48 * 1024L))
        assertEquals("1.5 MB", sizeLabel(testResources, (1.5 * 1024 * 1024).toLong()))
    }
}
