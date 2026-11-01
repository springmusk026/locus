package dev.locus.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.data.mcp.SavedKeys
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.designsystem.ListRow
import androidx.compose.material3.Checkbox
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.ConnectorIcon
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.LINUX_INSTALL_PATH
import dev.locus.core.designsystem.Insets

/** Templates grouped for the picker, in [McpTemplate.Category] order. */
internal fun templateGroups(templates: List<McpTemplate> = McpTemplates.all): List<Pair<McpTemplate.Category, List<McpTemplate>>> =
    McpTemplate.Category.entries.mapNotNull { c -> templates.filter { it.category == c }.takeIf { it.isNotEmpty() }?.let { c to it } }

/** Step one of Add an MCP server: a known server, or a custom one. */
@Composable
internal fun McpTemplatePicker(onPick: (McpTemplate) -> Unit, onCustom: () -> Unit, onDismiss: () -> Unit) {
    LocusSheet(
        title = "Add an MCP server",
        subtitle = "Pick a known server, or add any other. Keys stay encrypted on this phone; the AI never sees them.",
        onDismiss = onDismiss,
    ) {
        CardGroup {
            TemplateRow("Custom server", "Any MCP server: its URL, or a command to run in Linux", leading = { ConnectorIcon("custom") }, onClick = onCustom)
        }
        templateGroups().forEach { (category, templates) ->
            SectionLabel(category.label, Modifier.padding(top = Spacing.xs))
            CardGroup {
                templates.forEachIndexed { i, t ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    TemplateRow(t.name, "${t.description} · ${t.authSummary}", leading = { ConnectorIcon(t.id) }) { onPick(t) }
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
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
        template.local != null -> "Add"
        form.useSavedKey -> "Add"
        template.signIn && form.key.isBlank() -> "Sign in"
        else -> "Add"
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
                Text(if (template.category == McpTemplate.Category.LOCAL) "Reference server from the MCP project" else "Official server by ${template.vendor}", style = MaterialTheme.typography.bodyMedium)
                Text(template.authSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        template.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val local = template.local
        if (local != null) {
            LocalTemplateFields(local, form, linuxInstalled)
        } else {
            OutlinedTextField(form.url, { form.url = it.trim() }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (template.signIn) {
                Text(
                    "Locus opens ${template.vendor}'s sign-in in your browser and keeps the sign-in encrypted on this phone." +
                        (template.key?.let { " Or paste a ${it.label.lowercase()} instead." } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val savedFrom = savedKeyFrom?.takeIf { template.key != null }
            if (savedFrom != null) SavedKeyChoice(savedFrom, template, form)
            template.key?.takeUnless { form.useSavedKey }?.let { key ->
                OutlinedTextField(
                    form.key, { form.key = it },
                    label = { Text(if (key.optional || template.signIn) "${key.label} (optional)" else key.label) },
                    supportingText = { Text("Sent as ${key.sentAs}." + (key.scopes?.let { " Needs: $it." } ?: "")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            template.key?.let { key -> TextButton(onClick = { uriHandler.openUri(key.url) }) { Text("Get a token") } }
            TextButton(onClick = { uriHandler.openUri(template.docsUrl) }) { Text("Docs") }
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
            title = "Use the key saved for ${integration.name}",
            subtitle = "From Settings → Integrations, instead of pasting one",
            onClick = { form.useSavedKey = !form.useSavedKey },
            trailing = { Checkbox(checked = form.useSavedKey, onCheckedChange = { form.useSavedKey = it }) },
            subtitleMaxLines = 2,
        )
    }
    if (form.useSavedKey) {
        if (form.savedKeyBlocked) {
            Banner(
                "The saved ${integration.name} key only goes to ${template.host}. Put that address back, or paste a key instead.",
                Tone.Caution,
            )
        } else {
            Text(savedKeyNote(integration, template), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The confirmation under "Use the key saved for …": where the key will also be sent. */
internal fun savedKeyNote(integration: Integration, template: McpTemplate): String =
    "Your ${integration.name} key will also be sent to ${template.host}, as ${template.key?.sentAs}. Locus copies it inside the encrypted vault; " +
        "neither you nor the AI sees it. Changing or removing it in Settings later doesn't change this copy."

@Composable
private fun ColumnScope.LocalTemplateFields(local: McpTemplate.Local, form: TemplateForm, linuxInstalled: Boolean) {
    if (!linuxInstalled) {
        Banner(
            "Local servers run in Locus's Linux system, which isn't installed yet. Install it from $LINUX_INSTALL_PATH. You can add the server now; it can't start until then.",
            Tone.Caution,
            title = "Linux isn't installed",
        )
    }
    Text("Runs inside Locus's Linux system when a chat needs it.", style = MaterialTheme.typography.bodySmall)
    Text(local.command, style = CodeTextStyle)
    Text("Needs ${local.needs}. Locus doesn't install it: do that once with run_command or the Linux terminal.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    local.env.forEach { name ->
        OutlinedTextField(
            form.env[name].orEmpty(), { v -> form.env = form.env + (name to v) },
            label = { Text(name) },
            supportingText = { Text("Stored encrypted and given only to this server's process.") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
