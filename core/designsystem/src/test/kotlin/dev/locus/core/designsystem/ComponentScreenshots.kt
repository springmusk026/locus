package dev.locus.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.locus.core.model.SettingsDestination
import dev.locus.core.model.TaskBudget
import dev.locus.core.screenshot.ScreenshotTest
import org.junit.Test

/**
 * Every design-system component in every variant (docs/design/design-system.md, "Review gate"). The
 * existing previews come first, then a page per family for the components no preview shows.
 * [ComponentCoverageTest] fails when a public composable appears in none of them.
 */
class ComponentScreenshots : ScreenshotTest() {

    // The catalogue's previews (Previews.kt and beside each component).
    @Test fun tones() = snap("tones", heightDp = 200) { TonesPreview() }
    @Test fun chips() = snap("chips", heightDp = 160) { ChipsPreview() }
    @Test fun rows() = snap("rows", heightDp = 640) { RowsPreview() }
    @Test fun meters() = snap("meters", heightDp = 200) { MetersPreview() }
    @Test fun errorState() = snap("error-state", heightDp = 360) { ErrorStatePreview() }
    @Test fun errorStateRetrying() = snap("error-state-retrying", heightDp = 360) { ErrorStateRetryingPreview() }
    @Test fun confirmSheetBody() = snap("confirm-sheet-body", heightDp = 400) { ConfirmSheetPreview() }
    @Test fun feedback() = snap("feedback", heightDp = 260) { FeedbackPreview() }
    @Test fun topBar() = snap("top-bar", heightDp = 120) { TopBarPreview() }
    @Test fun chatParts() = snap("chat-parts", heightDp = 240) { ChatPartsPreview() }
    @Test fun arrangements() = snap("arrangements", heightDp = 480) { ArrangementsPreview() }
    @Test fun charts() = snap("charts", heightDp = 320) { ChartsPreview() }
    @Test fun composerParts() = snap("composer-parts", heightDp = 160) { ComposerPartsPreview() }
    @Test fun searchField() = snap("search-field", heightDp = 520) { SearchFieldPreview() }
    @Test fun voiceOrb() = snap("voice-orb", heightDp = 260) { VoiceOrbPreview() }
    @Test fun codeSurface() = snap("code-surface", heightDp = 200) { CodeSurfacePreview() }
    @Test fun setupCard() = snap("setup-card", heightDp = 420) { Page { SetupCardPreview() } }

    // The rest, a page per family.

    @Test
    fun screen() = snap("screen") {
        LocusListScreen("Agent", onBack = {}, subtitle = "How Locus works on a task") {
            locusSection("Behaviour", note = "Applies to new chats.") {
                item {
                    CardGroup {
                        SwitchRow("Check its own work", checked = true, onCheckedChange = {}, subtitle = "A second look before it says done")
                        GroupDivider()
                        KeyValueRow("Task budget", "50k tokens · 10 min", onClick = {})
                    }
                }
            }
            locusSection("Instructions", action = "Reset", onAction = {}) {
                item { LocusCard { TextArea("Answer briefly. Ask before deleting files.", onValueChange = {}) } }
            }
            item { LocusSection("A section in a column") { LocusCard { Text("Cards inside a LocusSection line up with its header.", style = LocusType.rowSupporting) } } }
        }
    }

    @Test
    fun screenFullBleed() = snap("screen-full-bleed", heightDp = 400) {
        LocusScreen("Workspace", subtitle = "12 files", actions = { CompactIconButton(LocusIcons.Settings, "Settings", onClick = {}) }) { padding ->
            Column(Modifier.padding(screenContentPadding(padding))) {
                SectionLabel("Recent", action = "See all", onAction = {})
                SectionNote("Files Locus changed in the last week.")
                CardGroup { ListRow("notes.md", subtitle = "Edited 2 min ago", leading = { FileTypeIcon("notes.md", isDirectory = false) }) }
            }
        }
    }

    @Test
    fun cards() = snap("cards", heightDp = 720) {
        Page {
            LocusCard(glow = MaterialTheme.colorScheme.primary) {
                Text("The card a screen is about", style = LocusType.cardTitle)
                SupportingText("One glow per screen at most.", style = LocusType.rowSupporting)
            }
            LocusCard(outline = Locus.colors.caution) {
                Text("Needs attention", style = LocusType.cardTitle)
                SupportingText("An outline marks selection or attention.", style = LocusType.rowSupporting)
            }
            CardGroup {
                ListRow("Projects", subtitle = "4 projects", leading = { IconTile(LocusIcons.Folder) }, trailing = { RowChevron() }, onClick = {})
                GroupDivider(Insets.iconRow)
                KeyValueRow("Model", "claude-sonnet · Anthropic", mono = true)
                GroupDivider()
                KeyValueRow("Status", "Connected", tone = Tone.Live, dense = true)
                GroupDivider()
                AddRow("Add a machine", onClick = {})
            }
            CardGroup {
                ExpandableRow("Linux", subtitle = "Ubuntu 24.04 · 1.2 GB", icon = LocusIcons.Terminal, badge = "Update", startExpanded = true) {
                    SupportingText("Expanded content sits under the row.", Modifier.padding(Spacing.lg))
                }
                GroupDivider(Insets.iconRow)
                ListRow("Read replies aloud", trailing = { InlineSwitch(checked = true, onCheckedChange = {}, contentDescription = "Read replies aloud") })
            }
        }
    }

    @Test
    fun banners() = snap("banners", heightDp = 420) {
        Page {
            Banner("Your Anthropic key was rejected. Add a new one to keep chatting.", Tone.Critical, title = "Key rejected") {
                androidx.compose.material3.TextButton(onClick = {}) { Text("Open Models") }
            }
            Banner("Battery saver is on, so scheduled tasks wait for a charger.", Tone.Caution)
            Banner("Replies come from the model on this phone.", Tone.Info)
            StatusLine("2 running", Tone.Live)
            StatusLine("All paused", Tone.Neutral)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                StatusDot(Tone.Live)
                StatusDot(Tone.Caution)
                StatusPill("Paused", Tone.Caution, dot = true)
                ContextRing(0.62f)
                CountBadge(12)
            }
        }
    }

    @Test
    fun states() = snap("states", heightDp = 520) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                EmptyState("No scheduled tasks", "Ask Locus to do something every morning, or add one here.", icon = LocusIcons.Clock, modifier = Modifier.weight(1f)) {
                    androidx.compose.material3.Button(onClick = {}) { Text("Add a task") }
                }
                LoadingState(Modifier.weight(0.5f))
            }
        }
    }

    @Test
    fun controls() = snap("controls", heightDp = 560) {
        Page {
            Segmented(listOf("Day", "Week", "Month"), selected = 1, onSelect = {})
            Segmented(listOf("Allow", "Ask", "Deny"), selected = 0, onSelect = {}, compact = true)
            TextArea("", onValueChange = {}, placeholder = "What should Locus always keep in mind?", size = TextAreaSize.Regular)
            DestinationLink("Library → Connectors", onNavigate = {}, hint = "Connect more apps in")
            SettingsLink(SettingsDestination.entries.first(), hint = "Change it in")
            ContinueWithChatGptButton(onClick = {})
            TaskBudgetEditor(TaskBudget(tokens = 50_000, minutes = 10), onChange = {}, costNote = "About $0.40 with this model")
        }
    }

    @Test
    fun icons() = snap("icons", heightDp = 260) {
        Page {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Accent.entries.forEach { AccentTile(it, LocusIcons.Sparkle) }
            }
            AccentTileStack(listOf(Accent.Files to LocusIcons.Folder, Accent.Web to LocusIcons.Globe, Accent.Linux to LocusIcons.Terminal))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FileTypeIcon("README.md", isDirectory = false)
                FileTypeIcon("src", isDirectory = true)
                FileTypeIcon("build.gradle.kts", isDirectory = false)
                IconTile(LocusIcons.Folder)
                ProviderBadge("Anthropic", "https://api.anthropic.com", onDevice = false)
                ProviderBadge("On this phone", "", onDevice = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                IconTile(LocusIcons.Calendar)
                IconTile(LocusIcons.Sunrise)
                IconTile(LocusIcons.Check, tint = Tone.Live.color())
                IconTile(LocusIcons.Skip)
                IconTile(LocusIcons.Error, tint = Tone.Critical.color())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                LogoTile(BrandLogo.entries.first())
                ModelLogo("claude-sonnet-4")
                ConnectorIcon("github", name = "GitHub")
                IntegrationIcon("telegram")
            }
        }
    }

    @Test
    fun diff() = snap("diff", heightDp = 200) {
        Page {
            CodeSurface {
                Column {
                    listOf("@@ -1,3 +1,3 @@", " fun greet() {", "-    println(\"Hi\")", "+    println(\"Hello\")", " }").forEach { DiffLine(it) }
                }
            }
        }
    }

    @Test
    fun sheet() = snap("sheet", heightDp = 520) {
        LocusSheet("Add a machine", onDismiss = {}, subtitle = "Locus connects over SSH", action = "Save", onAction = {}) {
            KeyValueRow("Host", "build.example.com", mono = true)
            SupportingText("Locus keeps the key on this phone, encrypted.")
        }
    }

    @Test
    fun confirmSheet() = snap("confirm-sheet", heightDp = 360) {
        ConfirmSheet("Delete this chat?", "Delete", onConfirm = {}, onDismiss = {}, message = "Its files stay in Workspace.", tone = Tone.Critical)
    }

    @Test
    fun sheetLongAction() = snap("sheet-long-action", heightDp = 560) {
        LocusSheet("Restore a backup", onDismiss = {}, subtitle = "Replaces this phone's chats, settings and keys", action = "Restore and restart", onAction = {}) {
            SectionLabel("What's in it")
            KeyValueRow("Chats", "214", dense = true)
            SectionNote("Locus restarts when it's done.")
        }
    }

    @Test
    fun confirmSheetLongLabel() = snap("confirm-sheet-long-label", heightDp = 360) {
        ConfirmSheet("Trust this certificate for every provider?", "Trust for every provider", onConfirm = {}, onDismiss = {}, message = "Locus will accept it on any connection.", tone = Tone.Caution)
    }

    @Test
    fun sideOrBelow() = snap("side-or-below", heightDp = 240) {
        Page {
            SideOrBelow(main = { Text("Short title") }) { LocusChip("Ready", tone = Tone.Live) }
            SideOrBelow(main = { Text("A much longer title that needs the room") }) { LocusChip("Waiting for your approval", tone = Tone.Caution) }
        }
    }

    @Test
    fun actionRow() = snap("action-row", heightDp = 200) {
        Page {
            CardGroup {
                ListRow(title = "Qwen3.5 2B", subtitle = "Paused at 0.8 of 1.9 GB")
                ActionRow {
                    androidx.compose.material3.TextButton(onClick = {}) { Text("Resume") }
                    androidx.compose.material3.TextButton(onClick = {}) { Text("Cancel download") }
                }
            }
        }
    }

    /** A page of components on the background, at the screen inset. */
    @Composable
    private fun Page(content: @Composable ColumnScope.() -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
        }
    }
}
