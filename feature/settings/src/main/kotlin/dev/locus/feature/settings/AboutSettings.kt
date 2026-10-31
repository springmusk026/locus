package dev.locus.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote

/** Version, a plain account of what leaves the phone, and a way back into setup. */
@Composable
internal fun AboutSettings(onBack: () -> Unit, onRunSetup: () -> Unit) {
    val version = appVersion()
    SettingsPageScaffold("About & privacy", onBack) {
        item { SectionLabel("What leaves this phone", SectionPadding) }
        item {
            CardGroup {
                PrivacyRow("Your messages", "Sent straight from this phone to the model provider you pick. With an on-device model, nothing leaves.", LocusIcons.Chats)
                GroupDivider(inset = ROW_INSET)
                PrivacyRow("Keys and passwords", "Encrypted with a key held in Android's secure hardware. The AI never sees them.", LocusIcons.Key)
                GroupDivider(inset = ROW_INSET)
                PrivacyRow("Web, integrations and machines", "Only when a task uses them, and only to that service or machine.", LocusIcons.Globe)
                GroupDivider(inset = ROW_INSET)
                PrivacyRow("Files, memory and chats", "Stay on this phone. Locus has no account and no server of its own.", LocusIcons.Folder)
            }
        }
        item { SectionNote("You can see every action Locus took in Activity.", NotePadding) }

        item { SectionLabel("Setup", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Run setup again",
                    subtitle = "Walk through the first-run steps",
                    onClick = onRunSetup,
                    leading = { IconTile(LocusIcons.Restore) },
                    trailing = { Chevron() },
                )
            }
        }

        item { SectionLabel("About", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Locus",
                    subtitle = "Open source, local-first agent",
                    leading = { IconTile(LocusIcons.Sparkle) },
                    trailing = { if (version != null) Text(version, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
    }
}

@Composable
private fun PrivacyRow(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ListRow(title = title, subtitle = body, subtitleMaxLines = 3, leading = { IconTile(icon) })
}
