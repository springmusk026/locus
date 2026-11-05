package dev.locus.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview

/**
 * One measured thing in a card: what it is on the left, how much on the right ("/usr/lib/node_modules
 * 188 MB", "Phone memory 3.0 GB available"). Built for lists of sizes and counts inside a [LocusCard].
 *
 * Unlike a dense [KeyValueRow] the label is never cut: it takes the room the amount leaves and wraps,
 * so a path or a long name stays whole at any font size. The row has no side padding of its own, so its
 * label starts on the card's text edge. [monoLabel] sets the label in mono for paths and process names;
 * the amount is always mono so a column of numbers lines up. TalkBack reads label and amount as one.
 */
@Composable
fun AmountRow(label: String, amount: String, modifier: Modifier = Modifier, monoLabel: Boolean = false) {
    Row(
        modifier.fillMaxWidth().padding(vertical = Spacing.xs).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SupportingText(label, Modifier.weight(1f), style = if (monoLabel) LocusType.mono else LocusType.supporting)
        // The amount wraps past its share rather than squeeze the label to a stub.
        Text(amount, Modifier.atMostShare(AMOUNT_SHARE), style = LocusType.mono, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
    }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun AmountRowPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LocusCard(Modifier.padding(Spacing.lg)) {
                Column {
                    Text("Phone storage", style = LocusType.cardTitle)
                    AmountRow("Linux uses", "612 MB")
                    AmountRow("/usr/lib/node_modules/typescript/lib", "188 MB", monoLabel = true)
                    AmountRow("Phone memory", "3.0 GB available")
                }
            }
        }
    }
}

/** The most of an [AmountRow] its amount may take: room for "3 running · 139 MB" on one line at 360dp. */
private const val AMOUNT_SHARE = 0.6f
