package dev.locus.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.RowChevron

/** Version, updates, a plain account of what leaves the phone, Diagnostics, and a way back into setup. */
@Composable
internal fun AboutSettings(onBack: () -> Unit, onRunSetup: () -> Unit, onOpenDiagnostics: () -> Unit = {}, updatesViewModel: UpdatesViewModel = hiltViewModel()) {
    val version = appVersion()
    val updates by updatesViewModel.uiState.collectAsStateWithLifecycle()
    val updateActions = remember(updatesViewModel) { updatesViewModel.actions() }
    UpdatesResumeEffect(updatesViewModel)
    SettingsPageScaffold("About & privacy", onBack) {
        // First, so "Update available" (and the notification that opens this page) is in view.
        updatesSection(updates, updateActions)

        item { SectionLabel("What leaves this phone", SectionPadding) }
        item {
            CardGroup {
                PrivacyRow("Your messages", "Sent straight from this phone to the model provider you pick. With an on-device model, nothing leaves.", LocusIcons.Chats)
                GroupDivider(inset = Insets.iconRow)
                PrivacyRow("Keys and passwords", "Encrypted with a key held in Android's secure hardware. The AI never sees them.", LocusIcons.Key)
                GroupDivider(inset = Insets.iconRow)
                PrivacyRow("Web, integrations and machines", "Only when a task uses them, and only to that service or machine.", LocusIcons.Globe)
                GroupDivider(inset = Insets.iconRow)
                PrivacyRow("Files, memory and chats", "Stay on this phone. Locus has no account and no server of its own.", LocusIcons.Folder)
                // Only in builds that have the check (docs/design/updates.md).
                if (updates.info?.availability?.canCheck == true) {
                    GroupDivider(inset = Insets.iconRow)
                    PrivacyRow(
                        "Update check",
                        "Off unless you turn it on: then about once a day one request to GitHub for the latest release, with nothing about you. Check now asks once.",
                        LocusIcons.Clock,
                    )
                }
            }
        }
        item { SectionNote("You can see every action Locus took in Activity.", NotePadding) }

        item { SectionLabel("Reliability", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Diagnostics",
                    subtitle = "Self-test, storage, and recent crashes and exits",
                    subtitleMaxLines = 2,
                    onClick = onOpenDiagnostics,
                    leading = { IconTile(LocusIcons.Tool) },
                    trailing = { RowChevron() },
                )
            }
        }

        item { SectionLabel("Setup", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Run setup again",
                    subtitle = "Walk through the first-run steps",
                    onClick = onRunSetup,
                    leading = { IconTile(LocusIcons.Play) },
                    trailing = { RowChevron() },
                )
            }
        }

        item { SectionLabel("About", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Locus",
                    subtitle = "Open source, local-first agent",
                    leading = { IconTile(LocusIcons.Info) },
                    trailing = { if (version != null) SupportingText(version, style = LocusType.mono) },
                )
            }
        }
    }
}

@Composable
private fun PrivacyRow(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    // A privacy promise is read in full: it wraps instead of being cut.
    ListRow(title = title, subtitle = body, titleMaxLines = 2, subtitleMaxLines = Int.MAX_VALUE, leading = { IconTile(icon) })
}
