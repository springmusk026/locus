package dev.locus.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Things the user might say next (follow-ups after a reply): one row of chips that scrolls sideways,
 * so it never takes more than a touch target's height however many there are or however long they
 * run. A long one is cut short with an ellipsis; TalkBack still reads all of it, as "Suggested: …"
 * in a list, so the row is heard as one group. The row has no padding above or below: the 48dp
 * targets already give it room, and whatever docks it adds no gap of its own.
 */
@Composable
fun SuggestionRow(suggestions: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics {
                isTraversalGroup = true
                collectionInfo = CollectionInfo(rowCount = 1, columnCount = suggestions.size)
            }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        suggestions.forEachIndexed { i, text ->
            LocusChip(
                text,
                Modifier
                    .widthIn(max = SuggestionMaxWidth)
                    .semantics { collectionItemInfo = CollectionItemInfo(rowIndex = 0, rowSpan = 1, columnIndex = i, columnSpan = 1) }
                    .entrance(i),
                variant = ChipVariant.Suggestion,
                tone = Tone.Brand,
                onClick = { onPick(text) },
                contentDescription = "Suggested: $text",
            )
        }
    }
}

/** The widest a suggestion gets: on a 360dp phone the next one still peeks in, so the row reads as scrollable. */
private val SuggestionMaxWidth = 280.dp

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun SuggestionRowPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(vertical = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                SuggestionRow(listOf("Show it with a TextField", "How do I test it?", "Cancel on exit?"), onPick = {})
                SuggestionRow(
                    listOf(
                        "Show me how to wire the debounced query into a TextField with a clear button",
                        "How would I write a unit test for the debounce with a test dispatcher?",
                    ),
                    onPick = {},
                )
            }
        }
    }
}
