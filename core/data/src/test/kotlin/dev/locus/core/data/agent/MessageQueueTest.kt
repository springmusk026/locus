package dev.locus.core.data.agent

import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageQueueTest {
    private val dir: File = Files.createTempDirectory("queued").toFile()
    private var now = 0L
    private fun queue() = MessageQueue(dir, clock = { now++ })

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `messages keep their order and are handed over once`() {
        val q = queue()
        q.add("chat", "first")
        q.add("chat", "second")
        q.add("other", "elsewhere")
        assertEquals(listOf("first", "second"), q.get("chat").map { it.text })

        val taken = q.take("chat")
        assertEquals(listOf("first", "second"), taken.map { it.text })
        assertTrue(q.take("chat").isEmpty())
        assertFalse(q.has("chat"))
        assertEquals(listOf("elsewhere"), q.get("other").map { it.text })
    }

    @Test
    fun `a waiting message can be edited or cancelled, but not once it was sent`() {
        val q = queue()
        val a = q.add("chat", "draft one")
        val b = q.add("chat", "draft two")
        assertTrue(q.edit("chat", a.id, "  final one "))
        assertTrue(q.remove("chat", b.id))
        assertEquals(listOf("final one"), q.get("chat").map { it.text })
        assertFalse(q.edit("chat", a.id, " "), "blank text is refused")

        q.take("chat")
        assertFalse(q.edit("chat", a.id, "too late"))
        assertFalse(q.remove("chat", a.id))
    }

    @Test
    fun `queued messages survive the app being killed`() {
        val q = queue()
        q.add("chat", "keep the drafts", listOf(Part.Attachment("a.png", "image/png", "/x/a.png", "attachments/a.png", 3)))
        q.add("chat", "and email me")

        val reloaded = queue()
        assertEquals(setOf("chat"), reloaded.load())
        val list = reloaded.get("chat")
        assertEquals(listOf("keep the drafts", "and email me"), list.map { it.text })
        assertEquals("a.png", list.first().attachments.single().name)
    }

    @Test
    fun `taken messages stay saved until delivered, and a crash in between is resolved by id`() {
        val q = queue()
        val a = q.add("chat", "one")
        val b = q.add("chat", "two")
        q.take("chat")
        q.delivered("chat", a.id)

        // Killed after "one" reached the chat, before "two" did: "two" is still saved.
        val reloaded = queue()
        reloaded.load()
        assertEquals(listOf(b.id), reloaded.get("chat").map { it.id })

        // Killed after "two" was stored but before it was confirmed: recovery drops it by id.
        reloaded.dropDelivered("chat", setOf(b.id))
        assertFalse(reloaded.has("chat"))
        assertFalse(File(dir, "chat.json").exists())
    }

    @Test
    fun `messages taken but never stored go back to the front`() {
        val q = queue()
        q.add("chat", "one")
        q.take("chat")
        q.add("chat", "two")
        q.restore("chat")
        assertEquals(listOf("one", "two"), q.get("chat").map { it.text })
    }

    @Test
    fun `discard clears the chat's queue and its file`() {
        val q = queue()
        q.add("chat", "one")
        q.clear("chat")
        assertFalse(q.has("chat"))
        assertTrue(queue().load().isEmpty())
    }

    @Test
    fun `a queued message becomes the user's message, with its id and the plan-mode note`() {
        val q = queue()
        val queued = q.add("chat", "also check the logs")
        val plain = queued.toChatMessage("chat", planMode = false, now = 5)
        assertEquals(queued.id, plain.id)
        assertEquals(Role.USER, plain.role)
        assertEquals("also check the logs", plain.userText)
        assertEquals(1, plain.parts.size)

        val planned = queued.toChatMessage("chat", planMode = true, now = 5)
        assertEquals("also check the logs", planned.userText)
        assertTrue(planned.parts.any { it is Part.Text && ModeNote.isNote(it.text) })
        assertTrue(ModeNote.isPlanMode(planned))
        assertFalse(ModeNote.isMidTask(planned))
    }

    @Test
    fun `a message joining at a step boundary carries the hidden mid-task note, after the user's words`() {
        val q = queue()
        val queued = q.add("chat", "use the staging bucket instead")
        val mid = queued.toChatMessage("chat", planMode = false, midTask = true, now = 5)
        assertEquals("use the staging bucket instead", mid.userText)
        assertTrue(ModeNote.isMidTask(mid))
        assertFalse(ModeNote.isPlanMode(mid))
        // The user's words first, then the note: the model reads "the message above".
        assertEquals("use the staging bucket instead", (mid.parts.first() as Part.Text).text)
        assertTrue(ModeNote.MID_TASK in (mid.parts.last() as Part.Text).text)

        val both = queued.toChatMessage("chat", planMode = true, midTask = true, now = 5)
        assertTrue(ModeNote.isMidTask(both) && ModeNote.isPlanMode(both))
        assertFalse(ModeNote.isMidTask(queued.toChatMessage("chat", planMode = false, now = 5)))
    }

    @Test
    fun `ids that aren't safe file names are never written`() {
        val q = queue()
        q.add("../escape", "x")
        assertTrue(dir.parentFile.listFiles().orEmpty().none { it.name.startsWith("escape") })
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
