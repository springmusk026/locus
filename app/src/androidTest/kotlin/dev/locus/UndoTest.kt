package dev.locus

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.locus.testing.FakeModel
import dev.locus.testing.LocusTest
import dev.locus.testing.awaitText
import dev.locus.testing.send
import dev.locus.testing.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Change tracking (#68) on a device: a scripted turn edits real files, and Undo all from its card puts them back. */
@HiltAndroidTest
class UndoTest : LocusTest() {
    @get:Rule(order = 3)
    val compose = createEmptyComposeRule()

    @Test
    fun undoAllPutsTheTurnsFilesBack() {
        workspace.resolve("notes.md").apply { parentFile?.mkdirs() }.writeText("shopping:\nbuy milk\n")
        val chat = newChat()
        FakeModel.script { r ->
            when (r.toolResults) {
                0 -> FakeModel.toolCall("c1", "read_file", """{"path":"notes.md"}""")
                1 -> FakeModel.toolCall("c2", "edit_file", """{"path":"notes.md","old_text":"buy milk","new_text":"buy oat milk\nbuy bread"}""")
                2 -> FakeModel.toolCall("c3", "write_file", """{"path":"todo.md","content":"- call mum\n"}""")
                else -> FakeModel.text("Updated your notes and added a to-do list.")
            }
        }
        launchChat(chat)
        compose.send("Tidy my notes")
        compose.awaitText("Changed 2 files")
        awaitRunEnd(chat)
        assertEquals("shopping:\nbuy oat milk\nbuy bread\n", workspace.resolve("notes.md").readText())
        assertTrue(workspace.resolve("todo.md").exists())

        compose.tap("Undo all")
        compose.awaitText("Undo the changes to 2 files?")
        compose.tap("Undo")
        compose.awaitText("Undid the changes to 2 files")

        assertEquals("shopping:\nbuy milk\n", workspace.resolve("notes.md").readText())
        assertFalse(workspace.resolve("todo.md").exists())
        // Nothing is lost: what the agent wrote went to the trash.
        assertTrue(workspace.trashEntries().any { it.originalPath == "todo.md" })
    }
}
