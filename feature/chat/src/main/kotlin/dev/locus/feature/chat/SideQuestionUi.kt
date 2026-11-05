package dev.locus.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import dev.locus.core.data.agent.SideThread
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone

/** What the composer's text asks for, when it's the `/btw` command. */
internal sealed interface SideCommand {
    /** `/btw <question>`: a side question. */
    data class Ask(val question: String) : SideCommand

    /** `/btw` alone: show the last side question's card again. */
    data object Reopen : SideCommand

    companion object {
        private val BTW = Regex("^\\s*/btw(?:\\s+([\\s\\S]*))?$", RegexOption.IGNORE_CASE)

        /** The `/btw` command in [text], or null when the text is an ordinary message. */
        fun parse(text: String): SideCommand? {
            val match = BTW.matchEntire(text) ?: return null
            val question = match.groupValues[1].trim()
            return if (question.isEmpty()) Reopen else Ask(question)
        }
    }
}

/**
 * What Send does with the composer's text: a side question (typed as `/btw …`, or with the Aside
 * switch on), or null for the normal path (a message, queued while the agent works).
 */
internal fun sideCommandFor(text: String, asideOn: Boolean): SideCommand? =
    SideCommand.parse(text) ?: if (asideOn && text.isNotBlank()) SideCommand.Ask(text.trim()) else null

/** The side question card's caption: what happens to the question. */
internal fun sideCaption(running: Boolean): String =
    if (running) "Not added to the chat. The task keeps going." else "Not added to the chat."

/**
 * The side question card above the composer: the question, its answer (streaming in), and a dimmed
 * line for each earlier one. Dismissible; nothing here is part of the chat.
 */
@Composable
internal fun SideQuestionCard(
    thread: SideThread,
    running: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = thread.current ?: return
    LocusCard(modifier.animateContentSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Spacing.md, top = Spacing.xs, bottom = Spacing.sm, end = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LocusIcons.Chats, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.small))
            // At a large font Clear goes under the caption, so the title isn't squeezed to a column.
            val clearBelow = largeFont()
            Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                Text("Side question", style = LocusType.cardTitle)
                SupportingText(sideCaption(running))
                if (clearBelow && thread.exchanges.size > 1) TextButton(onClick = onClear, modifier = Modifier.offset(x = -Spacing.md)) { Text("Clear") }
            }
            if (!clearBelow && thread.exchanges.size > 1) TextButton(onClick = onClear) { Text("Clear") }
            IconButton(onClick = onDismiss) { Icon(LocusIcons.Close, contentDescription = "Close side question") }
        }
        // The body starts on the title's edge, after the icon.
        Column(
            Modifier.fillMaxWidth().heightIn(max = Sizes.inlineScroll).verticalScroll(rememberScrollState()).padding(start = IconSize.small + Spacing.sm, end = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            val earlier = thread.earlier.takeLast(EARLIER_SHOWN)
            val older = thread.earlier.size - earlier.size
            // Earlier questions: muted, but still readable.
            if (older > 0) SupportingText("$older older")
            earlier.forEach { e -> SupportingText(e.question, maxLines = 1) }
            SupportingText(current.question, style = LocusType.rowSupporting)
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                when {
                    current.error != null -> Text(current.error!!, style = LocusType.rowSupporting, color = MaterialTheme.colorScheme.error)
                    current.answer.isEmpty() && !current.done -> Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(Tone.Live)
                        SupportingText("Answering…", Modifier.padding(start = Spacing.sm), style = LocusType.rowSupporting)
                    }
                    current.done -> SideAnswer(current.answer)
                    else -> Text(current.answer, style = LocusType.rowSupporting)
                }
                if (current.triedTools) {
                    SupportingText(
                        "It tried to use a tool; side questions can't run tools, so nothing ran.",
                        Modifier.padding(top = Spacing.xs),
                        style = LocusType.supporting,
                    )
                }
            }
        }
    }
}

/** The finished answer as Markdown, in the body size of the card. */
@Composable
private fun SideAnswer(text: String) {
    if (text.isNotBlank()) AssistantMarkdown(text)
}

private const val EARLIER_SHOWN = 5
