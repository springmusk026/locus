package dev.locus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.data.mcp.McpFirstStatus
import dev.locus.core.agent.integrations.McpFirst
import androidx.compose.runtime.LaunchedEffect
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
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
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.ui.TelegramActions
import dev.locus.core.ui.TelegramSheet
import dev.locus.core.ui.EmailActions
import dev.locus.core.ui.EmailSheet
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.ui.authSummaryText
import dev.locus.core.ui.descriptionText
import dev.locus.core.ui.displayName
import dev.locus.core.ui.keyHelpText
import dev.locus.core.ui.keyLabelText
import dev.locus.core.ui.linkLabelText
import dev.locus.core.ui.summaryText

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
    email: EmailActions = EmailActions.None,
) {
    var keyFor by remember { mutableStateOf<Integration?>(null) }
    LaunchedEffect(Unit) { onCheckMcp() }

    SettingsPageScaffold(stringResource(R.string.settings_integrations_title), onBack) {
        item { SectionLabel(stringResource(R.string.settings_integrations_section), SectionPadding) }
        item {
            CardGroup {
                state.integrations.forEachIndexed { i, (integration, hasKey) ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    val mcpHint: (@Composable () -> Unit)? = when {
                        state.mcpFirst[integration.id]?.usingMcp == true -> ({ McpHint(stringResource(R.string.settings_integrations_mcp_using), Tone.Brand) })
                        officialMcp(integration.id) != null -> ({ McpHint(stringResource(R.string.settings_integrations_mcp_available), Tone.Neutral) })
                        else -> null
                    }
                    val status = @Composable {
                        if (hasKey) StatusPill(stringResource(R.string.settings_integrations_connected), Tone.Live, dot = true)
                        else StatusPill(stringResource(if (integration.id in SET_UP) R.string.settings_integrations_set_up else R.string.settings_integrations_add_key), Tone.Neutral)
                    }
                    // At a large font the status joins the chips under the text, so the name keeps the row's width.
                    val large = LocalDensity.current.fontScale > LARGE_FONT
                    val res = LocalResources.current
                    ListRow(
                        title = integration.displayName(res),
                        subtitle = integration.summaryText(res),
                        titleMaxLines = 2,
                        subtitleMaxLines = if (large) Int.MAX_VALUE else 2,
                        onClick = { keyFor = integration },
                        leading = { IntegrationIcon(integration.id) },
                        // A fact about the row, not a link: a quiet chip (the sheet has the way to it).
                        meta = when {
                            large -> ({
                                FlowRow(Modifier.padding(top = Spacing.xxs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    status()
                                    mcpHint?.invoke()
                                }
                            })
                            else -> mcpHint
                        },
                        trailing = if (large) null else ({ status() }),
                    )
                }
            }
        }
        item { SectionNote(searchOrderNote(), NotePadding) }
        item { SectionLabel(stringResource(R.string.settings_integrations_connectors_section), SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = stringResource(R.string.settings_integrations_connectors_title),
                    subtitle = stringResource(R.string.settings_integrations_connectors_subtitle),
                    onClick = onOpenConnectors,
                    leading = { IconTile(LocusIcons.Plug) },
                    trailing = { RowChevron() },
                )
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = stringResource(R.string.settings_integrations_home_title),
                    subtitle = stringResource(R.string.settings_integrations_home_subtitle),
                    subtitleMaxLines = 2,
                    onClick = { onUseMcpTemplate(dev.locus.core.data.connectors.ConnectorTemplates.PICKER) },
                    leading = { IconTile(LocusIcons.Server) },
                    trailing = { RowChevron() },
                )
            }
        }
    }

    keyFor?.takeIf { it.id == "telegram" }?.let { integration ->
        TelegramSheet(integration, state.integrations.any { it.first == integration && it.second }, telegram, onDismiss = { keyFor = null })
    }

    keyFor?.takeIf { it.id == Integrations.email.id }?.let { integration ->
        EmailSheet(integration, state.integrations.any { it.first == integration && it.second }, email, onDismiss = { keyFor = null })
    }

    keyFor?.takeIf { it.id != "telegram" && it.id != Integrations.email.id }?.let { integration ->
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
private fun McpHint(text: String, tone: Tone) {
    LocusChip(text, tone = tone)
}

/**
 * For an integration that has both its key and the vendor's MCP server: which one new chats use, and
 * the switch back. Chats that already exist keep their tools, so the switch only says "new chats".
 */
@Composable
private fun McpFirstCard(integration: Integration, status: McpFirstStatus, onSetUseMcp: (Boolean) -> Unit) {
    val server = status.connector?.name ?: "the MCP server"
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(mcpFirstTitle(status), style = LocusType.cardTitle)
            SupportingText(mcpFirstBody(integration, status, server))
            when {
                status.usingMcp -> TextButton(onClick = { onSetUseMcp(false) }) { Text(stringResource(R.string.settings_integrations_use_builtin)) }
                status.keptBuiltIn -> TextButton(onClick = { onSetUseMcp(true) }) { Text(stringResource(R.string.settings_integrations_use_mcp)) }
            }
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
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            // Search keys also power web_search's fallback chain, which an MCP server can't join.
            val search = integration in Integrations.searchOrder
            Text(
                stringResource(if (search) R.string.settings_integrations_card_also else R.string.settings_integrations_card_recommended, template.vendor),
                style = LocusType.cardTitle,
            )
            SupportingText(
                if (search) {
                    stringResource(R.string.settings_integrations_card_search_body, template.vendor, template.authSummaryText(LocalResources.current))
                } else {
                    stringResource(R.string.settings_integrations_card_body, template.vendor, template.authSummaryText(LocalResources.current), integration.displayName(LocalResources.current))
                },
            )
            Button(onClick = onUse) { Text(stringResource(R.string.settings_integrations_card_use)) }
        }
    }
}

/** From this font scale a row's status moves under its text. */
private const val LARGE_FONT = 1.3f

/** Integrations whose "key" is a whole setup (a bot, a topic, a mailbox) rather than one pasted key. */
private val SET_UP = setOf("telegram", "ntfy", "email")

/** The web search fallback order, named from [Integrations.searchOrder]. */
@Composable
internal fun searchOrderNote(): String =
    stringResource(R.string.settings_integrations_search_order, Integrations.searchOrder.joinToString { it.name })

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
    val res = LocalResources.current
    var value by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    LocusSheet(
        // No subtitle: the description below starts with the same words.
        title = integration.displayName(res),
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_integrations_save),
        actionEnabled = value.isNotBlank(),
        onAction = { onSave(value) },
    ) {
        SupportingText(integration.descriptionText(res))
        if (mcpFirst != null) McpFirstCard(integration, mcpFirst, onSetUseMcp)
        else officialMcp(integration.id)?.let { template -> onUseMcp?.let { UseMcpCard(integration, template, it) } }
        OutlinedTextField(
            value, { value = it },
            label = { Text(integration.keyLabelText(res).let { if (hasKey) stringResource(R.string.settings_integrations_key_saved, it) else it }) },
            supportingText = integration.keyHelpText(res)?.let { { Text(it) } },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        SupportingText(stringResource(R.string.settings_integrations_key_stored))
        // Side by side while they fit; at a large font "Remove key" moves to its own line instead of breaking.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { uriHandler.openUri(integration.signupUrl) }) { Text(integration.linkLabelText(res)) }
            if (hasKey) TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.settings_integrations_remove_key), color = Locus.colors.critical) }
        }
    }
    if (confirmRemove) {
        ConfirmSheet(
            title = stringResource(R.string.settings_integrations_remove_title, integration.displayName(res)),
            message = stringResource(R.string.settings_integrations_remove_message, integration.displayName(res)),
            confirmLabel = stringResource(R.string.settings_integrations_remove_confirm),
            tone = Tone.Critical,
            onConfirm = { confirmRemove = false; onSave(null) },
            onDismiss = { confirmRemove = false },
        )
    }
}
