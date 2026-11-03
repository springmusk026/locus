package dev.locus.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

// Android Studio previews of every shared component, in light and dark. They are the design system's
// catalogue: open this file in Studio's split view. Not used at run time (R8 drops them).

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
internal annotation class LocusPreviews

@Composable
private fun PreviewSurface(content: @Composable ColumnScope.() -> Unit) {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
        }
    }
}

@LocusPreviews
@Composable
private fun TonesPreview() = PreviewSurface {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Tone.entries.forEach { LocusChip(it.name, tone = it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LocusChip("Connected", tone = Tone.Live, dot = true)
        LocusChip("Needs review", tone = Tone.Caution)
        LocusChip("Remote", icon = LocusIcons.Server, tone = Tone.Info)
        LocusChip("Disabled", enabled = false)
    }
    SupportingText("Supporting text: a note under a section.")
    SupportingText("~/projects/locus/README.md", style = LocusType.mono)
}

@LocusPreviews
@Composable
private fun ChipsPreview() = PreviewSurface {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LocusChip("All", variant = ChipVariant.Filter, selected = true, onClick = {})
        LocusChip("Tools", variant = ChipVariant.Filter, onClick = {})
        LocusChip("Errors", variant = ChipVariant.Filter, tone = Tone.Critical, selected = true, onClick = {})
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LocusChip(chipCount(3), variant = ChipVariant.Count, contentDescription = "3 unread")
        LocusChip(chipCount(140), variant = ChipVariant.Count, tone = Tone.Brand, contentDescription = "140 items")
        LocusChip("3 steps", tone = Tone.Brand, icon = LocusIcons.Activity, onClick = {})
    }
}

@LocusPreviews
@Composable
private fun RowsPreview() = PreviewSurface {
    CardGroup {
        SwitchRow("Read replies aloud", checked = true, onCheckedChange = {}, subtitle = "Uses the phone's voice", icon = LocusIcons.Speaker)
        GroupDivider(Insets.iconRow)
        SwitchRow("Remote approvals", checked = false, onCheckedChange = {}, subtitle = "Telegram isn't connected", icon = LocusIcons.Phone, enabled = false)
        GroupDivider(Insets.iconRow)
        SwitchRow("Sync memory", checked = true, onCheckedChange = {}, icon = LocusIcons.Library, busy = true)
        GroupDivider(Insets.iconRow)
        SwitchRow("Notifications", checked = false, onCheckedChange = {}, icon = LocusIcons.Note, error = "Android blocked notifications for Locus.")
        GroupDivider(Insets.iconRow)
        ListRow("A disabled row", subtitle = "Can't be opened right now", leading = { IconTile(LocusIcons.Folder) }, onClick = {}, enabled = false)
    }
    CardGroup {
        KeyValueRow("Version", "1.4.0", mono = true)
        GroupDivider()
        KeyValueRow("Status", "Failed", tone = Tone.Critical)
        GroupDivider()
        KeyValueRow("Model", "claude-haiku-4-5", onClick = {})
    }
    LocusCard {
        KeyValueRow("Took", "42 s", dense = true)
        KeyValueRow("Cost", "$0.04", dense = true, mono = true)
        KeyValueRow("Steps", "12 · open in Activity", dense = true, onClick = {})
    }
    DestinationLink(destinationPath("Settings", "Models"), onNavigate = {}, hint = "No API key for this provider. Add one in")
    ProvideSettingsLinks({}) { SettingsLink(dev.locus.core.model.SettingsDestination.Ssh, hint = "Add one of your machines in") }
}

@LocusPreviews
@Composable
private fun MetersPreview() = PreviewSurface {
    Meter(0.42f, contentDescription = "Context used")
    Meter(0.86f, size = MeterSize.Medium)
    Meter(0.97f, size = MeterSize.Large)
    Meter(0.3f, tone = Tone.Live, size = MeterSize.Hairline)
    Meter(null, contentDescription = "Loading")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        InlineSpinner()
        InlineSpinner(size = IconSize.small, color = MaterialTheme.colorScheme.primary)
    }
}

@LocusPreviews
@Composable
private fun ErrorStatePreview() = PreviewSurface {
    ErrorState(
        title = "Couldn't load your tasks",
        message = "The database didn't answer. Your tasks are safe; this is only the list.",
        onRetry = {},
    )
}

@LocusPreviews
@Composable
private fun ErrorStateRetryingPreview() = PreviewSurface {
    ErrorState(title = "Couldn't reach the server", message = "Check the connection, then try again.", onRetry = {}, retrying = true)
}

@LocusPreviews
@Composable
private fun ConfirmSheetPreview() = PreviewSurface {
    Text("Clear browsing data?", style = LocusType.screenTitle)
    SupportingText("Signs Locus's browser out of every site.", style = LocusType.rowSupporting)
    ConfirmSheetBody("Clear", {}, {}, Tone.Critical, "Cancel", confirmEnabled = true, busy = false, error = null, content = null)
    ConfirmSheetBody(
        "OK", {}, {}, Tone.Brand, "Cancel", confirmEnabled = true, busy = true,
        error = "Couldn't clear all browsing data. Try again.",
        content = { OutlinedTextField("Ada", {}, singleLine = true) },
    )
}

@LocusPreviews
@Composable
private fun FeedbackPreview() = PreviewSurface {
    FeedbackCard("Browsing data cleared", Tone.Live, action = null, onAction = {})
    FeedbackCard("Moved 3 files to the bin", Tone.Neutral, action = "Undo", onAction = {})
    FeedbackCard("Couldn't save the task", Tone.Critical, action = "Retry", onAction = {})
}

@LocusPreviews
@Composable
private fun TopBarPreview() = PreviewSurface {
    LocusTopBar("Scheduled tasks", onBack = {}, subtitle = "3 active · next at 09:00")
}
