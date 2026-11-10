package dev.locus.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.locus.core.agent.home.Connector
import dev.locus.core.data.connectors.ConnectionCheck
import dev.locus.core.data.connectors.ConnectorTemplate
import dev.locus.core.data.connectors.ConnectorTemplates
import dev.locus.core.data.mcp.HomeAssistantSafety
import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConnectorIcon
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocalNetworkGate
import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.PasswordField
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.color
import dev.locus.core.model.ServiceAddress

/** The MCP templates the service picker also offers: the personal ones, set up like a service. */
internal val personalMcpTemplates: List<McpTemplate> get() = McpTemplates.all.filter { it.category == McpTemplate.Category.HOME }

/** Step one of Add a service: a self-hosted or personal service from the catalogue. */
@Composable
internal fun ServicePicker(onPickService: (ConnectorTemplate) -> Unit, onPickMcp: (McpTemplate) -> Unit, onDismiss: () -> Unit) {
    LocusSheet(
        title = stringResource(R.string.library_connectors_add_service),
        subtitle = stringResource(R.string.library_service_picker_subtitle),
        onDismiss = onDismiss,
    ) {
        SectionLabel(stringResource(R.string.library_service_picker_home))
        CardGroup {
            personalMcpTemplates.forEachIndexed { i, t ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                PickerRow(t.name, t.description, t.authSummary, leading = { ConnectorIcon(t.id, name = t.name) }) { onPickMcp(t) }
            }
        }
        SectionLabel(stringResource(R.string.library_service_picker_self_hosted), Modifier.padding(top = Spacing.xs))
        CardGroup {
            ConnectorTemplates.all.forEachIndexed { i, t ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                PickerRow(t.name, t.description, stringResource(R.string.library_service_picker_needs, sentenceLower(t.auth.label)), leading = { ConnectorIcon(t.id, name = t.name) }) { onPickService(t) }
            }
        }
    }
}

/**
 * A picker row: what the service is, then what it needs on its own line, so the requirement is never the
 * part an ellipsis cuts. At a large font nothing is cut.
 */
@Composable
private fun PickerRow(title: String, description: String, needs: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    val large = LocalDensity.current.fontScale >= 1.5f
    ListRow(
        title = title,
        subtitle = description,
        subtitleMaxLines = if (large) Int.MAX_VALUE else 2,
        titleMaxLines = 2,
        meta = { SupportingText(needs) },
        leading = leading,
        onClick = onClick,
    )
}

/** "Access token" → "access token", but "API key" stays: an acronym keeps its capitals. */
internal fun sentenceLower(label: String): String {
    val first = label.substringBefore(' ')
    return if (first.length > 1 && first.all { it.isUpperCase() }) label else label.replaceFirstChar { it.lowercase() }
}

/** What the user types for a service: its address, a user name when it needs one, and the key. */
internal class ServiceForm(initialAddress: String = "") {
    var address by mutableStateOf(initialAddress)
    var username by mutableStateOf("")
    var key by mutableStateOf("")

    val checked: ServiceAddress.Checked get() = ServiceAddress.check(address)

    fun ready(template: ConnectorTemplate): Boolean =
        checked.ok && key.isNotBlank() && (!template.auth.needsUsername || username.isNotBlank())

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<ServiceForm, String>(
            save = { listOf(it.address, it.username, it.key) },
            restore = { s -> ServiceForm(s[0]).apply { username = s[1]; key = s[2] } },
        )
    }
}

/** Step two for an HTTP service: its address and key, Test connection, and what it can do. */
@Composable
internal fun ServiceTemplateSheet(
    template: ConnectorTemplate,
    setup: ServiceSetup,
    localNetwork: LocalNetworkGate,
    onTest: (address: String, username: String, key: String) -> Unit,
    onAdd: (address: String, username: String, key: String) -> Unit,
    onEdited: () -> Unit,
    onDismiss: () -> Unit,
    initialAddress: String = "",
    /** What the fields start with (renders); null starts from [initialAddress]. */
    initialForm: ServiceForm? = null,
) {
    val form = rememberSaveable(template.id, saver = ServiceForm.Saver) { initialForm ?: ServiceForm(initialAddress) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(setup.added) { if (setup.added) onDismiss() }
    val busy = setup.testing || setup.adding
    LocusSheet(
        title = template.name,
        subtitle = template.description,
        onDismiss = onDismiss,
        action = stringResource(if (setup.adding) R.string.library_service_adding else R.string.library_action_add),
        actionEnabled = form.ready(template) && !busy,
        onAction = { form.checked.url?.let { url -> localNetwork.run(url) { onAdd(form.address, form.username, form.key) } } },
    ) {
        AddressField(form, template.hostHint, localNetwork) { form.address = it; onEdited() }
        val auth = template.auth
        if (auth.needsUsername) {
            OutlinedTextField(
                form.username, { form.username = it.trim(); onEdited() },
                label = { Text(auth.usernameLabel ?: stringResource(R.string.library_service_user_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PasswordField(
            form.key, { form.key = it; onEdited() },
            label = auth.label,
            supportingText = stringResource(R.string.library_service_key_hint, auth.where, auth.sentAs),
        )
        template.note?.let { SupportingText(it) }
        TestButton(enabled = form.ready(template) && !busy, testing = setup.testing) {
            form.checked.url?.let { url -> localNetwork.run(url) { onTest(form.address, form.username, form.key) } }
        }
        ConnectionResult(setup)
        OperationsSummary(template)
        TextButton(onClick = { uriHandler.openUri(template.docsUrl) }) { Text(stringResource(R.string.library_service_api_docs)) }
    }
}

/** The address field with its check: an error, the LAN warning, or Android's local network note. */
@Composable
private fun ColumnScope.AddressField(form: ServiceForm, hint: String, localNetwork: LocalNetworkGate, onChange: (String) -> Unit) {
    val checked = form.checked
    val showError = form.address.isNotBlank() && checked.error != null
    OutlinedTextField(
        form.address, { onChange(it.trim()) },
        label = { Text(stringResource(R.string.library_service_address_label)) },
        placeholder = { Text(hint) },
        singleLine = true,
        isError = showError,
        supportingText = { Text(if (showError) checked.error.orEmpty() else stringResource(R.string.library_service_address_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    checked.warning?.let { Banner(it, Tone.Caution, title = stringResource(R.string.library_service_not_encrypted)) }
    checked.url?.let { LocalNetworkNote(it, localNetwork) }
}

@Composable
private fun TestButton(enabled: Boolean, testing: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = enabled, onClick = onClick) {
            if (testing) {
                InlineSpinner(size = IconSize.small)
                Spacer(Modifier.size(Spacing.sm))
            }
            Text(stringResource(R.string.library_service_test))
        }
    }
}

/** The outcome of Test connection or Add, as the email sheet shows its checks. */
@Composable
internal fun ConnectionResult(setup: ServiceSetup) {
    setup.error?.let { Banner(it.asString(), Tone.Critical, title = stringResource(R.string.library_service_add_failed)) }
    val result = setup.result
    if (!setup.testing && result == null) return
    val pill = @Composable {
        when (result) {
            null -> InlineSpinner(size = IconSize.small, contentDescription = stringResource(R.string.library_service_testing))
            is ConnectionCheck.Passed -> StatusPill(stringResource(R.string.library_service_result_works), Tone.Live)
            is ConnectionCheck.Failed -> StatusPill(stringResource(R.string.library_service_result_failed), Tone.Critical)
        }
    }
    val large = LocalDensity.current.fontScale >= 1.5f
    CardGroup {
        ListRow(
            title = stringResource(R.string.library_service_connection),
            subtitle = when (result) {
                null -> stringResource(R.string.library_service_trying)
                is ConnectionCheck.Passed -> result.detail
                is ConnectionCheck.Failed -> result.message.ifBlank { stringResource(R.string.library_service_test_failed_body) }
            },
            subtitleMaxLines = Int.MAX_VALUE,
            meta = if (large) ({ Box(Modifier.padding(top = Spacing.xs)) { pill() } }) else null,
            leading = {
                when (result) {
                    null -> IconTile(LocusIcons.Server)
                    is ConnectionCheck.Passed -> IconTile(LocusIcons.Check, tint = Tone.Live.color())
                    is ConnectionCheck.Failed -> IconTile(LocusIcons.Error, tint = Tone.Critical.color())
                }
            },
            trailing = if (large) null else ({ pill() }),
        )
    }
}

/** What the connector can do, and which calls ask first. */
@Composable
private fun OperationsSummary(template: ConnectorTemplate) {
    val reads = template.operations.filter { it.sideEffect == "read" }.map { it.name.replace('_', ' ') }
    val writes = template.operations.filter { it.sideEffect == "write" || it.sideEffect == "destructive" }.map { it.name.replace('_', ' ') }
    val outward = template.outward.map { it.replace('_', ' ') }
    SectionLabel(stringResource(R.string.library_service_can_do), Modifier.padding(top = Spacing.xs))
    CardGroup {
        ListRow(title = stringResource(R.string.library_service_reads), subtitle = stringResource(R.string.library_service_reads_body, reads.joinToString(", ")), subtitleMaxLines = Int.MAX_VALUE, leading = { IconTile(LocusIcons.Eye) })
        if (writes.isNotEmpty()) {
            GroupDivider(inset = Insets.iconRow)
            ListRow(title = stringResource(R.string.library_service_changes), subtitle = stringResource(R.string.library_service_changes_body, writes.joinToString(", ")), subtitleMaxLines = Int.MAX_VALUE, leading = { IconTile(LocusIcons.Edit) })
        }
        if (outward.isNotEmpty()) {
            GroupDivider(inset = Insets.iconRow)
            ListRow(title = stringResource(R.string.library_service_publishes), subtitle = stringResource(R.string.library_service_publishes_body, outward.joinToString(", ")), subtitleMaxLines = Int.MAX_VALUE, leading = { IconTile(LocusIcons.Share) })
        }
    }
}

/** The Home Assistant setup: address, token, the Assist-only or full endpoint, and what always asks. */
@Composable
internal fun HomeAssistantSheet(
    template: McpTemplate,
    setup: ServiceSetup,
    localNetwork: LocalNetworkGate,
    onTest: (address: String, token: String) -> Unit,
    onAdd: (address: String, full: Boolean, token: String, rules: Connector.HomeAssistantRules) -> Unit,
    onEdited: () -> Unit,
    onDismiss: () -> Unit,
    /** What the fields start with (renders); null starts empty. */
    initialForm: ServiceForm? = null,
) {
    val hosted = template.selfHosted ?: return
    val form = rememberSaveable(template.id, saver = ServiceForm.Saver) { initialForm ?: ServiceForm() }
    var full by rememberSaveable { mutableStateOf(false) }
    val rules = rememberSaveable(saver = RulesForm.Saver) { RulesForm(Connector.HomeAssistantRules()) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(setup.added) { if (setup.added) onDismiss() }
    val busy = setup.testing || setup.adding
    val ready = form.checked.ok && form.key.isNotBlank() && rules.parsed != null && !busy
    LocusSheet(
        title = template.name,
        subtitle = template.description,
        onDismiss = onDismiss,
        action = stringResource(if (setup.adding) R.string.library_service_adding else R.string.library_action_add),
        actionEnabled = ready,
        onAction = {
            val parsed = rules.parsed ?: return@LocusSheet
            form.checked.url?.let { url -> localNetwork.run(url) { onAdd(form.address, full, form.key, parsed) } }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectorIcon(template.id, name = template.name)
            androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(template.byline, style = LocusType.rowSupporting)
                SupportingText(template.authSummary)
            }
        }
        template.note?.let { SupportingText(it) }
        AddressField(form, hosted.hostHint, localNetwork) { form.address = it; onEdited() }
        PasswordField(
            form.key, { form.key = it; onEdited() },
            label = template.key?.label ?: stringResource(R.string.library_service_token_label),
            supportingText = stringResource(R.string.library_service_bearer_hint, template.key?.scopes?.replaceFirstChar { it.uppercase() }.orEmpty()),
        )
        TestButton(enabled = form.checked.ok && form.key.isNotBlank() && !busy, testing = setup.testing) {
            form.checked.url?.let { url -> localNetwork.run(url) { onTest(form.address, form.key) } }
        }
        ConnectionResult(setup)
        CardGroup {
            SwitchRow(
                title = stringResource(R.string.library_service_full_access),
                subtitle = if (full) hosted.fullWarning.orEmpty() else stringResource(R.string.library_service_full_access_off, hosted.path),
                checked = full,
                onCheckedChange = { full = it },
            )
        }
        if (full) Banner(hosted.fullWarning.orEmpty(), Tone.Caution, title = stringResource(R.string.library_service_full_access_banner))
        HomeRulesFields(rules)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            template.key?.let { key -> TextButton(onClick = { uriHandler.openUri(key.url) }) { Text(stringResource(R.string.library_service_about_tokens)) } }
            TextButton(onClick = { uriHandler.openUri(template.docsUrl) }) { Text(stringResource(R.string.library_mcp_sheet_docs)) }
        }
    }
}

/** The always-confirm rules as typed: extra entries (one per line or comma) and the setpoint range. */
internal class RulesForm(initial: Connector.HomeAssistantRules) {
    var extra by mutableStateOf(initial.alwaysConfirm.joinToString(", "))
    var min by mutableStateOf(number(initial.climateMin))
    var max by mutableStateOf(number(initial.climateMax))

    private fun rulesOrProblem(): Pair<Connector.HomeAssistantRules?, UiText?> {
        val lo = decimal(min)
        val hi = decimal(max)
        if (lo == null || hi == null) return null to UiText.Res(R.string.library_rules_range_not_numbers)
        val entries = extra.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val rules = Connector.HomeAssistantRules(entries, lo, hi)
        val problem = runCatching { rules.validate() }.exceptionOrNull()?.let { e -> e.message?.let(UiText::Plain) ?: UiText.Res(R.string.library_rules_check_fields) }
        return (if (problem == null) rules else null) to problem
    }

    /** The rules, or null while a field can't be read or the range is backwards. */
    val parsed: Connector.HomeAssistantRules? get() = rulesOrProblem().first

    /** Why [parsed] is null, for the user. */
    val problem: UiText? get() = rulesOrProblem().second

    companion object {
        private fun number(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

        private fun decimal(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

        val Saver = androidx.compose.runtime.saveable.listSaver<RulesForm, String>(
            save = { listOf(it.extra, it.min, it.max) },
            restore = { s -> RulesForm(Connector.HomeAssistantRules()).apply { extra = s[0]; min = s[1]; max = s[2] } },
        )
    }
}

/** What always needs a tap: the built-in list (fixed), the user's additions and the setpoint range. */
@Composable
internal fun HomeRulesFields(rules: RulesForm) {
    SectionLabel(stringResource(R.string.library_rules_title), Modifier.padding(top = Spacing.xs))
    val shown = rules.parsed ?: Connector.HomeAssistantRules()
    CardGroup {
        ListRow(
            title = stringResource(R.string.library_rules_physical_security),
            subtitle = stringResource(R.string.library_rules_physical_security_body, HomeAssistantSafety.builtInSummary(shown)),
            subtitleMaxLines = Int.MAX_VALUE,
            leading = { IconTile(LocusIcons.Lock, tint = Tone.Caution.color()) },
        )
    }
    val large = LocalDensity.current.fontScale >= 1.5f
    val minField = @Composable { m: Modifier ->
        OutlinedTextField(
            rules.min, { rules.min = it }, label = { Text(stringResource(R.string.library_rules_lowest)) }, singleLine = true, modifier = m,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        )
    }
    val maxField = @Composable { m: Modifier ->
        OutlinedTextField(
            rules.max, { rules.max = it }, label = { Text(stringResource(R.string.library_rules_highest)) }, singleLine = true, modifier = m,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        )
    }
    if (large) {
        minField(Modifier.fillMaxWidth())
        maxField(Modifier.fillMaxWidth())
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            minField(Modifier.weight(1f))
            maxField(Modifier.weight(1f))
        }
    }
    OutlinedTextField(
        rules.extra, { rules.extra = it },
        label = { Text(stringResource(R.string.library_rules_extra_label)) },
        placeholder = { Text(stringResource(R.string.library_rules_extra_placeholder)) },
        supportingText = { Text(stringResource(R.string.library_rules_extra_hint)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
    rules.problem?.let { Text(it.asString(), style = LocusType.supporting, color = Locus.colors.critical) }
}

/** Library's editor for an added Home Assistant server's always-confirm rules. */
@Composable
internal fun HomeRulesSheet(connector: Connector, onSave: (Connector.HomeAssistantRules) -> Unit, onDismiss: () -> Unit) {
    val rules = rememberSaveable(connector.id, saver = RulesForm.Saver) { RulesForm(connector.homeAssistant ?: Connector.HomeAssistantRules()) }
    LocusSheet(
        title = stringResource(R.string.library_connectors_what_always_asks),
        subtitle = stringResource(R.string.library_rules_sheet_subtitle, connector.name),
        onDismiss = onDismiss,
        action = stringResource(R.string.library_action_save),
        actionEnabled = rules.parsed != null,
        onAction = { rules.parsed?.let(onSave) },
    ) {
        HomeRulesFields(rules)
    }
}

/** The address a template connector was made for: its base URL without the template's API path. */
internal fun ConnectorTemplate.addressOf(baseUrl: String): String = baseUrl.removeSuffix(this.baseUrl.removePrefix(ServiceAddress.HOST))
