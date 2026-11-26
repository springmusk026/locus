package dev.locus.core.data.repository

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each chat's unsent composer text, kept on disk per chat (#319). */
class ChatDraftsTest {
    private val dir: File = Files.createTempDirectory("drafts").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `drafts are kept per chat and read back after a restart`() {
        val drafts = ChatDrafts(dir)
        drafts.set("chat-a", "first line\nsecond line")
        drafts.set("chat-b", "other")
        assertEquals(mapOf("chat-a" to "first line\nsecond line", "chat-b" to "other"), drafts.drafts.value)

        val restarted = ChatDrafts(dir)
        assertEquals("first line\nsecond line", restarted.get("chat-a"))
        assertEquals("other", restarted.get("chat-b"))
    }

    @Test
    fun `blank text and clear drop a draft, on disk too`() {
        val drafts = ChatDrafts(dir)
        drafts.set("chat-a", "text")
        drafts.set("chat-a", "   ")
        drafts.set("chat-b", "text")
        drafts.clear("chat-b")
        assertTrue(drafts.drafts.value.isEmpty())
        assertTrue(ChatDrafts(dir).drafts.value.isEmpty())
    }

    @Test
    fun `an in-memory draft isn't written until asked`() {
        val drafts = ChatDrafts(dir)
        drafts.set("chat-a", "typing", inMemory = true)
        assertEquals("typing", drafts.get("chat-a"))
        assertNull(ChatDrafts(dir).get("chat-a"))
        drafts.set("chat-a", "typing")
        assertEquals("typing", ChatDrafts(dir).get("chat-a"))
    }

    @Test
    fun `an id that isn't a plain name never becomes a path`() {
        val drafts = ChatDrafts(dir)
        drafts.set("../escape", "text")
        assertEquals("text", drafts.get("../escape"))
        assertTrue(dir.parentFile!!.listFiles().orEmpty().none { it.name == "escape.txt" })
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
