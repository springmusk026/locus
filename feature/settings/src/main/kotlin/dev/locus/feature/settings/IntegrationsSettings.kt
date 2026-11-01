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
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.IntegrationIcon
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
    telegram: TelegramActions = TelegramActions.None,
) {
    var keyFor by remember { mutableStateOf<Integration?>(null) }

    SettingsPageScaffold("Integrations & connectors", onBack) {
        item { SectionLabel("Integrations", SectionPadding) }
        item {
            CardGroup {
                state.integrations.forEachIndexed { i, (integration, hasKey) ->
                    if (i > 0) GroupDivider(inset = ROW_INSET)
                    ListRow(
                        title = integration.name,
                        subtitle = integration.summary,
                        onClick = { keyFor = integration },
                        leading = { IntegrationIcon(integration.id) },
                        trailing = {
                            if (hasKey) StatusPill("Connected", Tone.Live, dot = true)
                            else StatusPill(if (integration.id in SET_UP) "Set up" else "Add key", Tone.Neutral)
                        },
                    )
                }
            }
        }
        item { SectionNote(searchOrderNote(), NotePadding) }
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

    keyFor?.takeIf { it.id == "telegram" }?.let { integration ->
        TelegramSheet(integration, state.integrations.any { it.first == integration && it.second }, telegram, onDismiss = { keyFor = null })
    }

    keyFor?.takeIf { it.id != "telegram" }?.let { integration ->
        IntegrationKeySheet(
            integration,
            hasKey = state.integrations.any { it.first == integration && it.second },
            onSave = { onSetIntegrationKey(integration, it); keyFor = null },
            onDismiss = { keyFor = null },
        )
    }
}

/** Integrations whose "key" is a whole setup (a bot, a topic, a mailbox) rather than one pasted key. */
private val SET_UP = setOf("telegram", "ntfy", "email")

/** The web search fallback order, named from [Integrations.searchOrder]. */
internal fun searchOrderNote(): String =
    "Web search uses the first connected search service, in this order: ${Integrations.searchOrder.joinToString { it.name }}. " +
        "Keys and tokens stay encrypted on this phone; the AI never sees them."

/** Adds, replaces or removes one integration's key; [onSave] with null removes it. */
@Composable
private fun IntegrationKeySheet(integration: Integration, hasKey: Boolean, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var value by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    LocusSheet(
        title = integration.name,
        subtitle = integration.summary,
        onDismiss = onDismiss,
        action = "Save",
        actionEnabled = value.isNotBlank(),
        onAction = { onSave(value) },
    ) {
        Text(integration.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value, { value = it },
            label = { Text(if (hasKey) "${integration.keyLabel} (saved — type to replace)" else integration.keyLabel) },
            supportingText = integration.keyHelp?.let { { Text(it) } },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Text("Stored encrypted on this phone. The AI never sees it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = { uriHandler.openUri(integration.signupUrl) }) { Text(integration.linkLabel) }
            Box(Modifier.weight(1f))
            if (hasKey) TextButton(onClick = { confirmRemove = true }) { Text("Remove key", color = Locus.colors.critical) }
        }
    }
    if (confirmRemove) {
        LocusSheet(
            title = "Remove the ${integration.name} key?",
            subtitle = "It's deleted from this phone, and the agent can't use ${integration.name} until you add one again.",
            onDismiss = { confirmRemove = false },
            action = "Remove",
            actionTone = Tone.Critical,
            onAction = { confirmRemove = false; onSave(null) },
        ) {}
    }
}
