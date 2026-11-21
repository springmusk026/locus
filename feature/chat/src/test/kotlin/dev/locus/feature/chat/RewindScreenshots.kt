package dev.locus.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.locus.core.agent.Rewind
import dev.locus.core.data.agent.RewindPreview
import dev.locus.core.data.agent.SummaryDraft
import dev.locus.core.data.repository.RewindFile
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.model.Part
import dev.locus.core.screenshot.OnScreen
import dev.locus.core.screenshot.ScreenshotTest
import org.junit.Test

/** Rewind here and Summarise from here (docs/design/rewind.md): the message menu, the sheets, and the transcript rows. */
class RewindScreenshots : ScreenshotTest() {

    /**
     * A message's long-press menu, drawn on a menu-like surface rather than in its popup (which Robolectric
     * doesn't finish drawing): Copy, Edit, Fork, then the two history entries.
     */
    @androidx.compose.runtime.Composable
    private fun Menu(actions: MessageActions, running: Boolean = false) {
        Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.TopEnd) {
            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = Spacing.xs) {
                Column(Modifier.widthIn(max = Sizes.readable).width(IntrinsicSize.Max).padding(vertical = Spacing.sm)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_screen_copy)) }, leadingIcon = { Icon(LocusIcons.Copy, null) }, onClick = {})
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_screen_edit)) }, leadingIcon = { Icon(LocusIcons.Edit, null) }, enabled = !running, onClick = {})
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_screen_fork)) }, leadingIcon = { Icon(LocusIcons.Branch, null) }, onClick = {})
                    HistoryMenuItems(actions, running = running, onRewind = {}, onSummarise = {})
                }
            }
        }
    }

    @Test
    fun menu() = snap("message-menu", heightDp = 480) { Menu(MessageActions(summariseCount = 6)) }

    /** An earlier summary already covers the message: Summarise from here is greyed with the reason. */
    @Test
    fun menuSummarised() = snap("message-menu-summarised", heightDp = 480) {
        Menu(MessageActions(summarise = SummariseUnavailable.SUMMARISED))
    }

    @Test
    fun menuRunning() = snap("message-menu-running", heightDp = 480) { Menu(MessageActions(summariseCount = 6), running = true) }

    private val preview = RewindPreview(
        messageId = "u2",
        userMessage = true,
        files = listOf(
            RewindFile("garden-app/src/checkout/payment.ts", TrackedChange.Kind.MODIFIED, RewindFile.State.RESTORE),
            RewindFile("garden-app/src/checkout/retry.ts", TrackedChange.Kind.CREATED, RewindFile.State.RESTORE),
            RewindFile("garden-app/README.md", TrackedChange.Kind.DELETED, RewindFile.State.RESTORE),
            RewindFile("garden-app/package.json", TrackedChange.Kind.MODIFIED, RewindFile.State.CHANGED_SINCE),
            RewindFile("garden-app/assets/logo.png", TrackedChange.Kind.MODIFIED, RewindFile.State.GONE),
        ),
        effects = listOf(
            Rewind.Effect(Rewind.EffectKind.COMMAND, "run_command", "npm install stripe@18"),
            Rewind.Effect(Rewind.EffectKind.COMMAND, "run_command", "npx vitest run"),
            Rewind.Effect(Rewind.EffectKind.COMMAND, "run_command", "git stash"),
            Rewind.Effect(Rewind.EffectKind.OUTSIDE, "github_create_issue", "github_create_issue"),
            Rewind.Effect(Rewind.EffectKind.HELPER, "agent_spawn", "agent_spawn"),
        ),
        draft = "Retry declined cards once before failing",
    )

    /** Files that go back, one changed outside Locus since, one whose earlier version is gone, and what stays done. */
    @Test
    fun rewindSheet() = snap("sheet-rewind", heightDp = 1700) { RewindSheet(preview, onConfirm = {}, onDismiss = {}) }

    /** From a reply, with nothing changed after it: the chat only branches. */
    @Test
    fun rewindSheetNoFiles() = snap("sheet-rewind-no-files", heightDp = 700) {
        RewindSheet(preview.copy(userMessage = false, files = emptyList(), effects = emptyList(), draft = null), onConfirm = {}, onDismiss = {})
    }

    @Test
    fun summariseSheet() = snap("sheet-summarise-from-here", heightDp = 900) {
        SummariseSheet(userMessage = true, count = 14, onConfirm = {}, onDismiss = {})
    }

    private val summary = """
        ## Goal
        Card payments for garden-app's checkout.
        ## Done so far
        - Found the off-by-one in `src/checkout/retry.ts:14` and fixed it; `npx vitest run` passes (44 tests).
        - Declined cards are retried once, after 2 s.
        ## Open
        Refunds (partial and full) are next.
    """.trimIndent()

    @Test
    fun summaryEditor() = snap("sheet-summary-review", heightDp = 1100) {
        SummaryEditorSheet(initial = summary, count = 14, review = true, onSave = {}, onDismiss = {}, onDiscard = {})
    }

    @Test
    fun summaryEditorExisting() = snap("sheet-summary-edit", heightDp = 1000) {
        SummaryEditorSheet(initial = summary, count = 14, review = false, onSave = {}, onDismiss = {})
    }

    /** The transcript's rows: a summary of the later part not yet read (editable), a draft waiting for review, and the later branch after a rewind. */
    @Test
    fun rows() = snap("rewind-summary-rows", heightDp = 900) {
        OnScreen {
            CheckpointCard(ChatItem.Checkpoint("cp", summary, 14, partial = true, edited = true), onEdit = {})
            PendingSummaryCard(SummaryDraft("u2", "r9", Part.Checkpoint(summary, 14, from = "u2")), onReview = {}, onDiscard = {})
            LaterBranchRow(onShow = {})
        }
    }
}
