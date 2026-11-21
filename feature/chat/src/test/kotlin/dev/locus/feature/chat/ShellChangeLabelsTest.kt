package dev.locus.feature.chat

import androidx.test.core.app.ApplicationProvider
import dev.locus.core.agent.tools.ShellSnapshots
import dev.locus.core.data.repository.FileEdit
import dev.locus.core.data.repository.ShellNote
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the changes card says about shell commands' changes and the commands it couldn't track (docs/design/shell-snapshots.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShellChangeLabelsTest {
    private val locale = Locale.getDefault()

    @Before fun english() = Locale.setDefault(Locale.US)

    @After fun restore() = Locale.setDefault(locale)

    private fun change(byCommand: Boolean) =
        TrackedChange(1, "t", "src/plan.ts", TrackedChange.Kind.MODIFIED, 2, 1, null, binary = false, isDirectory = false, undone = false, canUndo = true, byCommand = byCommand)

    @Test
    fun `a command's change says so, a file tool's doesn't`() {
        assertEquals("By a command", byCommandLabel(testResources, change(byCommand = true)))
        assertNull(byCommandLabel(testResources, change(byCommand = false)))
        assertEquals("Changed by a command", editLabel(testResources, FileEdit("src/plan.ts", "c1", ShellSnapshots.TOOL, 0, 2, 1)))
    }

    @Test
    fun `each reason reads with the tracker's own limits, once per reason`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun note(reason: ShellSnapshots.SkipReason) = ShellNote("c", "t", "x", reason, 0)
        assertEquals(
            listOf(
                "A command's changes weren't tracked: the project has more than 5,000 files.",
                "A command's changes weren't tracked: the project is larger than 100 MB.",
                "A command's changes weren't tracked: reading the project took longer than 5 seconds.",
                "A command changed more than 500 files, too many to track: its changes can't be undone here.",
                "A command's changes weren't tracked: another command was running in this project. · 2 commands",
                "A command's changes weren't tracked: Locus couldn't read the project's folder.",
            ),
            shellNoteLines(
                context,
                listOf(
                    note(ShellSnapshots.SkipReason.TOO_MANY_FILES), note(ShellSnapshots.SkipReason.TOO_LARGE), note(ShellSnapshots.SkipReason.TOO_SLOW),
                    note(ShellSnapshots.SkipReason.TOO_MANY_CHANGES), note(ShellSnapshots.SkipReason.BUSY), note(ShellSnapshots.SkipReason.BUSY),
                    note(ShellSnapshots.SkipReason.FAILED),
                ),
            ),
        )
    }

    @Test
    fun `rewind says commands' project files go back when they were snapshotted`() {
        val effects = listOf(dev.locus.core.agent.Rewind.Effect(dev.locus.core.agent.Rewind.EffectKind.COMMAND, "run_command", "npm run format"))
        assertEquals("1 shell command ran after this; its effects stay.", rewindEffectLines(testResources, effects).single().first)
        assertEquals(
            "1 shell command ran after this: the project files it changed go back with the others above; anything else it did (packages, files outside the project) stays.",
            rewindEffectLines(testResources, effects, commandFilesBack = true).single().first,
        )
    }

    @Test
    fun `a turn with only untracked commands still gets a card, saying so`() {
        val messages = listOf(
            Message("u1", "c", Role.USER, listOf(Part.Text("format the project")), 1),
            Message("a1", "c", Role.ASSISTANT, listOf(Part.Text("Done.")), 2),
        )
        val note = ShellNote("c", "a1", "x", ShellSnapshots.SkipReason.TOO_LARGE, 0)
        val items = transcriptItems(messages, shellNotes = mapOf("a1" to listOf(note)))
        val card = items.filterIsInstance<ChatItem.Changes>().single()
        assertTrue(card.files.isEmpty())
        assertEquals(listOf(note), card.notes)
        // Without notes or changes, no card, as before.
        assertTrue(transcriptItems(messages).none { it is ChatItem.Changes })
    }
}
