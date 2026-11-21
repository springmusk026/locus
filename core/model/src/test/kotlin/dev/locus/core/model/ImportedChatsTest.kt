package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An imported chat is known by its id alone (docs/design/import-chats.md), so the database needs no new column. */
class ImportedChatsTest {
    @Test
    fun `ids carry the source, stay stable and never match Locus's own chats`() {
        val id = ImportedChats.chatId(ChatImportSource.CHATGPT, "6789abcd-0000-4000-8000-000000000001")
        assertEquals("imported-chatgpt-6789abcd-0000-4000-8000-000000000001", id)
        assertEquals(id, ImportedChats.chatId(ChatImportSource.CHATGPT, "6789abcd-0000-4000-8000-000000000001"))
        assertEquals(ChatImportSource.CHATGPT, ImportedChats.sourceOf(id))
        assertEquals(ChatImportSource.CLAUDE, ImportedChats.sourceOf(ImportedChats.chatId(ChatImportSource.CLAUDE, "c0ffee")))
        // Odd characters are replaced, so an id is always safe to store and link.
        assertEquals("imported-claude-a_b_c", ImportedChats.chatId(ChatImportSource.CLAUDE, "a/b c"))
        assertNull(ImportedChats.sourceOf(java.util.UUID.randomUUID().toString()))
        assertNull(ImportedChats.sourceOf("imported-gemini-1"))
        assertFalse(ImportedChats.isImported("New chat"))
        assertTrue(ImportedChats.messageId(id, "client-created-root").startsWith("$id:"))
        assertEquals("chatgpt:n1", ImportedChats.sourceRef(ChatImportSource.CHATGPT, "n1"))
        val chat = Conversation(id, "Trip", null, "", 0, 0)
        assertEquals(ChatImportSource.CHATGPT, chat.importedFrom)
    }
}
