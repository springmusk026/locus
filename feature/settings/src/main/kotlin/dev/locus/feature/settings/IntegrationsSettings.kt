package dev.locus.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.LogoTile
import dev.locus.core.designsystem.integrationLogo
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone

/** Built-in integrations that need a key, plus the way into connectors and MCP servers. */
@Composable
internal fun IntegrationsSettings(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSetIntegrationKey: (Integration, String?) -> Unit,
    onOpenConnectors: () -> Unit,
) {
    var keyFor by remember { mutableStateOf<Integration?>(null) }
    val uriHandler = LocalUriHandler.current

    SettingsPageScaffold("Integrations & connectors", onBack) {
        item { SectionLabel("Integrations", SectionPadding) }
        item {
            CardGroup {
                state.integrations.forEachIndexed { i, (integration, hasKey) ->
                    if (i > 0) GroupDivider(inset = ROW_INSET)
                    ListRow(
                        title = integration.name,
                        subtitle = integration.description,
                        subtitleMaxLines = 2,
                        onClick = { keyFor = integration },
                        leading = {
                            val logo = integrationLogo(integration.id)
                            if (logo != null) LogoTile(logo, size = 36.dp)
                            else IconTile(
                                when (integration.id) {
                                    "ntfy" -> LocusIcons.Phone
                                    "email" -> LocusIcons.Note
                                    else -> LocusIcons.Plug
                                },
                            )
                        },
                        trailing = { if (hasKey) StatusPill("Connected", Tone.Live, dot = true) else StatusPill("Add key", Tone.Neutral) },
                    )
                }
            }
        }
        item {
            SectionNote(
                "Web search uses the first connected search service, in this order: Firecrawl, Tavily, Brave, Exa. Keys and tokens stay encrypted on this phone; the AI never sees them.",
                NotePadding,
            )
        }
        item { SectionLabel("Connectors", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Connectors & MCP servers",
                    subtitle = "Add MCP servers and review connectors Locus drafted",
                    onClick = onOpenConnectors,
                    leading = { IconTile(LocusIcons.Plug) },
                    trailing = { Chevron() },
                )
            }
        }
    }

    keyFor?.let { integration ->
        var value by remember { mutableStateOf("") }
        LocusSheet(
            title = integration.name,
            subtitle = integration.description,
            onDismiss = { keyFor = null },
            action = "Save",
            actionEnabled = value.isNotBlank(),
            onAction = { onSetIntegrationKey(integration, value); keyFor = null },
        ) {
            OutlinedTextField(
                value, { value = it }, label = { Text(integration.keyLabel) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Text("Stored encrypted on this phone. The AI never sees it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { uriHandler.openUri(integration.signupUrl) }) { Text(integration.linkLabel) }
                Box(Modifier.weight(1f))
                TextButton(onClick = { onSetIntegrationKey(integration, null); keyFor = null }) { Text("Remove key", color = Locus.colors.critical) }
            }
        }
    }
}
