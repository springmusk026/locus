package dev.locus.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.data.mcp.McpFirstStatus
import dev.locus.core.agent.integrations.McpFirst
import androidx.compose.runtime.LaunchedEffect
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
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
import dev.locus.core.ui.TelegramActions
import dev.locus.core.ui.TelegramSheet
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.RowChevron

/** Built-in integrations that need a key, plus the way into connectors and MCP servers. */
@Composable
internal fun IntegrationsSettings(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSetIntegrationKey: (Integration, String?) -> Unit,
    onOpenConnectors: () -> Unit,
    onUseMcpTemplate: (String) -> Unit = {},
    onSetUseMcp: (String, Boolean) -> Unit = { _, _ -> },
    /** Tries MCP servers set up next to their built-in, so the rows can say whether they work. */
    onCheckMcp: () -> Unit = {},
    telegram: TelegramActions = TelegramActions.None,
) {
    var keyFor by remember { mutableStateOf<Integration?>(null) }
    LaunchedEffect(Unit) { onCheckMcp() }

    SettingsPageScaffold("Integrations & connectors", onBack) {
        item { SectionLabel("Integrations", SectionPadding) }
        item {
            CardGroup {
                state.integrations.forEachIndexed { i, (integration, hasKey) ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    ListRow(
                        title = integration.name,
                        subtitle = integration.summary,
                        onClick = { keyFor = integration },
                        leading = { IntegrationIcon(integration.id) },
                        meta = when {
                            state.mcpFirst[integration.id]?.usingMcp == true -> ({ McpHint(USING_MCP) })
                            officialMcp(integration.id) != null -> ({ McpHint("Official MCP server available") })
                            else -> null
                        },
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
                    trailing = { RowChevron() },
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
            onUseMcp = officialMcp(integration.id)?.let { t -> { keyFor = null; onUseMcpTemplate(t.id) } },
            mcpFirst = state.mcpFirst[integration.id]?.takeIf { it.both },
            onSetUseMcp = { use -> onSetUseMcp(integration.id, use) },
        )
    }
}

/**
 * The official MCP server recommended over [integrationId]'s built-in tools, if there is one. See
 * docs/design/mcp-connectors.md for why some built-ins have none (Vercel, Brave, ntfy, Telegram, Email).
 */
internal fun officialMcp(integrationId: String): McpTemplate? =
    McpTemplates.forIntegration(integrationId)?.takeIf { it.local == null }

/** What the row says when new chats use the MCP server instead of the built-in tools. */
internal const val USING_MCP = "Using the MCP server in new chats"

@Composable
private fun McpHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

/**
 * For an integration that has both its key and the vendor's MCP server: which one new chats use, and
 * the switch back. Chats that already exist keep their tools, so the switch only says "new chats".
 */
@Composable
private fun McpFirstCard(integration: Integration, status: McpFirstStatus, onSetUseMcp: (Boolean) -> Unit) {
    val server = status.connector?.name ?: "the MCP server"
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Radius.control)).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(mcpFirstTitle(status), style = MaterialTheme.typography.titleSmall)
        Text(mcpFirstBody(integration, status, server), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            status.usingMcp -> TextButton(onClick = { onSetUseMcp(false) }) { Text("Use the built-in tools instead") }
            status.keptBuiltIn -> TextButton(onClick = { onSetUseMcp(true) }) { Text("Use the MCP server in new chats") }
        }
    }
}

internal fun mcpFirstTitle(status: McpFirstStatus): String = when {
    status.usingMcp -> USING_MCP
    status.keptBuiltIn -> "Using the built-in tools in new chats"
    else -> "The MCP server isn't working"
}

internal fun mcpFirstBody(integration: Integration, status: McpFirstStatus, server: String): String {
    val kept = McpFirst.alwaysKept.filter { it.startsWith(integration.id + "_") }
    val keeps = if (kept.isEmpty()) "" else " ${kept.joinToString(" and ")} stays, since it uploads from your workspace."
    return when {
        status.usingMcp -> "New chats leave out the built-in ${integration.name} tools and use “$server”.$keeps Chats you already have keep the tools they started with."
        status.keptBuiltIn -> "“$server” is connected too, but new chats use the built-in ${integration.name} tools, as you chose."
        else -> "“$server” ${status.problem?.let { "failed: ${it.take(160)}" } ?: "can't be used yet"}. Until it works, new chats use the built-in ${integration.name} tools."
    }
}

/** The recommendation inside an integration's sheet, with the way to the template. */
@Composable
private fun UseMcpCard(integration: Integration, template: McpTemplate, onUse: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Radius.control)).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        // Search keys also power web_search's fallback chain, which an MCP server can't join.
        val search = integration in Integrations.searchOrder
        Text(
            if (search) "Also available: ${template.vendor}'s MCP server" else "Recommended: ${template.vendor}'s MCP server",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            if (search) {
                "Adds the rest of ${template.vendor}'s tools, such as fetching and crawling pages. Web search keeps using the key saved here. ${template.authSummary}."
            } else {
                "Maintained by ${template.vendor} and covers more than these built-in tools. ${template.authSummary}. " +
                    "The built-in ${integration.name} tools keep working if you stay with them."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onUse) { Text("Use the official MCP server") }
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
private fun IntegrationKeySheet(
    integration: Integration,
    hasKey: Boolean,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** Opens the official MCP server's template; null when there's none to recommend. */
    onUseMcp: (() -> Unit)? = null,
    /** Set when this integration has both its key and the vendor's MCP server. */
    mcpFirst: McpFirstStatus? = null,
    onSetUseMcp: (Boolean) -> Unit = {},
) {
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
        if (mcpFirst != null) McpFirstCard(integration, mcpFirst, onSetUseMcp)
        else officialMcp(integration.id)?.let { template -> onUseMcp?.let { UseMcpCard(integration, template, it) } }
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
