package dev.locus.feature.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.markdown.rememberMathText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/** Chat-sized headings; the library defaults are display sizes. Shared so streamed and saved text look identical. */
@Composable
private fun chatTypography(): MarkdownTypography = markdownTypography(
    h1 = MaterialTheme.typography.titleLarge,
    h2 = MaterialTheme.typography.titleMedium,
    h3 = MaterialTheme.typography.titleSmall,
    h4 = MaterialTheme.typography.titleSmall,
    h5 = MaterialTheme.typography.labelLarge,
    h6 = MaterialTheme.typography.labelLarge,
    text = MaterialTheme.typography.bodyLarge,
    paragraph = MaterialTheme.typography.bodyLarge,
    code = CodeTextStyle,
    inlineCode = CodeTextStyle,
)

/**
 * GitHub-flavoured Markdown for a saved message. Parsed during composition (`immediate`) rather than
 * asynchronously: the async default first lays out an empty loading state, so a message would appear
 * at zero height and then grow, which made the list jump and broke scrolling to the end on open.
 * Maths is typeset (see [rememberMathText]); copying a selection gives its LaTeX source.
 */
@Composable
internal fun AssistantMarkdown(text: String) {
    val math = rememberMathText(text, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.onBackground)
    val state = rememberMarkdownState(math.markdown, retainState = true, immediate = true)
    SelectionContainer {
        Markdown(
            state,
            typography = chatTypography(),
            components = SavedChatComponents,
            annotator = math.annotator,
            inlineContent = math.inlineContent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Kept reasoning: the same Markdown, smaller and muted so it reads as an aside, not the answer. */
@Composable
internal fun ThoughtMarkdown(text: String) {
    val body = MaterialTheme.typography.bodyMedium
    val math = rememberMathText(text, body, MaterialTheme.colorScheme.onSurfaceVariant)
    val state = rememberMarkdownState(math.markdown, retainState = true, immediate = true)
    SelectionContainer {
        Markdown(
            state,
            annotator = math.annotator,
            inlineContent = math.inlineContent,
            colors = com.mikepenz.markdown.m3.markdownColor(text = MaterialTheme.colorScheme.onSurfaceVariant),
            typography = markdownTypography(
                h1 = MaterialTheme.typography.titleSmall,
                h2 = MaterialTheme.typography.titleSmall,
                h3 = MaterialTheme.typography.labelLarge,
                h4 = MaterialTheme.typography.labelLarge,
                h5 = MaterialTheme.typography.labelLarge,
                h6 = MaterialTheme.typography.labelLarge,
                text = body,
                paragraph = body,
                code = CodeTextStyle,
                inlineCode = CodeTextStyle,
            ),
            components = SavedChatComponents,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The reply while it streams. Maths shows as source until the reply is saved, so nothing flickers
 * while delimiters arrive; a mermaid or math fence renders once its closing fence has arrived (see
 * [chatMarkdownComponents]). Uses the library's append-only streaming state (0.46.0): finished blocks
 * stay parsed and keep their composition, and only the unfinished tail is re-parsed. Deltas are
 * appended at most every [STREAM_TICK_MS], so a burst of tiny deltas costs one parse and one layout.
 * If the text stops extending what was appended (a new step or a retry), the state starts over.
 */
@Composable
internal fun StreamingAssistantMarkdown(text: String) {
    val tracker = remember { StreamTracker() }
    if (!text.startsWith(tracker.base)) tracker.generation++
    tracker.base = text
    val latest by rememberUpdatedState(text)
    key(tracker.generation) {
        val state = rememberStreamingMarkdownState()
        LaunchedEffect(state) {
            var sent = 0
            snapshotFlow { latest }.conflate().collect { now ->
                if (now.length > sent) {
                    state.append(now.substring(sent))
                    sent = now.length
                }
                delay(STREAM_TICK_MS)
            }
        }
        SelectionContainer {
            Markdown(streamingMarkdownState = state, typography = chatTypography(), components = StreamingChatComponents, modifier = Modifier.fillMaxWidth())
        }
    }
}

private const val STREAM_TICK_MS = 48L

private class StreamTracker {
    var base: String = ""
    var generation: Int = 0
}
