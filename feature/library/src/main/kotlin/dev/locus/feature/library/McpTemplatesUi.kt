package dev.locus.feature.library

import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.data.mcp.SavedKeys
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConnectorIcon
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.LINUX_INSTALL_PATH

/** Templates grouped for the picker, in [McpTemplate.Category] order. */
internal fun templateGroups(templates: List<McpTemplate> = McpTemplates.all): List<Pair<McpTemplate.Category, List<McpTemplate>>> =
    McpTemplate.Category.entries.mapNotNull { c -> templates.filter { it.category == c }.takeIf { it.isNotEmpty() }?.let { c to it } }

/** Step one of Add an MCP server: a known server, or a custom one. */
@Composable
internal fun McpTemplatePicker(onPick: (McpTemplate) -> Unit, onCustom: () -> Unit, onDismiss: () -> Unit) {
    LocusSheet(
        title = stringResource(R.string.library_connectors_add_mcp),
        subtitle = stringResource(R.string.library_mcp_picker_subtitle),
        onDismiss = onDismiss,
    ) {
        CardGroup {
            TemplateRow(stringResource(R.string.library_mcp_picker_custom_title), stringResource(R.string.library_mcp_picker_custom_subtitle), leading = { ConnectorIcon("custom") }, onClick = onCustom)
        }
        templateGroups().forEach { (category, templates) ->
            SectionLabel(category.label, Modifier.padding(top = Spacing.xs))
            CardGroup {
                templates.forEachIndexed { i, t ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    TemplateRow(t.name, stringResource(R.string.library_mcp_picker_template_subtitle, t.description, t.authSummary), leading = { ConnectorIcon(t.id) }) { onPick(t) }
                }
            }
        }
    }
}

@Composable
private fun TemplateRow(title: String, subtitle: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(start = Spacing.md)) {
            Text(title, style = LocusType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SupportingText(subtitle, maxLines = 3)
        }
    }
}

/** What the user fills in for a template: the URL (with options), a key, or a local server's variables. */
internal class TemplateForm(val template: McpTemplate) {
    var url by mutableStateOf(template.url)
    var key by mutableStateOf("")
    var env by mutableStateOf(template.local?.env.orEmpty().associateWith { "" })

    /**
     * The user tapped "Use the key saved for …": Locus copies the built-in's key inside the vault. Only
     * this flag is held here, never the key.
     */
    var useSavedKey by mutableStateOf(false)

    /** A saved key only goes to the template's own host; an edited host needs a pasted key. */
    val savedKeyBlocked: Boolean get() = useSavedKey && !SavedKeys.sameHost(template, url)

    val isValid: Boolean
        get() = when {
            template.local != null -> env.values.all { it.isNotBlank() }
            !url.startsWith("https://") || url.length <= "https://".length || url.any { it.isWhitespace() } -> false
            useSavedKey -> !savedKeyBlocked
            template.key?.optional != false || template.signIn -> true
            else -> key.isNotBlank()
        }

    fun envPairs(): List<Pair<String, String>> = env.entries.map { it.key to it.value }
}

/** Step two: what this server is, how it signs in, and the key if it needs one. */
@Composable
internal fun McpTemplateSheet(
    template: McpTemplate,
    linuxInstalled: Boolean,
    onAdd: (TemplateForm) -> Unit,
    onDismiss: () -> Unit,
    /** The built-in integration whose saved key this server can take ([SavedKeys.source]), when one is saved. */
    savedKeyFrom: Integration? = null,
) {
    val form = rememberSaveable(template.id, saver = androidx.compose.runtime.saveable.listSaver(
        save = { f -> listOf(f.url, f.key, f.useSavedKey.toString()) + f.env.flatMap { listOf(it.key, it.value) } },
        restore = { s ->
            TemplateForm(template).apply {
                url = s[0]; key = s[1]; useSavedKey = s[2].toBoolean(); env = s.drop(3).chunked(2).associate { (k, v) -> k to v }
            }
        },
    )) { TemplateForm(template) }
    if (savedKeyFrom == null && form.useSavedKey) form.useSavedKey = false
    val uriHandler = LocalUriHandler.current
    val action = when {
        template.local != null -> stringResource(R.string.library_action_add)
        form.useSavedKey -> stringResource(R.string.library_action_add)
        template.signIn && form.key.isBlank() -> stringResource(R.string.library_connectors_sign_in)
        else -> stringResource(R.string.library_action_add)
    }
    LocusSheet(
        title = template.name,
        subtitle = template.description,
        onDismiss = onDismiss,
        action = action,
        actionEnabled = form.isValid,
        onAction = { onAdd(form) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectorIcon(template.id)
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(template.byline, style = LocusType.rowSupporting)
                SupportingText(template.authSummary)
            }
        }
        template.note?.let { SupportingText(it) }
        val local = template.local
        if (local != null) {
            LocalTemplateFields(local, form, linuxInstalled)
        } else {
            OutlinedTextField(form.url, { form.url = it.trim() }, label = { Text(stringResource(R.string.library_connectors_custom_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (template.signIn) {
                Text(
                    template.key?.let { stringResource(R.string.library_mcp_sheet_sign_in_or_paste, template.vendor, it.label.lowercase()) }
                        ?: stringResource(R.string.library_mcp_sheet_sign_in, template.vendor),
                    style = LocusType.supporting,
                )
            }
            val savedFrom = savedKeyFrom?.takeIf { template.key != null }
            if (savedFrom != null) SavedKeyChoice(savedFrom, template, form)
            template.key?.takeUnless { form.useSavedKey }?.let { key ->
                OutlinedTextField(
                    form.key, { form.key = it },
                    label = { Text(if (key.optional || template.signIn) stringResource(R.string.library_mcp_sheet_key_optional, key.label) else key.label) },
                    supportingText = {
                        Text(
                            key.scopes?.let { stringResource(R.string.library_mcp_sheet_key_sent_as_needs, key.sentAs, it) }
                                ?: stringResource(R.string.library_mcp_sheet_key_sent_as, key.sentAs),
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            template.key?.let { key -> TextButton(onClick = { uriHandler.openUri(key.url) }) { Text(stringResource(R.string.library_mcp_sheet_get_token)) } }
            TextButton(onClick = { uriHandler.openUri(template.docsUrl) }) { Text(stringResource(R.string.library_mcp_sheet_docs)) }
        }
    }
}

/**
 * "Use the key saved for <Integration>": off until the user taps it, and then it says where the key
 * will also go. The key is copied inside the vault when the server is added; it never shows here.
 */
@Composable
private fun SavedKeyChoice(integration: Integration, template: McpTemplate, form: TemplateForm) {
    CardGroup {
        ListRow(
            title = stringResource(R.string.library_mcp_saved_key_title, integration.name),
            subtitle = stringResource(R.string.library_mcp_saved_key_subtitle),
            onClick = { form.useSavedKey = !form.useSavedKey },
            trailing = { Checkbox(checked = form.useSavedKey, onCheckedChange = { form.useSavedKey = it }) },
            subtitleMaxLines = 2,
        )
    }
    if (form.useSavedKey) {
        if (form.savedKeyBlocked) {
            Banner(
                stringResource(R.string.library_mcp_saved_key_blocked, integration.name, template.host),
                Tone.Caution,
            )
        } else {
            Text(savedKeyNote(LocalResources.current, integration, template), style = LocusType.supporting)
        }
    }
}

/** The confirmation under "Use the key saved for …": where the key will also be sent. */
internal fun savedKeyNote(resources: Resources, integration: Integration, template: McpTemplate): String =
    // How it's sent ("a bearer token", "the x-api-key header") is the template's, in core.
    resources.getString(R.string.library_mcp_saved_key_note, integration.name, template.host, template.key?.sentAs.toString())

@Composable
private fun ColumnScope.LocalTemplateFields(local: McpTemplate.Local, form: TemplateForm, linuxInstalled: Boolean) {
    if (!linuxInstalled) {
        Banner(
            stringResource(R.string.library_localmcp_linux_missing_body, LINUX_INSTALL_PATH),
            Tone.Caution,
            title = stringResource(R.string.library_localmcp_linux_missing_title),
        )
    }
    Text(stringResource(R.string.library_mcp_local_runs_inside), style = LocusType.supporting)
    Text(local.command, style = LocusType.code)
    SupportingText(stringResource(R.string.library_mcp_local_needs, local.needs))
    local.env.forEach { name ->
        OutlinedTextField(
            form.env[name].orEmpty(), { v -> form.env = form.env + (name to v) },
            label = { Text(local.label(name).removePrefix("your ").removePrefix("a ").replaceFirstChar { it.uppercase() }) },
            supportingText = { Text(stringResource(R.string.library_mcp_local_stored)) },
            singleLine = true,
            visualTransformation = if (name in local.plain) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
