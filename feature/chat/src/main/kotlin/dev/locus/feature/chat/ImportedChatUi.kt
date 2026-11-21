package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.BottomDock
import dev.locus.core.designsystem.FloatingLayer
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.ChatImportSource
import java.text.DateFormat
import java.util.Date

/**
 * An imported chat in the UI (docs/design/import-chats.md): the "Imported from ChatGPT" line under its
 * title, and in place of the composer, a band that says it's a read-only archive with "Continue in Locus".
 */

/** Under the title, where a project chip would be, drawn like [TemporaryMarker]. Not tappable: the band below explains. */
@Composable
internal fun ImportedMarker(source: ChatImportSource, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.chat_imported_marker, source.displayName)
    Row(
        modifier.clearAndSetSemantics { contentDescription = label }.padding(top = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LocusIcons.Import, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        SupportingText(label, style = LocusType.chip, maxLines = 1)
    }
}

/**
 * The read-only composer state: what this chat is, when it started, and the one way on, a new Locus chat
 * that reads this one ([onContinue]). Nothing here sends anything.
 */
@Composable
internal fun ImportedDock(source: ChatImportSource, startedAt: Long, onContinue: () -> Unit) {
    FloatingLayer {
        BottomDock {
            Column(Modifier.navigationBarsPadding().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) { // design-exception: in the chat's bottom band, like the composer, so it sits above the navigation bar (BottomDock asks for the insets inside it)
                Banner(
                    text = stringResource(
                        R.string.chat_imported_body,
                        source.displayName,
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(startedAt)),
                    ),
                    tone = Tone.Neutral,
                    title = stringResource(R.string.chat_imported_title),
                    actionsBelow = true,
                ) {
                    Button(onClick = onContinue) { Text(stringResource(R.string.chat_imported_continue)) }
                }
            }
        }
    }
}
