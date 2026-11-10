package dev.locus.feature.chat

import dev.locus.core.data.repository.FileEdit
import dev.locus.core.data.repository.TrackedChange
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the changes card says about each file and the turn: never "−0", and every edit visible. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChangeLabelsTest {
    private val locale = Locale.getDefault()

    /** Counts are grouped as the phone's language groups them; these tests read them in English. */
    @Before fun english() = Locale.setDefault(Locale.US)

    @After fun restore() = Locale.setDefault(locale)

    @Test
    fun `counts are grouped for the phone's language, in rows, edits and totals`() {
        assertEquals("+12,601 −11,015", lineCounts(12_601, 11_015))
        assertEquals("+12.601 −11.015", lineCounts(12_601, 11_015, Locale.GERMANY))
        assertEquals("12,601", formatCount(12_601))
        assertEquals("+12,480 −9,731", changeSummary(testResources, change(TrackedChange.Kind.MODIFIED, 12_480, 9_731)))
        assertEquals("New · 1,284 lines", changeSummary(testResources, change(TrackedChange.Kind.CREATED, 1_284, 0)))
        assertEquals("Deleted · 1,284 lines", changeSummary(testResources, change(TrackedChange.Kind.DELETED, 0, 1_284)))
        assertEquals(listOf("Edit 1 · +1,240 −14"), editLines(testResources, change(TrackedChange.Kind.MODIFIED, 1, 1, edits = listOf(edit(1_240, 14)))))
    }

    @Test
    fun `phrases wrap only between parts`() {
        val nbsp = ' '
        assertEquals("1${nbsp}new${nbsp}file", unbreakable("1 new file"))
        // Breaks only before a " ·", never inside "121 lines" or "Edited 2 times".
        assertEquals("New ·${nbsp}121${nbsp}lines ·${nbsp}Edited${nbsp}2${nbsp}times", keepPhrases("New · 121 lines · Edited 2 times"))
    }
    private fun change(
        kind: TrackedChange.Kind,
        added: Int,
        removed: Int,
        binary: Boolean = false,
        dir: Boolean = false,
        undone: Boolean = false,
        edits: List<FileEdit> = emptyList(),
    ) = TrackedChange(1, "t", "src/plan.ts", kind, added, removed, null, binary = binary, isDirectory = dir, undone = undone, canUndo = true, edits = edits)

    private fun edit(added: Int, removed: Int, outside: Boolean = false, binary: Boolean = false) =
        FileEdit("src/plan.ts", if (outside) "" else "k$added", if (outside) "command" else "edit_file", 0, added, removed, binary = binary, outside = outside)

    @Test
    fun `a new file is labelled new with its line count, not +N −0`() {
        val created = change(TrackedChange.Kind.CREATED, 121, 0)
        assertEquals("New · 121 lines", changeSummary(testResources, created))
        assertEquals("New · 1 line", changeSummary(testResources, change(TrackedChange.Kind.CREATED, 1, 0)))
        assertEquals("New · empty", changeSummary(testResources, change(TrackedChange.Kind.CREATED, 0, 0)))
        assertFalse(summaryIsCounts(created))
    }

    @Test
    fun `a deleted file is labelled deleted with its line count`() {
        assertEquals("Deleted · 40 lines", changeSummary(testResources, change(TrackedChange.Kind.DELETED, 0, 40)))
        assertEquals("Deleted", changeSummary(testResources, change(TrackedChange.Kind.DELETED, 0, 0)))
    }

    @Test
    fun `a modified file shows only the counts that aren't zero`() {
        assertEquals("+12 −3", changeSummary(testResources, change(TrackedChange.Kind.MODIFIED, 12, 3)))
        assertEquals("+12", changeSummary(testResources, change(TrackedChange.Kind.MODIFIED, 12, 0)))
        assertEquals("−3", changeSummary(testResources, change(TrackedChange.Kind.MODIFIED, 0, 3)))
        assertEquals("No line changes", changeSummary(testResources, change(TrackedChange.Kind.MODIFIED, 0, 0)))
        assertTrue(summaryIsCounts(change(TrackedChange.Kind.MODIFIED, 12, 3)))
        assertFalse(summaryIsCounts(change(TrackedChange.Kind.MODIFIED, 0, 0)))
    }

    @Test
    fun `binary files and folders have no line counts`() {
        assertEquals("New binary file", changeSummary(testResources, change(TrackedChange.Kind.CREATED, 0, 0, binary = true)))
        assertEquals("Binary file changed", changeSummary(testResources, change(TrackedChange.Kind.MODIFIED, 0, 0, binary = true)))
        assertEquals("Binary file deleted", changeSummary(testResources, change(TrackedChange.Kind.DELETED, 0, 0, binary = true)))
        assertEquals("New folder", changeSummary(testResources, change(TrackedChange.Kind.CREATED, 0, 0, dir = true)))
        assertFalse(summaryIsCounts(change(TrackedChange.Kind.MODIFIED, 3, 1, binary = true)))
    }

    @Test
    fun `each edit is listed with its own counts, and the row says how many`() {
        val c = change(TrackedChange.Kind.CREATED, 121, 0, edits = listOf(edit(124, 0), edit(11, 14)))
        assertEquals(listOf("Edit 1 · +124", "Edit 2 · +11 −14"), editLines(testResources, c))
        assertEquals("Edited 2 times", editCountLabel(testResources, c))
        // A change found between tool edits isn't numbered or counted as an edit.
        val mixed = change(TrackedChange.Kind.MODIFIED, 5, 1, edits = listOf(edit(3, 0), edit(0, 0, outside = true), edit(2, 1), edit(0, 0, binary = true)))
        assertEquals(listOf("Edit 1 · +3", "Changed outside the file tools", "Edit 2 · +2 −1", "Edit 3 · binary"), editLines(testResources, mixed))
        assertEquals("Edited 3 times", editCountLabel(testResources, mixed))
        assertNull(editCountLabel(testResources, change(TrackedChange.Kind.MODIFIED, 1, 1, edits = listOf(edit(1, 1)))))
    }

    @Test
    fun `the turn's total drops a zero side and counts new and deleted files`() {
        val files = listOf(
            change(TrackedChange.Kind.CREATED, 121, 0),
            change(TrackedChange.Kind.CREATED, 8, 0),
            change(TrackedChange.Kind.DELETED, 0, 40),
            change(TrackedChange.Kind.CREATED, 9, 0, undone = true),
        )
        assertEquals(listOf("2 new files", "1 deleted"), changesTotalsParts(testResources, files))
        assertEquals(listOf("1 new file"), changesTotalsParts(testResources, files.take(1)))
        assertTrue(changesTotalsParts(testResources, listOf(change(TrackedChange.Kind.MODIFIED, 2, 1))).isEmpty())
        val (added, removed) = changesTotals(files.take(2))
        assertEquals("+129", lineCounts(added, removed))
        assertNull(lineCounts(0, 0))
    }

    @Test
    fun `the title counts what still stands and says when everything was undone`() {
        val one = change(TrackedChange.Kind.MODIFIED, 1, 1)
        val two = change(TrackedChange.Kind.MODIFIED, 2, 2)
        assertEquals("Changed 1 file", changesTitle(testResources, listOf(one)))
        assertEquals("Changed 2 files", changesTitle(testResources, listOf(one, two)))
        assertEquals("Changed 1 file", changesTitle(testResources, listOf(one, two.copy(undone = true))))
        assertEquals("Undid the change to 1 file", changesTitle(testResources, listOf(one.copy(undone = true))))
        assertEquals("Undid the changes to 2 files", changesTitle(testResources, listOf(one.copy(undone = true), two.copy(undone = true))))
    }

    @Test
    fun `an edit is labelled by the tool that made it`() {
        fun label(tool: String, outside: Boolean = false) =
            editLabel(testResources, FileEdit("src/plan.ts", "k", tool, 0, 1, 0, outside = outside))
        assertEquals("Wrote the file", label("write_file"))
        assertEquals("Edited", label("edit_file"))
        assertEquals("Edited", label("multi_edit"))
        assertEquals("Deleted", label("delete_path"))
        assertEquals("Moved", label("move_path"))
        assertEquals("Copied", label("copy_path"))
        assertEquals("Created", label("make_directory"))
        assertEquals("Changed", label("something_else"))
        assertEquals("Changed outside the file tools (a command, or you)", label("command", outside = true))
    }

    @Test
    fun `the export says the same things in English, whatever the screen's language`() {
        assertEquals("New · 1 line", ChatExport.changeSummaryEnglish(change(TrackedChange.Kind.CREATED, 1, 0)))
        assertEquals("New · 1,284 lines", ChatExport.changeSummaryEnglish(change(TrackedChange.Kind.CREATED, 1_284, 0)))
        assertEquals("New · empty", ChatExport.changeSummaryEnglish(change(TrackedChange.Kind.CREATED, 0, 0)))
        assertEquals("Deleted · 40 lines", ChatExport.changeSummaryEnglish(change(TrackedChange.Kind.DELETED, 0, 40)))
        assertEquals("Deleted", ChatExport.changeSummaryEnglish(change(TrackedChange.Kind.DELETED, 0, 0)))
        assertEquals("+12 −3", ChatExport.changeSummaryEnglish(change(TrackedChange.Kind.MODIFIED, 12, 3)))
        assertEquals("No line changes", ChatExport.changeSummaryEnglish(change(TrackedChange.Kind.MODIFIED, 0, 0)))
        assertEquals("Binary file changed", ChatExport.changeSummaryEnglish(change(TrackedChange.Kind.MODIFIED, 0, 0, binary = true)))
        assertEquals("New folder", ChatExport.changeSummaryEnglish(change(TrackedChange.Kind.CREATED, 0, 0, dir = true)))
    }
}
