package dev.locus.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp

/*
 * How small things sit together: a row of filter chips, chips under a row's title, a card's
 * actions, and a transfer's progress. Each was hand-made in feature code with its own spacing.
 */

/**
 * Filter chips in one line that scrolls sideways when they don't fit, so a chip is never orphaned
 * on a line of its own (at 200% font too). [Spacing.sm] between chips. Put it straight in the
 * screen's list, under a [SectionLabel].
 */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * True at large font sizes (150 % and up), where side-by-side layouts run out of width: a sheet's
 * header action and a dense [KeyValueRow]'s label stack instead of squeezing the text beside them.
 */
@Composable
internal fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE

private const val LARGE_FONT_SCALE = 1.5f

/**
 * Status chips that wrap onto more lines rather than overlap or clip: the chips under a [ListRow]'s
 * title (its `meta`), or a sheet's facts. [Spacing.xs] between chips and between lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier.padding(top = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        // A tappable chip has a 48dp touch target around its 24dp pill; centring keeps every pill on one line.
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A card's actions, end-aligned like [Banner]'s, that wrap onto a second line at large font sizes
 * instead of clipping. Text buttons only; the default padding lines their text up under a
 * [ListRow]'s inside a [CardGroup].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.sm), content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(contentPadding),
        horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

/**
 * A download or copy in progress, under the [ListRow] that names it in a [CardGroup]: a [Meter]
 * ([fraction] null for an unknown size), the [status] in mono ("0.8 / 1.9 GB" over "4.2 MB/s · 3 min
 * left", a line each, figures kept whole with no-break spaces), and [actions] (Pause, Resume, Cancel) in an [ActionRow].
 *
 * TalkBack hears [announcement] through a polite live region. Pass one that changes in steps
 * ("Qwen3.5 2B downloading, 40 percent"), not on every byte.
 */
@Composable
fun TransferProgress(
    fraction: Float?,
    status: String,
    announcement: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Live,
    /** Where the meter and status start: [Insets.iconRow] lines them up with a [ListRow]'s text after its tile. */
    start: Dp = Spacing.lg,
    actions: @Composable FlowRowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Meter(
            fraction,
            Modifier.padding(start = start, end = Spacing.lg).semantics { liveRegion = LiveRegionMode.Polite },
            tone = tone,
            contentDescription = announcement,
        )
        SupportingText(status, Modifier.padding(start = start, end = Spacing.lg, top = Spacing.xs), style = LocusType.mono)
        ActionRow(content = actions)
    }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Large font", widthDp = 360, fontScale = 2f)
@Composable
internal fun ArrangementsPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ChipRow {
                    LocusChip("Fits this phone", variant = ChipVariant.Filter, selected = true, onClick = {})
                    LocusChip("Tools", variant = ChipVariant.Filter, onClick = {})
                    LocusChip("Under 1 GB", variant = ChipVariant.Filter, onClick = {})
                    LocusChip("LiteRT", variant = ChipVariant.Filter, onClick = {})
                    LocusChip("llama.cpp", variant = ChipVariant.Filter, onClick = {})
                }
                CardGroup {
                    ListRow(
                        title = "Qwen3.5 2B",
                        subtitle = "1.3 GB · Q4_K_M · Apache 2.0",
                        leading = { IconTile(LocusIcons.Layers) },
                        meta = { ChipFlow { LocusChip("llama.cpp"); LocusChip("Tools", tone = Tone.Brand); LocusChip("Tight fit", tone = Tone.Caution) } },
                        trailing = { TextButton(onClick = {}) { Text("Get") } },
                    )
                    GroupDivider()
                    ListRow(title = "SmolLM3 3B", leading = { IconTile(LocusIcons.Layers) })
                    TransferProgress(0.42f, "0.8\u00A0/\u00A01.9\u00A0GB\n5.0\u00A0MB/s · 4\u00A0min left", "SmolLM3 3B downloading, 40 percent", start = Insets.iconRow) {
                        TextButton(onClick = {}) { Text("Pause") }
                        TextButton(onClick = {}) { Text("Cancel") }
                    }
                }
            }
        }
    }
}
